# FACT.md - AprismPrismate Project Tracking

> Maintained by BlockConnect@StarsailsClover
> Convention: read & update this file before and after every task session.
> Versioning mirrors Aprism: v26 = 2026 line, minors v26.0-v26.9, Alpha.1-9 per
> minor as GitHub Pre-Releases, bare number = minor official, annual edition
> v26.2026 each December. Artifact naming per Prismate docs 01 §10.4:
> `AprismPrismate-v<ver>-<loaderkey>-<mcver>.jar` with loader keys Fa/N/Fo.

## 1. Project Identity

- **Name:** AprismPrismate
- **Role:** Loader-side bridge mod that runs INSIDE Fabric / NeoForge / Forge
  and lets those loaders load Aprism-native `.aje` packs. Mirror counterpart of
  AprismRefract (Refract brings other loaders into Aprism; Prismate brings
  Aprism into other loaders).
- **Author:** BlockConnect@StarsailsClover
- **Repo:** https://github.com/NDBlockConnect/AprismPrismate (GitHub)
- **License:** Apache-2.0
- **Canonical docs:** docs/01-architecture-design.md, docs/02-developer-guide.md
  (English canonical; README.zh-CN.md is the synced Chinese summary)

## 2. Relationship to the Aprism Family

| Project | Direction | Runs inside | Loads |
|---|---|---|---|
| Aprism (core) | native | the JVM (javaagent) | `.aje`/`.abe`/`.aep` |
| AprismRefract | other loaders INTO Aprism | Aprism (`.aep` extension) | Fabric/NeoForge/Forge/Quilt/LiteLoader mods |
| AprismPrismate | Aprism INTO other loaders | Fabric / NeoForge / Forge | Aprism-native `.aje` packs |

Prismate is NOT a replacement for the Aprism agent; it is mutually exclusive
with it in one instance (docs 01 §9.2).

## 3. Build Consumption Model

- Aprism modules (`aprism-api`, `aprism-manifest`, `aprism-loader-core`) are
  consumed via Gradle composite build (`includeBuild '../Aprism'`) during
  co-development; `mavenLocal()` is the fallback for release builds.
- Embedding rule (docs 01 §9.1): relocate `com.aprism.loader` and
  `com.aprism.manifest` to `com.aprism.prismate.internal.**`; do NOT relocate
  `com.aprism.api` (mod jars must bind to the canonical API classes).
- Embedded Aprism core version is pinned in gradle.properties
  (`embeddedAprismVersion`) and injected into the dependency environment as the
  `aprism` environment id (interim fix for OPEN-1).

## 4. Decisions and Open Items

### Decisions (resolved)

- **DECISION-1 (Forge scope):** Alpha line ships Fabric + NeoForge first; the
  `forge/` module is a compiling stub in Alpha.1. Forge enters scope no earlier
  than v26.0-Alpha.5 and only after re-confirmation (Forge is legacy).
- **DECISION-2 (discovery location):** default discovery is `<gameDir>/mods/`
  (recursive for `*.aje`); extra directories can be added via
  `<gameDir>/prismate/prismate.json` (`extraAjeDirs`).
- **DECISION-3 (lib/ JiJ deps):** supported from Alpha.1; `lib/*.jar` entries
  are extracted and injected exactly like the main `<modid>.jar`, matching
  Aprism core behavior.
- **Extraction strictness:** the `.aje` structural purity rules of Aprism doc
  07 §3/§9 are enforced: exactly one root-level `<modid>.jar` named after the
  mod id; jars under `resources/` or `mixins/` are violations; per-loader
  subdirectories are violations. Violations are named load failures, never
  silent skips.
- **DECISION-4 (API/config freeze, v26.0-Alpha.9):** the public surface is
  FROZEN for the v26.0 line. Frozen = the `aprism-api` interface contract
  (`IAprismMod`/`AprismContext`/`AprismPhase`/event bus/registry), the
  `.aje` manifest schema Prismate honors, the `prismate/prismate.json` config
  keys (`enabled`, `extraAjeDirs`), the `HostBridge` SPI, and the per-loader
  artifact naming. Additions are allowed only under the monotonic-increment
  contract (never remove/rename); deprecation with notice only. No new
  behavior lands in v26.0 after this freeze; fixes go to v26.0 official only.

### Open items (tracked to resolution)

- **OPEN-1 (no `aprism` env id in Aprism core):** CLOSED upstream in Aprism
  core (v26.0 line) — `loadMods` now supplies the normalized running Aprism
  version under the `aprism` id. Prismate's self-injection is now the
  fallback and stays in place (the embedded Aprism core may predate the
  upstream fix); both use the identical normalization (v-prefix + prerelease
  stripped, three-segment padded).
- **OPEN-2 (no `forge` env id):** low priority; only needed if Forge `.aje`
  dependency declarations appear. Deferred with the Forge module.
- **OPEN-3 (agent detection mechanism):** CLOSED upstream in Aprism core
  (v26.0 line) — `AprismAgent.initialize` now sets
  `aprism.agent.active=true`, which is Prismate's PRIMARY detection. The
  system-classloader probe for an initialized `com.aprism.loader.AprismRuntime`
  is retained as the fallback for Aprism agent versions that predate the
  property.
- **OPEN-4 (Fabric Knot injection API):** RESOLVED for Fabric Loader 0.16.14
  (Alpha.2): the stable injection entry point is
  `FabricLauncherBase.getLauncher().addToClassPath(Path, ...)`, reached
  reflectively and verified live in-game (no degraded-mode fallback).
