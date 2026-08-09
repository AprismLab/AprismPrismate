package com.aprism.prismate.neoforge;

import java.nio.file.Path;
import java.util.logging.Logger;

import com.aprism.prismate.PrismateVersion;
import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.host.HostBridge;

/**
 * The NeoForge implementation of {@link HostBridge} (docs 01 Section 4.1).
 * Supplies the environment ids for dependency resolution ({@code minecraft},
 * {@code neoforge}, {@code java}, plus the runtime-injected {@code aprism}
 * id) and the classpath injection path.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class NeoForgeHostBridge implements HostBridge {

    private static final Logger LOG = Logger.getLogger("prismate.neoforge");

    private final EnvSide sideOverride;
    private final Path gameDirOverride;

    /**
     * Constructs the bridge with values resolved from the live NeoForge
     * runtime.
     */
    public NeoForgeHostBridge() {
        this(null, null);
    }

    /**
     * Constructs the bridge with explicit values (used when the live runtime
     * is unavailable at construction time, and by tests).
     *
     * @param side    the distribution side, or {@code null} to detect
     * @param gameDir the game directory, or {@code null} to detect
     */
    public NeoForgeHostBridge(EnvSide side, Path gameDir) {
        this.sideOverride = side;
        this.gameDirOverride = gameDir;
    }

    @Override
    public String loaderKey() {
        return "N";
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public String hostLoaderVersion() {
        try {
            Object versionInfo = versionInfo();
            if (versionInfo != null) {
                Object neoForgeVersion = versionInfo.getClass().getMethod("neoForgeVersion")
                        .invoke(versionInfo);
                if (neoForgeVersion != null && !neoForgeVersion.toString().isBlank()) {
                    return neoForgeVersion.toString();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to the pin
        }
        return PrismateVersion.pinFml();
    }

    @Override
    public String minecraftVersion() {
        try {
            Object versionInfo = versionInfo();
            if (versionInfo != null) {
                Object mcVersion = versionInfo.getClass().getMethod("mcVersion")
                        .invoke(versionInfo);
                if (mcVersion != null && !mcVersion.toString().isBlank()) {
                    return mcVersion.toString();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to the pin
        }
        return PrismateVersion.minecraftVersion();
    }

    @Override
    public EnvSide side() {
        if (sideOverride != null) {
            return sideOverride;
        }
        try {
            Object loader = Class.forName("net.neoforged.fml.loading.FMLLoader")
                    .getMethod("getCurrent").invoke(null);
            Object dist = loader.getClass().getMethod("getDist").invoke(loader);
            return "CLIENT".equalsIgnoreCase(dist.toString())
                    ? EnvSide.CLIENT
                    : EnvSide.DEDICATED_SERVER;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return EnvSide.CLIENT;
        }
    }

    @Override
    public Path gameDir() {
        if (gameDirOverride != null) {
            return gameDirOverride;
        }
        try {
            Object loader = Class.forName("net.neoforged.fml.loading.FMLLoader")
                    .getMethod("getCurrent").invoke(null);
            Object gamePath = loader.getClass().getMethod("getGameDir").invoke(loader);
            if (gamePath instanceof Path path) {
                return path;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through
        }
        return Path.of(".");
    }

    /**
     * Resolves the FML {@code VersionInfo} record. FML 11 exposes it as an
     * instance method on the current loader ({@code FMLLoader.getCurrent()});
     * older FML releases exposed a static {@code versionInfo()} method. Both
     * shapes are tried.
     */
    private static Object versionInfo() throws ReflectiveOperationException {
        Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
        try {
            Object loader = fmlLoader.getMethod("getCurrent").invoke(null);
            return loader.getClass().getMethod("getVersionInfo").invoke(loader);
        } catch (ReflectiveOperationException e) {
            return fmlLoader.getMethod("versionInfo").invoke(null);
        }
    }

    @Override
    public boolean injectJar(Path jar) {
        return NeoForgeClassloaderBridge.inject(jar);
    }

    @Override
    public void injectResourceDir(Path resourcesDir) {
        // v26.1-Alpha.4: the extracted resources/ directory is registered
        // with the Prismate-managed classloader by the runtime itself, so
        // mods loaded through it can serve their own resource entries. What
        // NeoForge still lacks is host-level resource-manager integration
        // (visible to the host's own resource reload / other mods) — there is
        // no public FML 11 runtime resource-injection API, so that stays
        // deferred (docs 01 Section 13 issue 2).
        LOG.info("Resource dir " + resourcesDir
                + " is served via the Prismate-managed classloader; host-level "
                + "resource-manager integration is not available on NeoForge");
    }

    @Override
    public void offerMixinConfig(String configName) {
        // Best-effort registration with the host Mixin environment. On
        // NeoForge the Mixin subsystem is sealed after FML's mod-loading
        // bootstrap, so late addConfiguration calls throw; the root cause is
        // unwrapped and reported so the load report names the boundary
        // (v26.1-Alpha.4, docs 01 Section 13 issue 1).
        try {
            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins");
            mixins.getMethod("addConfiguration", String.class).invoke(null, configName);
            LOG.info("Registered mixin config '" + configName
                    + "' with the NeoForge Mixin environment");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warning("Could not register mixin config '" + configName
                    + "' with the NeoForge Mixin environment: " + describe(e)
                    + " (mixin passthrough is a known NeoForge limitation; the mod's "
                    + "lifecycle still runs without its mixins)");
        }
    }

    /** Unwraps the cause chain to its root for a readable report line. */
    private static String describe(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.toString();
    }

    @Override
    public void log(String message) {
        LOG.info(message);
    }
}
