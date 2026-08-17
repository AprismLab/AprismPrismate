package com.aprism.prismate.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aprism.api.AprismPhase;
import com.aprism.api.IAprismMod;
import com.aprism.manifest.AprismManifest;
import com.aprism.manifest.DependencyResolutionException;
import com.aprism.manifest.DependencyResolver;
import com.aprism.manifest.VersionRange;
import com.aprism.api.ModContainer;
import com.aprism.prismate.LifecycleMapper;
import com.aprism.prismate.PrismateVersion;
import com.aprism.prismate.config.PrismateConfig;
import com.aprism.prismate.discovery.AjeDiscovery;
import com.aprism.prismate.discovery.LoadFailure;
import com.aprism.prismate.extraction.AjeExtractor;
import com.aprism.prismate.host.HostBridge;

/**
 * The embedded Aprism runtime in library mode (docs 01 Sections 4 and 6).
 * Drives the full Aprism lifecycle for packs discovered on a host loader:
 * discovery, extraction, dependency resolution against the Prismate
 * environment map, classpath injection through the {@link HostBridge}, and
 * strict phase-order dispatch (PREINIT -> INIT -> SETUP -> COMPLETE, then the
 * side CLIENT/SERVER).
 *
 * <p>Class identity invariant (docs 01 Section 9.1): entrypoints are loaded
 * through a classloader whose parent chain reaches Prismate's own loader, so
 * every mod binds to the same {@code com.aprism.api} classes Prismate ships.
 *
 * <p>Failure isolation (docs 01 principle 5): every per-pack failure is
 * recorded as a named {@link LoadFailure} and never aborts the remaining
 * packs.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class EmbeddedRuntime {

    private final HostBridge bridge;
    private final PrismateConfig config;
    private final PrismateEventBus eventBus = new PrismateEventBus();
    private final PrismateRegistry registry = new PrismateRegistry();
    private final PrismateLoadReport report = new PrismateLoadReport();
    private final PrismateInterModComms interModComms = new PrismateInterModComms();
    private final List<LoadFailure> failures = new ArrayList<>();

    private final Map<String, PrismateModContainer> mods = new LinkedHashMap<>();
    private final Map<String, AjeExtractor.ExtractedPack> extractedById = new LinkedHashMap<>();

    private PrismateModClassLoader fallbackLoader;
    private boolean hostInjectionAvailable = true;
    private boolean booted = false;
    private int discoveredCount = -1;

    private EmbeddedRuntime(HostBridge bridge, PrismateConfig config) {
        this.bridge = bridge;
        this.config = config;
    }

    /**
     * Creates the embedded runtime for a host loader boot.
     *
     * @param bridge the host loader bridge
     * @param config the Prismate configuration
     * @return a fresh, unbooted runtime
     */
    public static EmbeddedRuntime create(HostBridge bridge, PrismateConfig config) {
        return new EmbeddedRuntime(bridge, config);
    }

    /**
     * Runs the full load pipeline (discover, extract, resolve, inject) without
     * dispatching any lifecycle phase. Idempotent: a second call is a no-op.
     */
    public void boot() {
        if (booted) {
            return;
        }
        booted = true;
        discoverAndExtract();
        resolveAndOrder();
        injectClasspath();
    }

    /**
     * Steps 1-4 of the load pipeline: discover packs and extract them into
     * {@code <gameDir>/prismate/work/<modid>/}.
     */
    private void discoverAndExtract() {
        List<Path> scanDirs = new ArrayList<>();
        scanDirs.add(bridge.gameDir().resolve("mods"));
        for (String extra : config.getExtraAjeDirs()) {
            Path extraPath = Path.of(extra);
            scanDirs.add(extraPath.isAbsolute() ? extraPath : bridge.gameDir().resolve(extra));
        }

        AjeDiscovery discovery = new AjeDiscovery();
        List<AjeDiscovery.DiscoveredAje> discovered =
                discovery.discover(scanDirs, bridge.side(), failures);
        discoveredCount = discovered.size();
        bridge.log("Discovered " + discoveredCount + " .aje pack(s)");

        Path workRoot = bridge.gameDir().resolve("prismate").resolve("work");
        AjeExtractor extractor = new AjeExtractor();
        for (AjeDiscovery.DiscoveredAje aje : discovered) {
            long t0 = System.nanoTime();
            AjeExtractor.ExtractedPack pack = extractor.extract(aje, workRoot, failures);
            if (pack == null) {
                report.recordFailure("extraction", aje.manifest().id(),
                        aje.manifest().version(), ms(t0), "see load failures");
                continue;
            }
            extractedById.put(pack.manifest().id(), pack);
            report.recordOk("extraction", pack.manifest().id(), pack.manifest().displayName(),
                    pack.manifest().version(), ms(t0));
        }
    }

    /**
     * Steps 3 and ordering: validates dependencies against the Prismate
     * environment map (including the self-injected {@code aprism} id per
     * OPEN-1) and against other discovered packs, isolating unsatisfiable
     * packs per doc 01 Section 6 step 3, then topologically orders the
     * survivors with Aprism's {@link DependencyResolver}.
     */
    private void resolveAndOrder() {
        if (extractedById.isEmpty()) {
            return;
        }
        Map<String, String> environment = buildEnvironment();

        // Fixed-point elimination of packs with unsatisfiable dependencies.
        Map<String, AjeExtractor.ExtractedPack> remaining = new LinkedHashMap<>(extractedById);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, AjeExtractor.ExtractedPack> entry
                    : new LinkedHashMap<>(remaining).entrySet()) {
                AprismManifest manifest = entry.getValue().manifest();
                String problem = validateDepends(manifest, environment, remaining);
                if (problem != null) {
                    failures.add(new LoadFailure(LoadFailure.DEPENDENCY, manifest.id(),
                            entry.getValue().sourceAje().getFileName().toString(), problem));
                    report.recordFailure("dependency", manifest.id(), manifest.version(), 0, problem);
                    remaining.remove(entry.getKey());
                    changed = true;
                }
            }
        }

        // Rewrite provides-aliased dependency keys to the provider's mod id so
        // Aprism's resolver (which indexes by id only) orders them faithfully.
        Map<String, AprismManifest> forResolution = new LinkedHashMap<>();
        for (AjeExtractor.ExtractedPack pack : remaining.values()) {
            forResolution.put(pack.manifest().id(), rewriteProvidesDeps(pack.manifest(), environment, remaining));
        }

        try {
            List<ModContainer> ordered = new DependencyResolver()
                    .resolve(forResolution.values(), environment);
            for (ModContainer mc : ordered) {
                AjeExtractor.ExtractedPack pack = remaining.get(mc.getId());
                if (pack != null) {
                    mods.put(mc.getId(), new PrismateModContainer(pack.manifest(), pack.sourceAje()));
                }
            }
        } catch (DependencyResolutionException e) {
            // After fixed-point elimination the only remaining resolution
            // failure is a dependency cycle; name every involved pack.
            for (AjeExtractor.ExtractedPack pack : remaining.values()) {
                failures.add(new LoadFailure(LoadFailure.DEPENDENCY, pack.manifest().id(),
                        pack.sourceAje().getFileName().toString(),
                        "dependency resolution failed: " + e.getMessage()));
                report.recordFailure("dependency", pack.manifest().id(),
                        pack.manifest().version(), 0, e.getMessage());
            }
        }
    }

    /**
     * Validates one manifest's {@code depends} against the environment and the
     * remaining pack set.
     *
     * @return the failure reason, or {@code null} when satisfiable
     */
    private String validateDepends(AprismManifest manifest, Map<String, String> environment,
            Map<String, AjeExtractor.ExtractedPack> remaining) {
        if (manifest.depends() == null) {
            return null;
        }
        for (Map.Entry<String, String> dep : manifest.depends().entrySet()) {
            String depId = dep.getKey();
            String range = dep.getValue();
            if (environment.containsKey(depId)) {
                if (!rangeSatisfies(environment.get(depId), range)) {
                    return "requires environment " + depId + " " + range
                            + " but found " + environment.get(depId);
                }
                continue;
            }
            AjeExtractor.ExtractedPack direct = remaining.get(depId);
            if (direct != null) {
                if (!rangeSatisfies(direct.manifest().version(), range)) {
                    return "requires " + depId + " " + range + " but found "
                            + direct.manifest().version();
                }
                continue;
            }
            AjeExtractor.ExtractedPack provider = findProvider(depId, remaining);
            if (provider != null) {
                if (!rangeSatisfies(provider.manifest().version(), range)) {
                    return "requires " + depId + " " + range + " but provider '"
                            + provider.manifest().id() + "' is " + provider.manifest().version();
                }
                continue;
            }
            return "requires missing dependency " + depId;
        }
        return null;
    }

    /**
     * Finds a remaining pack whose {@code provides} list contains the id.
     */
    private AjeExtractor.ExtractedPack findProvider(String providedId,
            Map<String, AjeExtractor.ExtractedPack> remaining) {
        for (AjeExtractor.ExtractedPack pack : remaining.values()) {
            List<String> provides = pack.manifest().provides();
            if (provides != null && provides.contains(providedId)) {
                return pack;
            }
        }
        return null;
    }

    /**
     * Rewrites provides-aliased dependency keys to the provider's real mod id
     * so {@link DependencyResolver} (which indexes mods by id) can order them.
     */
    private AprismManifest rewriteProvidesDeps(AprismManifest manifest, Map<String, String> environment,
            Map<String, AjeExtractor.ExtractedPack> remaining) {
        if (manifest.depends() == null || manifest.depends().isEmpty()) {
            return manifest;
        }
        Map<String, String> rewritten = new LinkedHashMap<>();
        for (Map.Entry<String, String> dep : manifest.depends().entrySet()) {
            String depId = dep.getKey();
            if (environment.containsKey(depId) || remaining.containsKey(depId)) {
                rewritten.put(depId, dep.getValue());
                continue;
            }
            AjeExtractor.ExtractedPack provider = findProvider(depId, remaining);
            if (provider != null) {
                rewritten.put(provider.manifest().id(), dep.getValue());
            } else {
                rewritten.put(depId, dep.getValue());
            }
        }
        return new AprismManifest(
                manifest.schemaVersion(), manifest.id(), manifest.version(),
                manifest.displayName(), manifest.description(), manifest.environment(),
                manifest.entrypoints(), manifest.mixins(), rewritten, manifest.platforms(),
                manifest.accessWidener(), manifest.provides(), manifest.custom());
    }

    /**
     * Builds the Prismate environment map supplied to dependency resolution
     * (docs 01 Section 7). Injects the {@code aprism} id with the embedded
     * Aprism version, mirroring the canonical upstream behavior Aprism core
     * landed in v26.0 (OPEN-1 closed): both paths normalize the version the
     * same way, so {@code depends: {"aprism": ">=26.0"}} resolves identically
     * whether the pack is loaded by the Aprism agent or by Prismate.
     */
    private Map<String, String> buildEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("minecraft", bridge.minecraftVersion());
        switch (bridge.loaderKey()) {
            case "Fa" -> environment.put("fabricloader", bridge.hostLoaderVersion());
            case "N" -> environment.put("neoforge", bridge.hostLoaderVersion());
            case "Fo" -> environment.put("forge", bridge.hostLoaderVersion());
            default -> {
                // unknown loader key: no loader-specific env id
            }
        }
        environment.put("java", Integer.toString(Runtime.version().feature()));
        environment.put("aprism", PrismateVersion.embeddedAprismVersionNormalized());
        return environment;
    }

    /**
     * Mirrors Aprism's DependencyResolver range tolerance: null/empty/* always
     * satisfies; unparseable ranges or versions do not block the load.
     */
    private boolean rangeSatisfies(String actual, String range) {
        if (range == null || range.isEmpty() || "*".equals(range.trim())) {
            return true;
        }
        try {
            return VersionRange.parse(range).contains(actual);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /**
     * Step 5 of the load pipeline: injects extracted jars, resource
     * directories, and mixin configs through the host bridge, falling back to
     * the Prismate-managed child classloader when the host offers no working
     * injection path (docs 01 Section 9.3).
     */
    private void injectClasspath() {
        for (PrismateModContainer container : mods.values()) {
            AjeExtractor.ExtractedPack pack = extractedById.get(container.getId());
            long t0 = System.nanoTime();
            try {
                for (Path jar : pack.jars()) {
                    if (!injectJar(jar)) {
                        throw new IllegalStateException("no usable classpath injection path for "
                                + jar.getFileName());
                    }
                }
                if (pack.resourcesDir() != null) {
                    bridge.injectResourceDir(pack.resourcesDir());
                    // Classloader-level half of resource injection
                    // (v26.1-Alpha.4): also register the extracted resources/
                    // dir with the Prismate-managed loader so mods loaded
                    // through it can resolve their own resource entries even
                    // when the host has no runtime resource-injection path
                    // (NeoForge). On Fabric this is harmless (host already
                    // serves them; parent delegation finds them either way).
                    if (fallbackLoader == null) {
                        fallbackLoader = new PrismateModClassLoader(getClass().getClassLoader());
                    }
                    fallbackLoader.addResourceDir(pack.resourcesDir());
                }
                // Mixin configs: register every config the manifest declares
                // (the authoritative list, resolvable through the injected mod
                // jar) plus any config found under mixins/ that the manifest
                // forgot to declare. Mirrors Aprism core's manifest-driven
                // registerMixins semantics.
                java.util.LinkedHashSet<String> mixinConfigs = new java.util.LinkedHashSet<>();
                if (pack.manifest().mixins() != null) {
                    mixinConfigs.addAll(pack.manifest().mixins());
                }
                mixinConfigs.addAll(pack.mixinConfigs());
                for (String mixinConfig : mixinConfigs) {
                    bridge.offerMixinConfig(mixinConfig);
                }
                if (pack.manifest().accessWidener() != null) {
                    registerAccessWidener(container, pack);
                }
                report.recordOk("classpath", container.getId(), container.getDisplayName(),
                        container.getVersion(), ms(t0));
            } catch (RuntimeException e) {
                failures.add(new LoadFailure(LoadFailure.CLASSPATH, container.getId(),
                        pack.sourceAje().getFileName().toString(), e.getMessage()));
                report.recordFailure("classpath", container.getId(), container.getDisplayName(),
                        container.getVersion(), ms(t0), e.getMessage());
                mods.remove(container.getId());
            }
        }
    }

    /**
     * Registers the access widener a pack declares with the Prismate-managed
     * classloader. Host loaders apply no widener pass to runtime-injected
     * jars, so Prismate applies the Fabric-style rules itself when classes
     * load through its loader (docs 01 Section 6 step 5 parity). A malformed
     * widener is isolated as a named classpath failure for that pack only.
     */
    private void registerAccessWidener(PrismateModContainer container,
            AjeExtractor.ExtractedPack pack) {
        try {
            if (fallbackLoader == null) {
                fallbackLoader = new PrismateModClassLoader(getClass().getClassLoader());
            }
            Path widenerFile = pack.workDir().resolve(pack.manifest().accessWidener());
            fallbackLoader.getWidener().parseFile(widenerFile);
            bridge.log("Applied access widener '" + pack.manifest().accessWidener()
                    + "' for mod " + container.getId() + " ("
                    + fallbackLoader.getWidener().ruleCount() + " rule(s) registered)");
        } catch (java.io.IOException | RuntimeException e) {
            failures.add(new LoadFailure(LoadFailure.CLASSPATH, container.getId(),
                    pack.sourceAje().getFileName().toString(),
                    "access widener '" + pack.manifest().accessWidener()
                            + "' could not be applied: " + e));
        }
    }

    /**
     * Injects one jar. The jar is ALWAYS registered in the Prismate-managed
     * mod classloader (the classloading space entrypoints resolve through, so
     * behavior is deterministic and {@code com.aprism.api} binds to Prismate's
     * copy); it is ADDITIONALLY injected into the host classloader when the
     * host offers a working path, which is what makes mods visible to host
     * systems (resources, mixin, other mods).
     *
     * @return true when the jar is usable for class loading
     */
    private boolean injectJar(Path jar) {
        if (fallbackLoader == null) {
            fallbackLoader = new PrismateModClassLoader(getClass().getClassLoader());
        }
        fallbackLoader.addJar(jar);
        if (hostInjectionAvailable && !bridge.injectJar(jar)) {
            hostInjectionAvailable = false;
            bridge.log("Host classloader injection is unavailable; continuing in degraded "
                    + "mode (docs 01 Section 9.3). Mods load through a Prismate-managed "
                    + "classloader and stay invisible to host systems that scan the host "
                    + "classloader.");
        }
        return true;
    }

    /**
     * Dispatches the common lifecycle (PREINIT -> INIT -> SETUP -> COMPLETE)
     * in strict order over all loaded mods.
     */
    public void dispatchCommonLifecycle() {
        for (AprismPhase phase : LifecycleMapper.commonPhases()) {
            dispatchPhase(phase);
        }
    }

    /**
     * Dispatches the side phase matching the host's distribution side.
     */
    public void dispatchSideLifecycle() {
        dispatchPhase(LifecycleMapper.sidePhase(bridge.side()));
    }

    /**
     * Dispatches one lifecycle phase over every loaded mod in dependency
     * order. A mod throwing inside its entrypoint is isolated: the failure is
     * recorded and its remaining entrypoints are skipped, without affecting
     * the other mods (mirrors Aprism core's isolation semantics).
     *
     * @param phase the phase to dispatch
     */
    public void dispatchPhase(AprismPhase phase) {
        if (phase == AprismPhase.INIT) {
            interModComms.markInitPhaseReached();
        }
        String entrypointKey = LifecycleMapper.entrypointKeyFor(phase);
        for (PrismateModContainer container : new ArrayList<>(mods.values())) {
            AprismManifest manifest = container.getManifest();
            Map<String, List<String>> entrypoints = manifest.entrypoints();
            List<String> classNames = entrypoints == null
                    ? List.of()
                    : entrypoints.getOrDefault(entrypointKey, List.of());
            if (classNames.isEmpty()) {
                continue;
            }
            PrismateModContext context = new PrismateModContext(container, eventBus, registry, interModComms);
            ClassLoader loader = classLoaderForPacks();
            for (String className : classNames) {
                try {
                    Class<?> clazz = Class.forName(className, true, loader);
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    if (container.getInstance() == null) {
                        container.setInstance(instance);
                    }
                    if (instance instanceof IAprismMod mod) {
                        invokePhaseMethod(mod, context, phase);
                    } else {
                        failures.add(new LoadFailure(LoadFailure.LIFECYCLE, container.getId(),
                                null, "entrypoint " + className + " does not implement "
                                        + "com.aprism.api.IAprismMod"));
                    }
                } catch (ReflectiveOperationException e) {
                    String reason = "entrypoint " + className + " failed in phase " + phase
                            + ": " + rootMessage(e);
                    failures.add(new LoadFailure(LoadFailure.LIFECYCLE, container.getId(),
                            null, reason));
                    report.recordFailure("lifecycle", container.getId(), container.getVersion(),
                            0, reason);
                    break; // skip this mod's remaining entrypoints
                } catch (RuntimeException e) {
                    String reason = "mod threw in phase " + phase + ": " + fullChain(e);
                    failures.add(new LoadFailure(LoadFailure.LIFECYCLE, container.getId(),
                            null, reason));
                    report.recordFailure("lifecycle", container.getId(), container.getVersion(),
                            0, reason);
                    break; // isolate: remaining entrypoints of this mod skipped
                }
            }
        }
    }

    private void invokePhaseMethod(IAprismMod mod, PrismateModContext context, AprismPhase phase) {
        switch (phase) {
            case PREINIT -> mod.onPreInitialize(context);
            case INIT -> mod.onInitialize(context);
            case SETUP -> mod.onSetup(context);
            case COMPLETE -> mod.onComplete(context);
            case CLIENT, SERVER -> mod.onInitialize(context);
        }
    }

    /**
     * The classloader entrypoint classes are loaded through: the
     * Prismate-managed mod classloader holding every injected jar. Its
     * parent-first delegation guarantees mods bind to Prismate's
     * {@code com.aprism.api} classes (docs 01 Section 9.1). Protected so
     * tests can substitute a synthetic host loader.
     *
     * @return the classloader for mod entrypoint classes
     */
    protected ClassLoader classLoaderForPacks() {
        if (fallbackLoader == null) {
            fallbackLoader = new PrismateModClassLoader(getClass().getClassLoader());
        }
        return fallbackLoader;
    }

    /**
     * Renders the final load report including all named failures.
     *
     * @return the report text for the game log
     */
    public String renderReport() {
        StringBuilder sb = new StringBuilder(report.toSummary(
                PrismateVersion.prismateVersion(),
                bridge.loaderName() + " " + bridge.hostLoaderVersion()));
        if (!failures.isEmpty()) {
            sb.append("\nFailures (").append(failures.size()).append("):");
            for (LoadFailure failure : failures) {
                sb.append("\n  - ").append(failure.render());
            }
        }
        return sb.toString();
    }

    /**
     * Writes the rendered report into
     * {@code <gameDir>/prismate/reports/load-report.txt} (v26.0-Alpha.4 crash
     * and error reporting). Also mirrors each named failure into its own line
     * so bug reports can be filed directly from the file. Never throws: a
     * write failure is logged and swallowed.
     *
     * @return the written report file, or {@code null} on write failure
     */
    public Path writeReportFile() {
        try {
            Path reportsDir = bridge.gameDir().resolve("prismate").resolve("reports");
            java.nio.file.Files.createDirectories(reportsDir);
            Path reportFile = reportsDir.resolve("load-report.txt");
            String content = renderReport() + "\n\nGenerated: "
                    + java.time.LocalDateTime.now().withNano(0).toString() + "\n";
            java.nio.file.Files.writeString(reportFile, content,
                    java.nio.charset.StandardCharsets.UTF_8);
            return reportFile;
        } catch (java.io.IOException | RuntimeException e) {
            bridge.log("Could not write the Prismate report file: " + e);
            return null;
        }
    }

    /**
     * Writes a first-run guidance file when no {@code .aje} packs were
     * discovered (v26.0-Alpha.7 surface polish). Written once to
     * {@code <gameDir>/prismate/FIRST-RUN.txt} so a user who installed
     * Prismate but has no Aprism-native mods yet sees exactly where to put
     * them. Never throws: a write failure is logged and swallowed.
     *
     * @return the written guidance file, or {@code null} when skipped or on
     *         write failure
     */
    public Path writeFirstRunGuidanceIfEmpty() {
        if (discoveredCount != 0) {
            return null;
        }
        try {
            Path prismateDir = bridge.gameDir().resolve("prismate");
            java.nio.file.Files.createDirectories(prismateDir);
            Path guidanceFile = prismateDir.resolve("FIRST-RUN.txt");
            if (java.nio.file.Files.exists(guidanceFile)) {
                return null; // already written on a previous boot
            }
            String content = """
                    AprismPrismate first-run guidance
                    ===============================

                    Prismate is installed and running on %s %s, but no
                    Aprism-native .aje packs were found in this instance.

                    To load Aprism-native mods:
                      1. Place one or more *.aje files into:  <gameDir>/mods/
                         (the same mods folder this host loader uses)
                      2. Restart the game.
                      3. Check the log or <gameDir>/prismate/reports/load-report.txt
                         for the load report.

                    Notes:
                      - Do NOT run the Aprism javaagent in the same instance;
                        Prismate and the agent are mutually exclusive.
                      - Extra scan directories can be added in
                        <gameDir>/prismate/prismate.json ("extraAjeDirs").

                    Generated by AprismPrismate %s on %s
                    """.formatted(
                    bridge.loaderName(), bridge.hostLoaderVersion(),
                    PrismateVersion.prismateVersion(),
                    java.time.LocalDateTime.now().withNano(0).toString());
            java.nio.file.Files.writeString(guidanceFile, content,
                    java.nio.charset.StandardCharsets.UTF_8);
            return guidanceFile;
        } catch (java.io.IOException | RuntimeException e) {
            bridge.log("Could not write the Prismate first-run guidance file: " + e);
            return null;
        }
    }

    /**
     * Closes the fallback classloader, if one was created.
     */
    public void close() {
        interModComms.clear();
        if (fallbackLoader != null) {
            try {
                fallbackLoader.close();
            } catch (java.io.IOException e) {
                java.util.logging.Logger.getLogger("prismate")
                        .warning("Failed to close fallback classloader: " + e.getMessage());
            }
            fallbackLoader = null;
        }
    }

    /**
     * @return all loaded mod containers in dependency order
     */
    public List<PrismateModContainer> getMods() {
        return List.copyOf(mods.values());
    }

    /**
     * @param id the mod id
     * @return the container, or {@code null}
     */
    public PrismateModContainer getMod(String id) {
        return mods.get(id);
    }

    /**
     * @return the shared event bus
     */
    public PrismateEventBus getEventBus() {
        return eventBus;
    }

    /**
     * @return the shared registry
     */
    public PrismateRegistry getRegistry() {
        return registry;
    }

    /**
     * @return the shared inter-mod communication surface
     */
    public PrismateInterModComms getInterModComms() {
        return interModComms;
    }

    /**
     * @return all recorded load failures
     */
    public List<LoadFailure> getFailures() {
        return List.copyOf(failures);
    }

    /**
     * @return the startup load report
     */
    public PrismateLoadReport getReport() {
        return report;
    }

    /**
     * @return whether the degraded (fallback classloader) path is active
     */
    public boolean isDegraded() {
        return !hostInjectionAvailable;
    }

    private static long ms(long t0Nanos) {
        return (System.nanoTime() - t0Nanos) / 1_000_000;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.toString();
    }

    /**
     * Renders the full cause chain of a throwable into a single line, so
     * wrapped failures (e.g. host classloader Mixin transformation errors)
     * expose their root cause in the load report.
     */
    private static String fullChain(Throwable t) {
        StringBuilder sb = new StringBuilder(t.toString());
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
            sb.append(" -> ").append(cur);
        }
        return sb.toString();
    }
}