- **OPEN-5 (NeoForge classpath extension):** RESOLVED for NeoForge
  26.2.0.53-beta (Alpha.3): no official runtime jar-injection API exists under
  FML 11's JPMS module layer, so Prismate degrades to its managed classloader
  (the documented path, works and verified). Host Mixin passthrough
  (Mixins.addConfiguration throws under FML's already-initialized environment)
  and resource-dir injection remain known limitations on NeoForge.

## 5. Roadmap: v26.0-Alpha.1 -> v26.0 official

> Principle (mirrors Aprism FACT §8b): every Alpha must (a) pass the full test
> suite, (b) advance at least one real verification step, (c) close a named
> gap. No Alpha ships without a green build + signed commits. Alpha builds are
> GitHub Pre-Releases; the bare number is the official Release.

**v26.0-Alpha.1 — Headless core (the complete loader pipeline, testable
without a game).**
MultiLoader scaffold (common/fabric/neoforge/forge) with composite build into
Aprism; AjeDiscovery (recursive scan, manifest parse + validation, environment
filtering, duplicate detection); AjeExtractor (structural-purity-enforcing
extraction of `<modid>.jar`/`resources/`/`mixins/`/`lib/` with zip-slip and
zip-bomb defenses); EmbeddedRuntime (DependencyResolver-backed ordering with
the Prismate environment map incl. the self-injected `aprism` id, strict
PREINIT->INIT->SETUP->COMPLETE then CLIENT/SERVER dispatch, per-mod failure
isolation, Aprism LoadReport reuse); PrismateModClassLoader (parent-first
delegation preserving `com.aprism.api` class identity); AgentConflictDetector;
Fabric + NeoForge entrypoints with reflective host injection bridges and the
URLClassLoader fallback; Forge stub; shadow relocation with a build-time check
that `com/aprism/api` stays unrelocated; CI build + cosign release pipelines.
Exit: full suite green headlessly on both platforms, pipelines in place.

**v26.0-Alpha.2 — Real-game Fabric landing.**
Verify OPEN-4 inside a real Fabric 26.2 instance (native Knot injection);
resource bridge so extracted `resources/` participates in Fabric's resource
loading; mixin passthrough registering extracted `mixins/*.json` with the host
Mixin environment; Prismate Load Report visible in the game log. Exit: a real
`.aje` with resources + a mixin loads and takes effect in a running Fabric
game (harness re-runnable).

**v26.0-Alpha.3 — Real-game NeoForge landing.**
Resolve OPEN-5 for the pinned NeoForge version (mod file / module-layer
participation or a documented degraded path); NeoForge lifecycle mapping
proven in a real instance; same sample `.aje` loads on NeoForge. Exit: sample
`.aje` initializes on real NeoForge 26.x.

**v26.0-Alpha.4 — Parity hardening.**
Access-widener support via the host loader's mechanism (or Prismate-side
bytecode pass when the host has none); JiJ `lib/` end-to-end in real games;
crash/error report files under `<gameDir>/prismate/reports/`; error-report
polish pass (every failure names the pack and the reason).

**v26.0-Alpha.5 — Forge scope gate + multi-mod soak.**
Re-confirm DECISION-1; implement the Forge entrypoint + bridge if in scope
(stub otherwise); multi-`.aje` soak with inter-mod dependencies in one real
instance; startup performance baseline recorded.

**v26.0-Alpha.6 — Upstream alignment.**
Land OPEN-1 (Aprism core `aprism` env id) and OPEN-3 (agent-set system
property) against Aprism; Prismate switches detection/env injection to the
canonical upstream mechanisms with fallbacks retained.

**v26.0-Alpha.7 — Surface and supply chain.**
Mod metadata passthrough (icon.png, display name into the host mod list where
supported), first-run guidance, SBOM + checksum + signing polish, docs pass.

**v26.0-Alpha.8 — Real-game harness + regression soak.**
MDL-driven (or direct-launch) harness running Prismate + sample `.aje` on BOTH
Fabric and NeoForge headlessly; mixed-modpack regression suite green.

**v26.0-Alpha.9 — Release candidate.**
API/config freeze, docs 01/02 updated from Design to Implemented, known-issues
list, final soak.

**v26.0 official (bare number) — GitHub Release.**
Signed official release collapsing Alpha.1-9; finalized docs; known-issues.

## 5b. Roadmap: v26.1-Alpha.1 -> v26.1 official (version-line expansion)

> Same Alpha rules as Section 5. Context at planning time (2026-08-10): the
> Aprism main project is at v26.1-Alpha.7 (334 tests) with a JE version-line
> foundation (VersionLineRegistry: 1.20.x REMAPPED/Java 17, 1.21.x
> REMAPPED/Java 21, 26.x NO_REMAP/Java 25) and an in-flight lower-level API
> (lowlevel/ package, uncommitted upstream). Prismate therefore needs (a) the
> version line opened from the 26.2 pin to JE 1.20..26.2 per loader, and
> (b) an upstream-drift discipline because Aprism keeps moving under us.

**v26.1-Alpha.1 — Version-line foundation + upstream sync discipline.**
Bump to v26.1-Alpha.1; embedded Aprism core pin -> v26.1. PrismateVersionLine:
a slim Prismate-owned JE line registry mirroring the segments of Aprism
v26.1-Alpha.7's VersionLineRegistry, guarded by a composite-build consistency
test against the upstream registry (drift alarm). Detected host MC version
below the line -> named refusal. New tools/upstream/ drift check: fetches the
Aprism sibling, lists commits newer than the recorded sync pin, rebuilds and
re-tests Prismate. Exit: suite green + consistency test + drift check in place.

**v26.1-Alpha.2 — Fabric line expansion: real 1.21.x.**
Lower the Fabric artifact bytecode floor to Java 17 (release 17) so it can run
on the whole JE line; open fabric.mod.json minecraft range to >=1.20 with the
per-segment Java semantics documented; build a real 1.21.x Fabric smoke
environment from the Fabric meta (client + libraries); verify the sample .aje
full lifecycle on a real 1.21.x Fabric instance. Exit: real 1.21.x smoke green
+ 26.2 regression still green.

**v26.1-Alpha.3 — Fabric legacy line: real 1.20.x + remap boundary.**
Real 1.20.x Fabric smoke on a Java 17 runtime proving the release-17 floor.
Document the Intermediary remap boundary: Prismate loads packs as-is; version
remapping is the Aprism agent's job, and .aje packs declare their target
version. Exit: real 1.20.x smoke green; boundary documented.

**v26.1-Alpha.4 — NeoForge line decision + known-issue attempt.**
NeoForge stays 26.x-only for the v26.1 line (the bridge is written against
FML 11; NeoForge 1.20.2-1.21.x runs FML 2-4, a different API — named out of
scope with rationale). Opportunistic: attempt the v26.0 known-issue closures
(NeoForge Mixin passthrough / resource injection) if Aprism has landed a
usable mechanism (e.g. the lowlevel hook API); otherwise record the deferral.
Exit: scope decision recorded + attempt made or deferral documented.

**v26.1-Alpha.5 — Multi-version regression matrix.**
run_all_regression.sh grows a version matrix: Fabric 26.2 + Fabric 1.21.x +
Fabric 1.20.x (if the environment is buildable) + NeoForge 26.2; per-version
startup baselines; mixed-pack soak on each. Exit: matrix green.

