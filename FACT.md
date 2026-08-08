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

### Open items (tracked to resolution)

- **OPEN-1 (no `aprism` env id in Aprism core):** interim — Prismate injects
  `aprism=<embedded Aprism version>` into the dependency environment itself.
  Upstream fix targeted at v26.0-Alpha.6 (Aprism core adds the id).
- **OPEN-2 (no `forge` env id):** low priority; only needed if Forge `.aje`
  dependency declarations appear. Deferred with the Forge module.
- **OPEN-3 (agent detection mechanism):** RESOLVED IN PRISMATE: the Aprism
  agent (verified against v26.0-Alpha.8) sets NO system property, so Prismate
  detects the agent by probing the system classloader for an initialized
  `com.aprism.loader.AprismRuntime` (singleton with non-null classloader), and
  additionally honors an `aprism.agent.active=true` system property as a
  forward-compatible hook. Upstreaming the property into the Aprism agent is
  targeted at v26.0-Alpha.6.
- **OPEN-4 (Fabric Knot injection API):** the Fabric bridge resolves the
  add-jar entry point reflectively (`FabricLauncherBase#getLauncher().addURL`,
  Knot fallback) at runtime against the pinned Fabric Loader. Real-game
  verification is the v26.0-Alpha.2 exit criterion. Until verified, the
  documented URLClassLoader fallback keeps the pipeline functional.
- **OPEN-5 (NeoForge classpath extension):** no official runtime jar-injection
  API; Alpha.1 ships the documented URLClassLoader fallback with a clear
  warning. Real NeoForge integration (module layer / mod file participation)
  is the v26.0-Alpha.3 milestone.

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
