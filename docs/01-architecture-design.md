# AprismPrismate Architecture Design

> Document 1 of 2 | AprismPrismate Documentation Set
> Version: v26.6-Alpha.5 | Status: Implemented (official; status publishing live-verified both loaders)
> Author: BlockConnect@StarsailsClover
> Canonical language: English (Chinese summary in README.zh-CN.md)

## 1. Executive Summary

AprismPrismate is a loader-side bridge mod. It runs INSIDE Fabric, NeoForge,
and Forge and lets those loaders load Aprism-native `.aje` packs. It is the
mirror image of [AprismRefract](https://github.com/NDBlockConnect/AprismRefract):
Refract brings other loaders INTO Aprism; Prismate brings Aprism INTO other
loaders. Together they form the two-way compatibility bridge of the Aprism
ecosystem.

Prismate is implemented by embedding the Aprism loader-core + manifest runtime
as a relocated library, driving the Aprism lifecycle from the host loader's
lifecycle hooks, and injecting the extracted mod jars + resources into the host
loader's classloader so that Aprism-native mods become first-class citizens on
Fabric / NeoForge / Forge.

This document is the normative design reference. Another engineering assistant
will implement from it. Where a decision is deferred or needs confirmation, it
is marked `OPEN:` or `DECISION:` inline and collected in Section 11.

## 2. Positioning and Relationship to the Aprism Family

| Project | Direction | Runs inside | Loads | Artifact |
|---|---|---|---|---|
| **Aprism** (core) | native | the JVM (javaagent) | `.aje`/`.abe`/`.aep` | `Aprism-v<ver>-JE-<mc>.jar` |
| **AprismRefract** | other loaders INTO Aprism | Aprism (as `.aep` extension) | Fabric/NeoForge/Forge/Quilt/LiteLoader mods | `<Loader>-Support-A[..]-<ver>.aep` |
| **AprismPrismate** | Aprism INTO other loaders | Fabric / NeoForge / Forge | Aprism-native `.aje` packs | `AprismPrismate-v<ver>-<loaderkey>-<mc>.jar` |

Prismate is NOT a replacement for the Aprism agent. It exists so that users who
already run Fabric/NeoForge/Forge (and do not want to switch to the Aprism
agent) can still consume Aprism-native `.aje` mods. It is mutually exclusive
with the Aprism javaagent in the same instance (see Section 9).

## 3. Design Principles

1. **Faithful embedded runtime.** Prismate embeds the Aprism loader-core and
   manifest modules and drives the same lifecycle order (`PREINIT -> INIT ->
   SETUP -> COMPLETE`, then side `CLIENT`/`SERVER`). Behavior for an `.aje`
   mod must be identical whether loaded by the Aprism agent or by Prismate.
2. **Delegate to the host loader.** Prismate does not create a parallel
   classloader hierarchy or re-invent discovery. It injects extracted jars and
   resources into the host loader's classloader and lifecycle, so mods are
   visible to the host loader's own systems (resource loading, mixin, etc.).
3. **No Aprism-core modification for the bridge.** Prismate consumes Aprism's
   existing classes. If Prismate reveals a gap in Aprism's public surface, the
   fix goes into Aprism core (tracked as an OPEN item), not into a Prismate
   fork.
4. **One codebase, three loaders.** Shared logic in a `common/` source set;
   per-loader entrypoints in `fabric/`, `neoforge/`, `forge/` sets (MultiLoader
   template, per Aprism doc 08 Section 3).
5. **Fail visible, not silent.** A malformed `.aje`, a missing dependency, or a
   version mismatch must surface as a clear load error naming the pack and the
   reason, never a silent skip.

## 4. Architecture

```
+------------------------------------------------------------------+
| Minecraft JVM (client or dedicated server)                        |
|                                                                   |
|  Host loader (Fabric Knot / NeoForge / Forge)                     |
|   +-- discovers Prismate as a normal host-loader mod              |
|   +-- calls Prismate's host entrypoint                            |
|        |                                                          |
|        v                                                          |
|   PrismatePrismate (loader bridge mod)                            |
|    +-- embedded Aprism runtime (relocated)                        |
|    |    +-- AprismManifestParser / DependencyResolver             |
|    |    +-- AprismRuntime-equivalent (library mode)               |
|    |    +-- AprismEventBus / AprismRegistry                       |
|    +-- .aje discovery:  <gameDir>/mods/**/*.aje                  |
|    +-- extract <modid>.jar + resources/ + mixins/ per .aje        |
|    +-- inject jars + resources into host classloader              |
|    +-- drive lifecycle PREINIT->INIT->SETUP->COMPLETE->side      |
|                                                                   |
|  Aprism-native mods (loaded .aje)                                 |
|   +-- implement IAprismMod (com.aprism.api)                       |
+------------------------------------------------------------------+
```

### 4.1 Component breakdown

| Component | Responsibility | Source set |
|---|---|---|
| `PrismateBootstrap` | Detect host loader + side, detect agent conflict, boot embedded runtime | `common` |
| `AjeDiscovery` | Scan `mods/` (and configured dirs) for `.aje`, parse manifests | `common` |
| `AjeExtractor` | Unpack `<modid>.jar`, `resources/`, `mixins/`, `lib/` from each `.aje` | `common` |
| `EmbeddedRuntime` | Dependency resolution, lifecycle dispatch, event bus, registry | `common` |
| `HostClassloaderBridge` | Inject extracted jars/resources into host classloader (per loader) | per loader |
| `FabricEntrypoint` | Fabric `ModInitializer`/`ClientModInitializer`/`DedicatedServerModInitializer` | `fabric` |
| `NeoForgeEntrypoint` | NeoForge `@Mod` + lifecycle events | `neoforge` |
| `ForgeEntrypoint` | Forge `@Mod` + lifecycle events | `forge` |
| `LifecycleMapper` | Map Aprism phases to host-loader lifecycle hooks | per loader |

## 5. The .aje Contract Prismate Honors

Prismate MUST conform to the exact `.aje` contract from Aprism doc 07. Summary
(normative, do not deviate):

- ZIP root contains `aprism.manifest.json` (REQUIRED) + exactly one main mod
  jar `<modid>.jar` (REQUIRED). No per-loader subdirectories.
- Optional: `icon.png`, `resources/` (assets/data/pack.mcmeta), `mixins/`
  (mixin configs), `lib/` (JiJ-style bundled dependency jars).
- Manifest fields (record `AprismManifest`): `schemaVersion, id, version,
  displayName, description, environment, entrypoints (main/client/server),
  mixins, depends, platforms, accessWidener, provides, custom`.
- Entrypoint invocation order is phase-strict: `PREINIT -> INIT -> SETUP ->
  COMPLETE`, then side `CLIENT` or `SERVER`. Only `onInitialize(AprismContext)`
  is required; the rest are no-op defaults.

Prismate reuses Aprism's `AprismManifestParser` and `DependencyResolver`
classes directly (relocated) rather than re-implementing them, so manifest
semantics cannot drift between Aprism and Prismate.

## 6. Load Pipeline (per .aje)

1. **Discover.** Scan `<gameDir>/mods/` recursively for `*.aje`. (Config may
   add extra directories; default is `mods/` only.)
2. **Parse.** Open the ZIP, read `aprism.manifest.json`, parse with the embedded
   `AprismManifestParser`. Reject packs whose `environment` field is not
   compatible with JE (Prismate is JE-only; BE packs are out of scope).
3. **Validate environment + dependencies.** Resolve `depends` against the
   environment map (Section 7) and against other discovered `.aje` mods
   (`provides` supported). On missing/unsatisfiable dependency, record a load
   failure for that pack (do not abort the whole load; report at end).
4. **Extract.** Unpack `<modid>.jar` (the main mod code), `resources/`,
   `mixins/`, and any `lib/*.jar` into a per-mod working directory:
   `<gameDir>/prismate/work/<modid>/`.
5. **Inject classpath.** Add each extracted `.jar` (main mod jar + `lib/`
   jars) and the `resources/` directory to the host loader's classloader via
   the per-loader `HostClassloaderBridge`. Register mixin configs from
   `mixins/` with the host loader's Mixin environment.
6. **Instantiate + drive lifecycle.** For each mod, load each class named in
   `entrypoints.<phase>` through the host classloader, construct it, and invoke
   the corresponding `IAprismMod` phase method with a per-mod `AprismContext`
   (event bus, registry, mod-scoped logger). Dispatch in the strict phase
   order.

## 7. Environment IDs and Dependency Resolution

Prismate supplies the same environment IDs the Aprism native runtime provides
(from `AprismRuntime`), so existing `.aje` dependency declarations resolve
without modification:

| Environment ID | Value supplied by Prismate |
|---|---|
| `minecraft` | running Minecraft version (e.g. `26.2`) |
| `fabricloader` | running Fabric loader version (Fabric build only) |
| `neoforge` | running NeoForge version (NeoForge build only) |
| `java` | `Runtime.version().feature()` |

`OPEN-1`: The Aprism native environment map does NOT currently provide an
`aprism` ID, so an `.aje` that declares `depends: {"aprism": ">=26.0"}` cannot
be resolved by either Aprism or Prismate. Proposed fix: Aprism core should add
`environment.put("aprism", aprismVersion)`. Until that lands, Prismate should
inject `aprism` into the environment map itself (with the embedded Aprism
version) so such mods resolve. Track this against Aprism core.

`OPEN-2`: Forge (classic) has no `forge` environment ID in Aprism's map. If
Forge support for `.aje` mods that declare `depends: {"forge": ...}` is wanted,
add a `forge` ID. Low priority; Forge is legacy.

## 8. Lifecycle Mapping to Host Loaders

Aprism dispatch order (from `AprismPhase`): `PREINIT -> INIT -> SETUP ->
COMPLETE`, then side `CLIENT` or `SERVER`. Mapping per host loader:

### 8.1 Fabric
| Aprism phase | Fabric hook |
|---|---|
| `PREINIT` | Prismate's own `ModInitializer.onInitialize()` (before other Fabric mods) â€?parse + extract + classpath injection |
| `INIT` | end of Prismate's `ModInitializer.onInitialize()` |
| `SETUP` | end of Prismate's `ModInitializer.onInitialize()` (Aprism native dispatches SETUP in the same bootstrap) |
| side `CLIENT` | `ClientModInitializer.onInitializeClient()` |
| side `SERVER` | `DedicatedServerModInitializer.onInitializeServer()` |
| `COMPLETE` | Fabric `LifecycleEvents.GAME_READY` (or equivalent) |

### 8.2 NeoForge
| Aprism phase | NeoForge hook |
|---|---|
| `PREINIT` | Prismate `@Mod` constructor (parse + extract + classpath injection) |
| `INIT` | end of Prismate `@Mod` constructor |
| `SETUP` | end of Prismate `@Mod` constructor |
| side `CLIENT` | `FMLClientSetupEvent` |
| side `SERVER` | `FMLDedicatedServerSetupEvent` |
| `COMPLETE` | a late lifecycle event (e.g. `ModLifecycleEvent` / game start) |

### 8.3 Forge (classic, legacy)
Mirror the NeoForge mapping using Forge's `@Mod` + `FMLCommonSetupEvent` /
`FMLClientSetupEvent` / `FMLDedicatedServerSetupEvent`. `DECISION-1`: confirm
whether Forge (legacy) is in scope for Alpha 1; if deferred, ship Fabric +
NeoForge first and stub Forge.

Note on ordering: because Prismate performs classpath injection during its OWN
early entrypoint, Aprism mods become loadable before other host mods run. Mods
that need another host mod present at INIT time still work because all jars are
on the classloader by then; ordering BETWEEN `.aje` mods follows Aprism's
dependency-resolved order.

## 9. Classloader and Classloading Strategy

### 9.1 Class identity requirement
Aprism-native mods implement `com.aprism.api.IAprismMod`. For `instanceof` and
method dispatch to work, the `com.aprism.api` classes the mods see MUST be the
same classes Prismate uses. Therefore:

- Prismate **relocates the embedded Aprism runtime** (loader-core + manifest)
  to `com.aprism.prismate.internal.**` to avoid colliding with the host loader
  and with any Aprism agent.
- Prismate **does NOT relocate `com.aprism.api`**. The API package stays at its
  canonical name so mod jars (which reference `com.aprism.api`) bind to the API
  classes Prismate ships.

### 9.2 Mutual exclusion with the Aprism agent
If the Aprism javaagent is ALSO active in the same JVM, there would be TWO
definitions of `com.aprism.api` (agent's vs Prismate's) and two lifecycles,
causing ClassCastException / double-init. Prismate MUST detect the Aprism agent
(e.g. a known agent marker class on the system classloader or a system property
the agent sets) and refuse to boot, logging a clear message telling the user to
choose one. `OPEN-3`: decide the exact detection mechanism (agent-set system
property is preferred; needs a one-line addition in Aprism agent to set it).

### 9.3 Jar/resource injection
Each loader exposes a way to add URLs/jars to its classloader at runtime.
Prismate uses the loader-specific bridge:

- **Fabric**: inject through Fabric Loader's Knot classloader delegate
  (`net.fabricmc.loader.impl.launch.knot.Knot#addUrl` / the classloader
  delegate's add-jar API). `OPEN-4`: confirm the exact stable API against the
  Fabric Loader version pinned for the target Minecraft version; Fabric's
  internal delegate API has shifted across versions.
- **NeoForge / Forge**: inject through the loader's mod classloader / early
  classpath extension mechanism (NeoForge's `EarlyLoadingService`/classloader
  extension, or Forge's `FMLLoader` classpath hook). `OPEN-5`: same confirmation
  needed for NeoForge/Forge.

Fallback if the loader's injection API is unavailable: Prismate loads the
extracted jars through a child `URLClassLoader` parented to the host
classloader and drives lifecycle itself. This works but makes the mod's classes
invisible to host systems that scan the host classloader â€?use only as a
degraded path and log a warning.

## 10. Build, Versioning, and Distribution

### 10.1 Project structure (MultiLoader template, per Aprism doc 08)
```
AprismPrismate/
+-- settings.gradle / build.gradle / gradle.properties / gradle/libs.versions.toml
+-- common/        shared: discovery, extraction, embedded runtime, lifecycle mapper
+-- fabric/        Fabric entrypoints + fabric.mod.json + Fabric classloader bridge
+-- neoforge/      NeoForge entrypoints + @Mod + NeoForge classloader bridge
+-- forge/         Forge entrypoints + @Mod + Forge classloader bridge
+-- docs/          this documentation set
```
`common` depends on Aprism API + Aprism loader-core + Aprism manifest (all
relocated at shading time) and on vanilla Minecraft (deobfuscated per loader).
Each loader set depends on `common` + its loader toolchain (Architectury Loom /
Loom for Fabric; NeoGradle/ModDevGradle for NeoForge).

### 10.2 Toolchain
Modern profile (target MC 26.x): JDK 25, Gradle 9.x, Architectury Loom (per
Aprism doc 08 Section 2). Legacy profile not required for Alpha 1.

### 10.3 Versioning
Follow the Aprism family scheme exactly: `v<Year>.<minor>[-Alpha.<n>]`. Prismate
releases on the same minor line as the Aprism core it embeds so the embedded
runtime version is unambiguous. Example: `v26.0-Alpha.1`.

### 10.4 Artifact naming and signing
Per-loader artifacts, mirroring Refract's scheme:
`AprismPrismate-v26.0-Alpha.1-Fa-26.2.jar` (Fabric), `-N-` (NeoForge), `-Fo-`
(Forge). All artifacts signed with cosign keyless + SHA-256 checksums, released
as GitHub Pre-Releases (Alpha) exactly like Aprism and Refract.

### 10.5 Installation
Install Prismate into the HOST loader's mod folder (Fabric `mods/`, NeoForge
`mods/`, Forge `mods/`). Then place Aprism `.aje` packs into that instance's
`mods/` directory â€?Prismate discovers them there. Do NOT install the Aprism
javaagent in the same instance (mutual exclusion, Section 9.2).

## 11. Open Items and Decisions (track to resolution)

Status as of v26.0-Alpha.9 (release candidate). OPEN-1 and OPEN-3 were closed
upstream in Aprism core and adopted by Prismate (Alpha.6); OPEN-4 and OPEN-5
were resolved against the pinned loader versions with real-game verification
(Alpha.2 / Alpha.3); OPEN-2 and DECISION-1 (Forge) are deferred post-1.0.

| # | Item | Type | Resolution |
|---|---|---|---|
| OPEN-1 | No `aprism` env ID in Aprism's dependency environment map | Aprism core gap | CLOSED upstream (Aprism `loadMods` supplies the normalized Aprism version under `aprism`); Prismate self-injection retained as fallback for older embedded cores (Alpha.6) |
| OPEN-2 | No `forge` env ID | Aprism core gap | DEFERRED post-1.0 with the Forge module (DECISION-1) |
| OPEN-3 | Agent-vs-Prismate detection mechanism | needs Aprism cooperation | CLOSED upstream (Aprism agent sets `aprism.agent.active=true`); Prismate checks it first, system-classloader probe retained as fallback (Alpha.6) |
| OPEN-4 | Fabric Knot classloader injection API stability | Fabric-version dependent | RESOLVED for Fabric Loader 0.16.14: `FabricLauncherBase.getLauncher().addToClassPath(Path, ...)`, verified live in-game (Alpha.2) |
| OPEN-5 | NeoForge/Forge classpath extension API | loader-version dependent | RESOLVED for NeoForge 26.2.0.53-beta: degrades to the Prismate-managed classloader (documented path); host Mixin passthrough + resource injection on NeoForge remain known limitations (Alpha.3) |
| DECISION-1 | Is Forge (legacy) in Alpha 1 scope? | scope | RE-CONFIRMED (Alpha.5): Forge (classic) stays OUT of the v26.0 Alpha line, defers post-1.0; `forge/` is a visible stub |
| DECISION-2 | Where `.aje` packs are discovered | UX | RESOLVED: default `<gameDir>/mods/` (recursive); extra dirs via `<gameDir>/prismate/prismate.json` `extraAjeDirs` |
| DECISION-3 | Whether to support `lib/` JiJ-style bundled deps in Alpha 1 | scope | RESOLVED: supported from Alpha.1; `lib/*.jar` extracted and injected like the main jar |

## 12. Milestones (as shipped)

| Milestone | Scope | Exit criteria | Shipped |
|---|---|---|---|
| M1 | Project scaffold + common runtime + Fabric entrypoint | Prismate boots in Fabric, discovers a sample `.aje`, drives lifecycle, mod's `onInitialize` runs | v26.0-Alpha.1 (headless) / v26.0-Alpha.2 (real game) |
| M2 | Fabric classloader injection + resources/mixins | A real `.aje` with resources + a mixin loads and renders content | v26.0-Alpha.2 |
| M3 | NeoForge entrypoint + bridge | Same sample `.aje` loads on NeoForge | v26.0-Alpha.3 |
| M4 | Forge entrypoint (if in scope) | sample loads on Forge | Deferred post-1.0 (DECISION-1 re-confirmed at v26.0-Alpha.5); visible stub refuses to boot |
| M5 | Mutual-exclusion guard + error reporting polish | agent-present case refuses cleanly; malformed packs report clearly | v26.0-Alpha.1 (guard) / v26.0-Alpha.4 (report files) / v26.0-Alpha.6 (upstream alignment) |
| M6 | Signing + release pipeline (mirror Aprism/Refract) | cosign-signed Pre-Release artifacts published | v26.0-Alpha.1 (pipelines) / verified in place at v26.0-Alpha.7 |
| M7 | JE version-line expansion 1.20..26.2 + upstream sync discipline | PrismateVersionLine + boot gate + drift check; real Fabric landings on the 1.20 and 1.21 segments; multi-version regression matrix | v26.1-Alpha.1 (foundation/sync) / v26.1-Alpha.2 (real 1.21.x) / v26.1-Alpha.3 (real 1.20.x + remap boundary) / v26.1-Alpha.4 (NeoForge decision + resource/Mixin) / v26.1-Alpha.5 (multi-version matrix + per-segment baselines) |

## 13. Known Issues (v26.1 line)

1. **NeoForge host Mixin passthrough**: `.aje` mixin configs cannot be
   registered with NeoForge's already-initialized Mixin environment. The
   v26.1-Alpha.4 attempt captured the real root cause in a live game:
   `Mixins.addConfiguration` throws `IllegalArgumentException: The specified
   resource '<config>' was invalid or could not be read`, because the Mixin
   service resolves configs through FML 11's JPMS `ModuleClassLoader` /
   module layer, which has no runtime jar-injection seam reachable by
   Prismate's reflective bridge. `.aje` mods that rely on Mixin work on
   Fabric but their mixins are not applied on NeoForge; the mod's lifecycle
   still runs. Tracked with OPEN-5.
2. **NeoForge host resource-manager integration**: the extracted
   `resources/` dir is registered with the Prismate-managed classloader
   (v26.1-Alpha.4), so mods loaded through it serve their own resource
   entries (`getResource`/`getResourceAsStream` resolve; verified real-game
   with the ressmoke probe returning `visible=true`). What NeoForge still
   lacks is host-level resource-manager integration â€?visibility to the
   host's own resource reload / other mods â€?because FML 11 exposes no
   runtime resource-injection API. Tracked with OPEN-5.
3. **Forge (classic) not supported**: the `forge/` module is a visible stub
   that refuses to boot with a named error; Forge support defers post-1.0
   (DECISION-1 re-confirmed at v26.0-Alpha.5).
4. **Prismate-side access widener boundary**: the widener applies to classes
   resolved through the Prismate-managed classloader; when a host loader's
   own injection succeeds, host-loaded mod classes rely on the host's own
   widener mechanism (Fabric applies `fabric.mod.json` accessWidener for mods
   it discovered itself). Recorded at v26.0-Alpha.4 per docs Â§3.3.
5. **NeoForge version line = 26.x only (v26.1 decision)**: the NeoForge
   bridge is written against FML 11 (`FMLLoader.getCurrent()`, constructor
   injection of the mod-scoped event bus). NeoForge for 1.20.2-1.21.x runs
   FML 2-4 â€?a different API surface â€?so the NeoForge artifact is
   intentionally pinned to the 26.x line (neoforge.mods.toml
   `versionRange = "[26.2,)"`); backporting to FML 2-4 is out of scope for
   v26.1. The Fabric bridge covers the 1.20/1.21 segments instead.
6. **Intermediary remap boundary (1.20/1.21 segments)**: Prismate loads `.aje`
   packs as-is; it does NOT remap obfuscated classes. On the REMAPPED JE
   segments (1.20.x / 1.21.x, obfuscated) a pack's compiled classes must
   already match the running game, and Intermediary remapping remains the
   Aprism agent's job (mirrors Aprism's VersionLineRegistry REMAPPED
   profile). Real-game landings on 1.20.1 and 1.21.10 use version-agnostic
   sample packs (Aprism API + classloader only); a Mixin targeting
   `net.minecraft.client.Minecraft` is deliberately excluded there because
   that class is obfuscated.
7. **Java runtime floor = 21 (not the 1.20/1.21 official Java 17)**: the
   embedded `com.aprism.api` in the shaded artifact is Java 21 bytecode
   (upstream Aprism compiles at `--release 21`). Empirically verified
   (v26.1-Alpha.3): a release-17 probe class loads on JRE 17, but loading
   `com.aprism.api.AprismPhase` from the Prismate jar throws
   `UnsupportedClassVersionError: class file version 65.0, this version only
   recognizes up to 61.0`. Consequences: (a) fabric.mod.json keeps
   `java: ">=21"` so Fabric never installs Prismate into a Java-17 profile
   where the embedded API could not load; (b) 1.20.x / 1.21.x real-game
   landings run under a Java 21 runtime (MC 1.20+ is forward-compatible with
   newer JVMs); (c) a true Java-17 floor for the legacy segment is gated on
   upstream Aprism lowering its API baseline.

End of document.