**v26.1-Alpha.6 — Upstream alignment pass.**
Re-sync against Aprism HEAD; adapt to whatever API has landed since Alpha.1
(lowlevel hooks, loaderext seam, remap changes); refresh the consistency test
and the sync pin. Exit: aligned with Aprism HEAD of the day, suite green.

**v26.1-Alpha.7 — Surface and supply chain (line-wide).**
Docs pass for the version line (README EN/ZH, docs 01/02); artifact naming
across the line; SBOM/checksum/signing re-verified; known-issues refresh.
Exit: docs + supply chain green.

**v26.1-Alpha.8 — Harness hardening + full-line soak.**
Full matrix + mixed-modpack soak; failure-injection drill on each supported
version (deliberately broken pack -> complete named report). Exit: all gates
green.

**v26.1-Alpha.9 — Release candidate.**
API/config freeze for the v26.1 line (monotonic additions: PrismateVersionLine
API, minecraft range semantics), docs Implemented, known-issues finalized,
final soak.

**v26.1 official (bare number) — GitHub Release.**
Signed official release collapsing Alpha.1-9; finalized docs; known-issues.

### Cross-cutting rules
- Version bumps follow the Aprism scheme; Prismate stays on the same minor
  line as the embedded Aprism core.
- Conventional Commits, SSH-signed commits and tags, no force-push to main.
- No emoji in any artifact. Artifacts signed BlockConnect@StarsailsClover.
- Keep this FACT.md session log current after each Alpha.

## 6. Conventions

- Default document language: English. Chinese summary kept in sync
  (README.zh-CN.md).
- Conversation language: Chinese.
- Build tool: Gradle 9.5.1 (wrapper committed), JDK 21 toolchain,
  `--release 21`.
- Interface contract: monotonic increment only (never remove/rename);
  deprecation allowed with notice.

## 7. Session Log

### Session 2026-08-09 (v26.0-Alpha.1-Phase0)
- [DONE] Researched the full Prismate doc set (01 architecture, 02 developer
  guide), the Aprism main-project docs + FACT history through v26.0-Alpha.8,
  and the AprismRefract conventions; verified the exact public API surface of
  aprism-api / aprism-manifest / aprism-loader-core to be reused.
- [DECISION] DECISION-1/2/3 resolved as recorded in Section 4; OPEN-1/3
  interim mechanisms fixed; OPEN-4/5 deferred to Alpha.2/Alpha.3 with the
  documented fallback implemented from Alpha.1.
- [DECISION] Embedded runtime = library mode. common depends on aprism-api
  (unrelocated, the mod-facing API) + aprism-manifest (relocated; the faithful
  ManifestParser/DependencyResolver/VersionRange/ManifestValidator so manifest
  semantics cannot drift). The event bus, mod context, mod container, registry,
  load report, and mod classloader are Prismate-owned classes in the `runtime`
  package implementing the aprism-api interfaces — this keeps the shaded jar
  minimal and provably free of Aprism's Mixin service classes (which would
  fight the host loader's Mixin). loader-core is NOT an Alpha.1 dependency.
- [DONE] Scaffold: settings.gradle (composite build into ../Aprism with
  dependencySubstitution for aprism-api/manifest/loader-core), root
  build.gradle, gradle.properties (versions + host pins), gradle/
  libs.versions.toml, Gradle 9.5.1 wrapper, .gitignore. Four subprojects:
  common, fabric, neoforge, forge.
- [DONE] common module (the complete headless loader pipeline):
  PrismateVersion (generated build-info resource, Aprism-version
  normalization), PrismateConfig (prismate/prismate.json with graceful
  fallback), EnvSide + HostBridge SPI, AgentConflictDetector (OPEN-3 interim:
  system-classloader probe for AprismRuntime + forward-compatible
  aprism.agent.active property), AjeDiscovery (recursive scan, Aprism
  ManifestParser/ManifestValidator reuse, environment filtering, duplicate
  detection, named failures incl. JDK-rejected hostile archives), AjeExtractor
  (structural purity: single <modid>.jar at root, no jars under
  resources//mixins/, no per-loader subdirs; zip-slip containment; 512 MiB /
  10k-entry zip-bomb budget; stale-extraction cleanup; mixin config listing),
  EmbeddedRuntime (fixed-point dependency elimination honoring provides
  aliases, environment map incl. self-injected `aprism` id, DependencyResolver
  ordering, classpath injection through the host bridge with deterministic
  PrismateModClassLoader resolution and degraded-mode detection, strict
  PREINIT->INIT->SETUP->COMPLETE then CLIENT/SERVER dispatch with per-mod
  failure isolation, LoadReport-style summary rendering), PrismateEventBus /
  PrismateRegistry / PrismateModContainer / PrismateModContext /
  PrismateLoadReport, LifecycleMapper, PrismateBootstrap orchestrator
  (bootEarly -> dispatchEarlyLifecycle -> dispatchSide -> dispatchComplete ->
  logReport).
- [DONE] fabric module: FabricEntrypoint (ModInitializer: guard + pipeline +
  PREINIT/INIT/SETUP, COMPLETE via Fabric API GAME_READY proxy when present
  else end-of-entrypoint), FabricClientEntrypoint / FabricServerEntrypoint
  (CLIENT/SERVER side phases), FabricClassloaderBridge (reflective
  FabricLauncherBase#getLauncher().addToClassPath), FabricHostBridge
  (environment ids, mixin config offer), fabric.mod.json with version
  expansion.
- [DONE] neoforge module: NeoForgeEntrypoint (@Mod constructor: guard +
  pipeline + PREINIT/INIT/SETUP; FMLClientSetupEvent/FMLDedicatedServerSetup
  Event -> side phases; FMLLoadCompleteEvent -> COMPLETE), NeoForgeHostBridge
  (FMLLoader.versionInfo probing), NeoForgeClassloaderBridge (best-effort
  reflective injection; degrades to managed classloader until Alpha.3),
  META-INF/neoforge.mods.toml.
- [DONE] forge module: ForgeEntrypoint stub per DECISION-1 (visible refusal,
  no work).
- [DONE] Shadow packaging per loader with the critical relocation rule:
  com.aprism.manifest -> com.aprism.prismate.internal.manifest, gson ->
  com.aprism.prismate.libs.gson, com.aprism.api UNRELOCATED; build-time
  doLast check verifies com/aprism/api presence and absence of any relocated
  API copy. Artifacts: AprismPrismate-v26.0-Alpha.1-Fa-26.2.jar and
  -N-26.2.jar.
- [DONE] Tests: 54 tests green on JDK 21 Temurin — AjeDiscoveryTest (12),
  AjeExtractorTest (8), EmbeddedRuntimeTest (13, end-to-end lifecycle with
  ASM-generated jar-only entrypoints recorded through a cross-classloader
  PhaseRecorder), PrismateConfigTest (6), DetectionAndMappingTest (10),
  PrismateBootstrapTest (4). Covers: strict phase order, dependency-resolved
  multi-mod ordering, missing-dep / version-conflict / throwing-mod
  isolation, provides aliases, environment ids incl. injected `aprism`,
  degraded classloader path, zip-slip/zip-bomb defenses, agent refusal, and
  config disable.
- [DONE] CI: build.yml (windows+ubuntu matrix, JDK 21, artifact upload) and
  release.yml (sibling checkout of Aprism for the composite build, cosign
  keyless signing, SHA-256 checksums, CycloneDX SBOM, Pre-Release), mirroring
  the Aprism / Refract pipelines.
- [NOTE] Local build uses JDK 21 Temurin (JAVA_HOME override; the machine's
  JAVA_HOME env var pointed at a stale JDK path) and mavenLocal-pinned
  fabric-loader 0.16.14 / fancymodloader loader 2.0.20 / bus 7.0.16
  (non-transitive) because the NeoForge Maven endpoint resets Gradle's TLS
  connections on this machine; CI resolves remotely.
- [NOTE] LICENSE file on disk is AGPL-3.0 while the READMEs still say
  Apache-2.0 (pre-existing inconsistency, inherited from the Aprism repo);
  not changed this session — flag for the owner.
- [STATUS] v26.0-Alpha.1 complete: full suite green, pipelines in place,
  committed as the Alpha 1 baseline.

### Session 2026-08-09 (v26.0-Alpha.2-Phase0) - Real-game Fabric landing
- [DONE] Real-game Fabric verification harness (tools/smoke/run_fabric_smoke.sh):
  launches genuine Minecraft 26.2 through Fabric Loader 0.16.14 (KnotClient)
  with Prismate installed as a Fabric mod, reusing the Aprism workspace smoke
  environment (client.jar + libraries + natives). Fabric runtime deps
  committed under tools/smoke/deps/. JDK 25 drives the game JVM.
- [VERIFIED] SMOKE PASS with all three assertion groups green:
  (1) lifecycle: ExampleMod PREINIT/INIT/SETUP/COMPLETE inside the live game;
  (2) mixin passthrough: prismatemix sample's mixin woven into the real
  net.minecraft.client.Minecraft class by Fabric's own Mixin environment
  (marker printed at the TAIL of Minecraft.<init>);
  (3) resource injection: assets from the pack's resources/ dir visible
  through the host classloader (ressmoke probe).
- [FIXED] Fabric entrypoint ordering: the load pipeline now runs in a
  `preLaunch` entrypoint (FabricPreLaunchEntrypoint), before any Minecraft
  class loads. Running it in ModInitializer (inside Minecraft.<init>) made
  mixins targeting Minecraft fail with MixinTargetAlreadyLoadedException.
  bootEarly() is now idempotent (preLaunch + main both invoke it; the second
  call is a no-op) — the double-boot previously re-ran extraction and hit
  Windows file locks on the still-open extracted jars.
- [FIXED] Mixin compatibility level: the Fabric Mixin environment stayed at
  its JAVA_8 default on the Java 25 game JVM, rejecting Java-21 mixin
  classes. Prismate now elevates the level reflectively
  (setCompatibilityLevel to the highest supported, JAVA_22) before
  registering each .aje mixin config.
- [FIXED] Lifecycle failure records now render the full cause chain (host
  classloader errors like IllegalClassLoadError were previously invisible).
- [NOTE] The Aprism workspace's mixinproof smoke pack violates the host
  Mixin environment's package-ownership rule (entrypoint class in the same
  package its mixin config owns -> IllegalClassLoadError on Fabric). Prismate
  uses its own prismatemix sample instead; the Aprism pack is fine under the
  Aprism agent because Aprism's own classloader does not enforce that rule.
- [NOTE] OPEN-4 resolved for Fabric Loader 0.16.14: the stable injection
  entry point is FabricLauncherBase.getLauncher().addToClassPath(Path, ...),
  reached reflectively; verified live in-game (no degraded-mode fallback).
- [DONE] FabricHostBridge.injectResourceDir implemented: extracted resources/
  dirs are added to the Knot classloader, making assets/data visible to
  Fabric's resource loading (proven by the ressmoke probe).
- [STATUS] v26.0-Alpha.2 scope (Fabric real-game landing + mixin passthrough
  + resources) verified; NeoForge real-game landing is the next milestone
  (Alpha.3 per the roadmap).

### Session 2026-08-09 (v26.0-Alpha.3-Phase0) - Real-game NeoForge landing
- [DONE] Real NeoForge verification harness (tools/smoke/): setup_neoforge_env.sh
  builds build/smoke-neoforge/libraries (vanilla MC 26.2 libraries from the
  Aprism smoke env + NeoForge 26.2.0.53-beta libraries from maven.neoforged.net
  + installertools-patched client jar); build_neoforge_client_args.sh merges the
  vanilla client classpath with the FML boot libraries and writes the @argfile.
- [VERIFIED] Vanilla NeoForge 26.2.0.53-beta boots to the main menu from this
  harness first (environment health proof) before Prismate was added.
- [VERIFIED] SMOKE PASS with Prismate on real NeoForge 26.2:
  (1) Prismate loaded as a genuine NeoForge mod into FML 11's JPMS module
  layer ("aprismprismate" in the game content classloader, version
  26.2.0.53-beta detected via FMLLoader.getCurrent().getVersionInfo());
  (2) 3 .aje packs discovered/extracted/injected;
  (3) full PREINIT->INIT->SETUP->COMPLETE lifecycle dispatched inside the live
  game ([ExampleMod] all four phases);
  (4) Load Report printed: Loaded 6, failed 0; game continued to render.
- [FIXED] FML 11 API migration: FMLJavaModLoadingContext was removed in FML 11;
  the entrypoint now takes the mod-scoped IEventBus via constructor injection.
  NeoForgeHostBridge probes FMLLoader.getCurrent() (instance API) with a
  fallback to the legacy static versionInfo().
- [FIXED] Module-layer hygiene: slf4j (59 classes, transitive from the Aprism
  API) is excluded from the shaded jar — Prismate uses java.util.logging and
  bundling slf4j caused LinkageError between the app loader and the module
  classloader.
- [FIXED] Production launch anatomy (recorded for future harnesses): the
  NeoForge installer's win_args.txt is a SERVER template; a client launch needs
  the merged vanilla-client + FML-boot library set, the patched client jar and
  NeoForge universal jar must NOT be on -cp (FML locates them from
  libraryDirectory), earlyWindowControl=false in config/fml.toml for headless,
  and the jar manifest version must be Maven-legal (no 'v' prefix).
- [NOTE] Toolchain: the neoforge module compiles with a Java 25 toolchain
  (FML 11 jars are class file version 69) but emits release-21 bytecode so the
  shadow plugin's ASM can remap and the artifact stays portable.
- [OPEN] OPEN-5 refined for FML 11: classpath injection degrades to the
  Prismate-managed classloader (works, verified above); host Mixin passthrough
  (Mixins.addConfiguration throws under FML's already-initialized environment)
  and resource-dir injection remain the Alpha.4+ items.
- [STATUS] v26.0-Alpha.3 verified on both Fabric (Alpha.2) and NeoForge
  (Alpha.3) real games.

### Session 2026-08-09 (v26.0-Alpha.4-Phase0) - Parity hardening
- [DONE] Access widener support (Prismate-side bytecode pass): new
  PrismateAccessWidener (Fabric accessWidener v1 rule parser + ASM widening
  visitor; accessible/extendable/mutable on class/method/field, O(1) per-class
  rule lookup, zero-overhead passthrough when no rules match). Wired into
  PrismateModClassLoader.findClass so classes loading through the managed
  classloader are widened at define time; EmbeddedRuntime.registerAccessWidener
  parses each pack's manifest-declared accessWidener file and isolates a
  malformed widener as a named classpath failure for that pack only.
- [FIXED] Jar-lock regression introduced by the in-flight widener work:
  reading class bytes through url.openStream() parked opened JarFiles in the
  static JarURLConnection cache, holding Windows file locks past
  URLClassLoader.close() and breaking @TempDir cleanup (13 tests failed).
  Fixed with useCaches=false plus a fast path that skips the read entirely for
  classes without widener rules.
- [DONE] JiJ lib/ end-to-end coverage: EmbeddedRuntimeTest$JiJLib proves
  lib/*.jar is extracted and injected alongside the main jar.
- [DONE] Crash/error report files: EmbeddedRuntime.writeReportFile() writes the
  full load report (every named failure with pack + reason) to
  <gameDir>/prismate/reports/load-report.txt; PrismateBootstrap.logReport()
  writes it after the COMPLETE dispatch. Error-report polish pass confirmed
  every LoadFailure renders stage + pack id + file name + reason.
- [DONE] Shadow relocation updated for the widener: ASM is now bundled with
  common (implementation scope) and relocated to com.aprism.prismate.libs.asm
  in both the Fabric and NeoForge shaded jars so it never collides with the
  ASM version the host loader ships.
- [DONE] Tests: PrismateAccessWidenerTest (13: parsing validation x5, ASM
  transform x8) + PrismateModClassLoaderWidenerTest (3: load-time widening,
  unrelated-class passthrough, no-rules passthrough) + JiJ lib injection +
  report file tests. 72 tests green on JDK 21 Temurin.
- [VERIFIED] Real-game Fabric smoke PASS against the v26.0-Alpha.4 jar: full
  lifecycle (PREINIT/INIT/SETUP/COMPLETE), mixin passthrough woven into the
  real Minecraft class, resource injection visible, and the load report file
  written in-game (Loaded 6, failed 0).
- [NOTE] Known semantic boundary (recorded per docs 01 Section 3.3): the
  Prismate-side widener applies to classes resolved through the managed
  classloader; when the host loader's own injection succeeds, host-loaded mod
  classes rely on the host's own widener mechanism (Fabric applies fabric.mod.json
  accessWidener for mods it discovered itself). This mirrors the docs'
  "Prismate-side bytecode pass when the host has none" clause and will be
  documented in the Alpha.7 known-issues pass.
- [DONE] Version bumped to v26.0-Alpha.4 (gradle.properties, artifact-name
  comments). Full suite green + real-game smoke pass; committed and tagged.

### Session 2026-08-09 (v26.0-Alpha.5-Phase0) - Forge scope gate + multi-mod soak
- [DONE] Multi-mod soak harness (tools/smoke/soak/): three probe .aje packs
  with real cross-pack dependencies — soakcore (no deps), soakapi (provides
  the virtual id soak-api), soakconsumer (depends on soak-api >=1.0.0) — plus
  a real-game harness that asserts all three reach the full lifecycle, that
  the provider initializes BEFORE the consumer (provides-alias resolution +
  dependency-resolved ordering), and that the load report shows Loaded 6 /
  failed 0.
- [VERIFIED] SOAK PASS in a real Minecraft 26.2 / Fabric 0.16.14 instance:
  all three soak packs loaded and dispatched in dependency order; the
  provides-alias resolved correctly; no failures.
- [DONE] Startup performance baseline recorded: total boot = 17771 ms
  (Prismate v26.0-Alpha.4, MC 26.2, Fabric, 3 soak packs; includes full
  Minecraft boot). Baseline file: build/smoke-soak/boot_ms.txt.
- [DECISION] DECISION-1 (Forge scope) RE-CONFIRMED: Forge (classic) remains
  OUT of scope for the entire v26.0 Alpha line and defers to post-1.0.
  Rationale: classic Forge is legacy/unmaintained, NeoForge is the forward
  path, and both real-game landings (Fabric Alpha.2, NeoForge Alpha.3)
  already cover the two modern loaders. The forge/ module stays a visible
  stub that refuses to boot with a named error.
- [DONE] Version bumped to v26.0-Alpha.5; embedded Aprism core aligned to the
  v26.0 GA release (gradle.properties + libs.versions.toml).

### Session 2026-08-09 (v26.0-Alpha.6-Phase0) - Upstream alignment
- [DONE] Landed OPEN-1 upstream in Aprism core: AprismRuntime.loadMods now
  supplies the normalized running Aprism version under the `aprism`
  environment id; ExtensionLoader.normalizeAprismVersion widened to
  package-private for reuse. Tests: aprism env id resolves / mismatch aborts.
  (Aprism commit 04da150; 287 tests green.)
- [DONE] Landed OPEN-3 upstream in Aprism core: AprismAgent.initialize now
  sets aprism.agent.active=true. Test: premain sets the property. (Aprism
  commit 04da150.)
- [DONE] Prismate switched to the canonical mechanisms with fallbacks
  retained: AgentConflictDetector checks aprism.agent.active FIRST (now set
  by the agent) and keeps the system-classloader probe as the fallback for
  pre-property agents; EmbeddedRuntime.buildEnvironment keeps injecting the
  `aprism` id (now identical to the upstream behavior) as the fallback for
  embedded Aprism cores that predate the fix.
- [DONE] Version bumped to v26.0-Alpha.6.

### Session 2026-08-09 (v26.0-Alpha.7-Phase0) - Surface and supply chain
- [DONE] Mod metadata passthrough (v26.0-Alpha.7): the load report now
  surfaces each pack's manifest displayName alongside its id (verified in the
  real game: "examplemod (Example Mod) 1.0.0"); PrismateLoadReport.Entry
  gained displayName with backward-compatible recordOk/recordFailure
  overloads.
- [DONE] Icon metadata: AjeExtractor.ExtractedPack now exposes iconPath (the
  extracted root icon.png, or null) so host bridges can pass display metadata
  to the host mod list where supported.
- [DONE] First-run guidance: when no .aje packs are discovered, Prismate
  writes a one-time <gameDir>/prismate/FIRST-RUN.txt telling the user where
  to place mods, how to verify, and the agent mutual-exclusion rule.
- [DONE] Supply chain verified in place: release.yml ships SHA-256 checksums,
  cosign keyless signatures (.sig/.bundle), and a CycloneDX SBOM per tag —
  confirmed against the Alpha.4/Alpha.5/Alpha.6 releases; shaded-jar
  relocation layout re-verified (com/aprism/api present and unrelocated,
  manifest + ASM relocated, no leaked com/aprism/manifest).
- [DONE] Tests: 5 new (icon present/absent extraction, report displayName,
  first-run guidance written/skipped). 77 tests green; real-Fabric smoke
  PASS (lifecycle + mixin + resources + displayName in the in-game report).
- [DONE] Version bumped to v26.0-Alpha.7.

### Session 2026-08-09 (v26.0-Alpha.8-Phase0) - Real-game harness + regression soak
- [DONE] Unified real-game regression runner (tools/smoke/run_all_regression.sh):
  runs the three real-game harnesses in sequence against the current version —
  Fabric lifecycle+mixin+resources, NeoForge lifecycle+report, and the Fabric
  multi-mod dependency soak — printing a PASS/FAIL summary per harness and
  exiting non-zero on the first failure. This is the pre-release gate for
  every v26.0 Alpha.
- [VERIFIED] REGRESSION PASS on v26.0-Alpha.7: all three real-game harnesses
  green in one run. Soak startup baseline improved to 11242 ms total boot
  (vs 17771 ms at Alpha.4, same 3-pack soak).
- [VERIFIED] REGRESSION PASS on v26.0-Alpha.8 artifacts: all three real-game
  harnesses green against the freshly built Alpha.8 jars (Fabric lifecycle +
  mixin + resources, NeoForge lifecycle + report, Fabric multi-mod soak).
  Soak startup baseline improved to 9352 ms total boot (vs 11242 ms at
  Alpha.7, same 3-pack soak). Headless suite 77 tests green; build artifacts
  relocation layout re-verified.
- [DONE] Version bumped to v26.0-Alpha.8.

### Session 2026-08-09 (v26.0-Alpha.9-Phase0) - Release candidate
- [DONE] Docs 01/02 updated from Design to Implemented (release candidate):
  version headers bumped to v26.0-Alpha.9.
- [DONE] docs 01 §11 Open Items rewritten to final resolution status (OPEN-1/3
  closed upstream, OPEN-4 resolved, OPEN-5 resolved via degraded path); §12
  milestones rewritten to as-shipped status; new §13 Known Issues added
  (NeoForge Mixin passthrough, NeoForge resource injection, Forge stub,
  Prismate-side widener boundary, MC 26.2 pin).
- [DONE] DECISION-4 recorded: API/config freeze for the v26.0 line
  (aprism-api contract, .aje manifest schema, prismate.json config keys,
  HostBridge SPI, artifact naming) under the monotonic-increment contract.
- [DONE] FACT.md OPEN-4/OPEN-5 entries updated to final resolved status.
- [DONE] Version bumped to v26.0-Alpha.9.
- [VERIFIED] REGRESSION PASS on v26.0-Alpha.9 artifacts: all three real-game
  harnesses green (Fabric lifecycle + mixin + resources, NeoForge lifecycle +
  report, Fabric multi-mod soak). Soak startup baseline improved to 8887 ms
  total boot (vs 9352 ms at Alpha.8, same 3-pack soak). Headless suite 77
  tests green; build successful.
- [DONE] Final soak green; committed + tagged v26.0-Alpha.9 (release
  candidate). v26.0 official is the next step.

### Session 2026-08-09 (v26.0-Phase0) - v26.0 official (GA)
- [DONE] Version bumped to v26.0 (bare number = minor official, GitHub
  Release semantics; no stability suffix, per the Aprism family scheme).
- [DONE] release.yml fixed to distinguish channels by tag: Alpha tags
  (v26.0-Alpha.N) publish as Pre-Releases; the bare minor version (v26.0)
  publishes as the official Release (previously hardcoded --prerelease).
- [VERIFIED] REGRESSION PASS on v26.0 artifacts: all three real-game harnesses
  green (Fabric lifecycle + mixin + resources, NeoForge lifecycle + report,
  Fabric multi-mod soak). Soak startup baseline 9055 ms total boot (3-pack
  soak). Headless suite 77 tests green; build successful.
- [VERIFIED] v26.0 artifact relocation layout: com/aprism/api present and
  unrelocated (20 classes), manifest + loader relocated to
  com.aprism.prismate.internal, no leaked com/aprism/manifest or
  com/aprism/loader in either loader jar.
- [DONE] Docs 01/02 finalized at Implemented (release candidate) status;
  known-issues list committed at Alpha.9 (docs 01 §13). v26.0 official
  collapses Alpha.1-9 per the scheme.
- [FIXED] CI release pipeline (was failing for Alpha.6-Alpha.9 and v26.0):
  two root causes in GitHub Actions. (1) The forge stub declared compileOnly
  on NeoForge fancymodloader 11.0.16 (Java 25 bytecode) but the stub only
  uses java.util.logging and classic Forge is net.minecraftforge anyway; the
  unneeded FML deps were removed so the stub compiles on the JVM 21 CI
  toolchain. (2) The neoforge module requires a Java 25 toolchain that CI
  runners lack; added the foojay-resolver-convention 1.0.0 settings plugin so
  Gradle auto-provisions JDK 25 from the Foojay Disco API on CI (local
  machines supply it via org.gradle.java.installations.paths). 77 tests green
  locally after both fixes. v26.0 tag re-pointed to the fixed commit.

### Session 2026-08-10 (v26.1-Alpha.1-Phase0) - Version-line foundation + upstream sync discipline
- [DONE] Researched the Aprism main project's v26.1 progress (at planning time
  v26.1-Alpha.7, VersionLineRegistry: 1.20.x REMAPPED/Java 17, 1.21.x
  REMAPPED/Java 21, 26.x NO_REMAP/Java 25; an in-flight lowlevel API package)
  and drafted the v26.1 roadmap (FACT.md Section 5b): Alpha.1 version-line
  foundation + upstream sync discipline; Alpha.2/3 Fabric 1.21.x/1.20.x real
  landings; Alpha.4 NeoForge line decision; Alpha.5 multi-version regression
  matrix; Alpha.6 upstream alignment pass; Alpha.7 surface/supply chain;
  Alpha.8 harness hardening; Alpha.9 release candidate; v26.1 official.
- [DONE] PrismateVersionLine (common/.../version/): Prismate-owned mirror of
  the Aprism JE version line (segments 1.20/1.21/26 with profile, Java
  baseline, mappings source; resolve/isWithinSupportedLine/supportedLine/
  describeLine). Kept Prismate-owned rather than depending on loader-core at
  runtime, per the embedded-runtime minimalism rule (docs 01 Section 9.1).
- [DONE] Version-line boot gate: PrismateBootstrap.bootEarly() refuses with
  BootOutcome.VERSION_UNSUPPORTED (new, monotonic addition) when the host
  Minecraft version resolves to no segment; FakeHostBridge gained a settable
  MC version for tests.
- [DONE] Upstream drift discipline: gradle.properties records
  embeddedAprismVersion=v26.1 and the new aprismSyncPin; new
  tools/upstream/check_upstream_drift.sh compares the sibling Aprism HEAD
  against the pin, lists new commits, re-runs the suite, and (with --sync)
  bumps the pin once green. The consistency test lives in its OWN source set
  (consistencyTest) because it needs the full Aprism loader-core on its
  classpath while the main suite must NOT (loader-core on the main test
  classpath makes AgentConflictDetector's system-classloader fallback
  false-positive AGENT_CONFLICT).
- [VERIFIED] Drift check caught a REAL upstream push during development: the
  Aprism sibling moved from e432c1d (v26.1-Alpha.7) to a7c9b9c
  (v26.1-Alpha.8, lowlevel API foundation - ClassRedefiner/MethodHookRegistry/
  MethodHookTransformer). Prismate stayed green against it (lowlevel lives in
  loader-core, which Prismate does not embed); the pin was synced to a7c9b9c.
- [DONE] Tests: VersionLineConsistencyTest (4: line window, segment-by-segment
  field match, resolve agreement across probes, isWithinSupportedLine
  agreement) + PrismateBootstrapTest version-line cases (refusal below the
  line, boot on every supported segment). Headless suite 79 main tests + 4
  consistency tests green; build successful.
- [DONE] Version bumped to v26.1-Alpha.1; embedded Aprism pin -> v26.1.

### Session 2026-08-10 (v26.1-Alpha.2-Phase0) - Fabric line expansion: real 1.21.x
- [DONE] Real Minecraft 1.21.10 Fabric smoke environment
  (tools/smoke/setup_fabric121_env.sh): downloads the genuine 1.21.10 client
  jar + 85 Java libraries + LWJGL windows-x64 natives from Mojang piston and
  the Fabric runtime deps for 1.21.10 (fabric-loader 0.19.3, sponge-mixin
  0.17.3, intermediary, ASM 9.10.1) into build/smoke-fabric-121/ + committed
  tools/smoke/deps-121/. Assets intentionally omitted (the lifecycle
  assertions do not need them; the 26.2 smoke already proved an empty assets
  dir boots). Two environment fixes landed during bring-up: Windows-native
  python cannot open POSIX paths (added a cygpath wpath helper for every path
  handed to python), and Fabric 0.19.3's LoaderUtil.verifyClasspath rejects
  the vanilla ASM 9.6 jar next to Fabric's own ASM 9.10.1 (the classpath now
  excludes the org.ow2.asm group; Fabric supplies ASM).
- [DONE] Real 1.21.x Fabric harness (tools/smoke/run_fabric121_smoke.sh):
  launches genuine MC 1.21.10 through Fabric Loader 0.19.3 KnotClient with
  Prismate installed, asserting the full Aprism lifecycle
  (PREINIT/INIT/SETUP/COMPLETE) + resource-dir injection inside the live
  game. Version-agnostic sample packs only (examplemod lifecycle probe,
  ressmoke resource probe); prismatemix is deliberately excluded because its
  mixin targets net.minecraft.client.Minecraft, which is obfuscated on
  1.21.x — that remap is the Aprism agent's job (Alpha.3 remap boundary).
- [VERIFIED] SMOKE121 PASS: real Minecraft 1.21.10 + Fabric 0.19.3 boots with
  Prismate; the sample .aje reaches the full lifecycle and resource injection
  works. This is the first real landing on the 1.21 segment of the JE line.
- [DONE] Opened the Fabric dependency range: fabric.mod.json minecraft
  ">=26.2" -> ">=1.20" so Fabric accepts Prismate across the JE line
  (java stays ">=21", matching the embedded Aprism API's bytecode baseline).
- [DECISION] Bytecode floor scope refinement (replaces the roadmap's "lower
  Fabric to release 17"): the embedded com.aprism.api in the shaded artifact
  is Java 21 bytecode (upstream Aprism compiles at release 21; verified major
  version 65 in the jar). Lowering Prismate's own code to release 17 would be
  misleading — the artifact still cannot load the embedded API on a Java 17
  JVM. The real 1.21.x exit (a Java 21 game) is satisfied by the release-21
  artifact, so Prismate keeps release 21 for the 1.21 segment. A true
  release-17 floor (for 1.20.x/Java 17) is gated on upstream Aprism lowering
  its API baseline and is tracked as the Alpha.3 remap-boundary work.
- [VERIFIED] 26.2 regression still green on the Alpha.2 artifact: Fabric
  lifecycle+mixin+resources, NeoForge lifecycle+report, Fabric multi-mod soak
  (boot baseline 12341 ms). Headless suite green.
- [DONE] Version bumped to v26.1-Alpha.2.

### Session 2026-08-10 (v26.1-Alpha.3-Phase0) - Fabric legacy line: real 1.20.x + remap boundary
- [DONE] Generalized the real-Fabric tooling into a version-parameterized pair
  (setup_fabric121_env.sh [version] + run_fabric121_smoke.sh [version]):
  per-version dirs are slugged by major+minor (1.21.10 -> 121, 1.20.1 -> 120)
  matching the committed deps-121/build/smoke-fabric-121 layout; the asset
  index id is read from each version's own manifest (1.20.1 = 9, 1.21.10 = 27)
  instead of hardcoded. This is the building block for the Alpha.5
  multi-version regression matrix.
- [DONE] Real Minecraft 1.20.1 Fabric smoke environment (build/smoke-fabric-120):
  genuine 1.20.1 client jar + 64 Java libraries + LWJGL windows-x64 natives
  from Mojang piston + Fabric 0.19.3 runtime deps (committed under
  tools/smoke/deps-120, 4.3 MB).
- [VERIFIED] SMOKE121 PASS on MC 1.20.1: real Minecraft 1.20.1 + Fabric 0.19.3
  boots with Prismate; the version-agnostic sample .aje reaches the full
  lifecycle (PREINIT/INIT/SETUP/COMPLETE) and resource injection works on the
  1.20 segment. (Run under JDK 21: MC 1.20+ is forward-compatible with newer
  JVMs; see the Java-floor evidence below.)
- [VERIFIED] Java runtime floor empirically captured: a release-17 probe
  class loads on JRE 17.0.19, but loading com.aprism.api.AprismPhase from the
  Prismate shaded jar throws UnsupportedClassVersionError: class file version
  65.0, this version only recognizes up to 61.0. The embedded Aprism API is
  Java 21 bytecode, so the shaded artifact's effective runtime floor is Java
  21 regardless of Prismate's own bytecode target. fabric.mod.json therefore
  keeps java: ">=21" (Fabric will never install Prismate into a Java-17
  profile). A true Java-17 floor for the legacy segment is gated on upstream
  Aprism lowering its API baseline.
- [DONE] Remap boundary documented: docs 01 §13 rewritten for the v26.1 line —
  issue 5 now records the Intermediary remap boundary (Prismate loads packs
  as-is; remapping obfuscated 1.20/1.21 classes is the Aprism agent's job,
  mirroring Aprism's VersionLineRegistry REMAPPED profile); issue 6 records
  the Java-21 runtime floor with the empirical evidence above. The obsolete
  "MC pinned to 26.2" issue was replaced. Milestone M7 (version-line
  expansion) added to docs 01 §12.
- [FIXED] Alpha.3 regression initially reported the NeoForge harness FAIL:
  the NeoForge shadowJar had not been rebuilt for the new version (only the
  v26.1-Alpha.1-N jar existed). Rebuilt :neoforge:shadowJar and the harness
  passed; lesson recorded: every version bump must rebuild BOTH loader jars
  before running run_all_regression.sh.
- [VERIFIED] REGRESSION PASS on v26.1-Alpha.3: Fabric lifecycle+mixin+
  resources, NeoForge lifecycle+report, Fabric multi-mod soak all green; plus
  the 1.20.1 and 1.21.10 real-game smokes on the Alpha.3 artifact.
- [DONE] Version bumped to v26.1-Alpha.3.

### Session 2026-08-10 (v26.1-Alpha.4-Phase0) - NeoForge line decision + known-issue attempt
- [DECISION] NeoForge version line = 26.x only for v26.1 (docs 01 §13 issue
  5): the bridge is written against FML 11 (FMLLoader.getCurrent(),
  constructor-injected mod-scoped event bus). NeoForge 1.20.2-1.21.x runs
  FML 2-4 (different API); the 1.20/1.21 segments are covered by the Fabric
  bridge instead. neoforge.mods.toml keeps versionRange = "[26.2,)".
- [ATTEMPTED + RESOLVED-at-classloader-level] NeoForge resource injection
  (docs 01 §13 issue 2): the extracted resources/ directory is now ALSO
  registered with the Prismate-managed mod classloader
  (PrismateModClassLoader.addResourceDir + EmbeddedRuntime.injectClasspath),
  so mods loaded through it serve their own resource entries. VERIFIED in a
  real NeoForge 26.2 game: the ressmoke probe now prints
  "[RESSMOKE] resource visible=true" (was false). What remains open is only
  host-level resource-manager integration (visibility to the host's own
  resource reload / other mods) — FML 11 exposes no runtime
  resource-injection API, so that part stays deferred.
- [ATTEMPTED + root cause captured] NeoForge Mixin passthrough (docs 01 §13
  issue 1): unwrapped the previously-swallowed InvocationTargetException in a
  live game — the real root cause is IllegalArgumentException: "The specified
  resource '<config>' was invalid or could not be read": the Mixin service
  resolves configs through FML 11's JPMS ModuleClassLoader/module layer,
  which has no runtime jar-injection seam reachable by Prismate's reflective
  bridge. Confirmed a genuine architectural limitation (not a fixable gap);
  the failure is now reported with the root cause + an explicit boundary
  note. Fabric Mixin passthrough is unaffected.
- [DONE] Tests: ClassloaderResourceInjectionTest (2: addResourceDir resolves
  resource entries through the loader; EmbeddedRuntime registers pack
  resources/ into the managed loader). Headless suite 81 tests green.
- [DONE] Upstream sync discipline exercised twice this session: Aprism moved
  a7c9b9c -> 1a27f6c9 (feat(aep) v26.1-Alpha.9: priority ordering, dependency
  validation, onPostInitialize/onShutdown extension hooks — additive to
  aprism-api/loader-core). Prismate stayed green against both; the sync pin
  was advanced to 1a27f6c9.
- [VERIFIED] REGRESSION PASS on v26.1-Alpha.4: Fabric lifecycle+mixin+
  resources, NeoForge lifecycle+report, Fabric multi-mod soak all green
  (soak boot baseline 12262 ms).
- [DONE] Version bumped to v26.1-Alpha.4.
