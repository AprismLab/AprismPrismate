# AprismPrismate Developer Guide

> Document 2 of 2 | AprismPrismate Documentation Set
> Version: v26.18-Alpha.1 | Status: Implemented (official)
> Author: BlockConnect@StarsailsClover
> Canonical language: English

This guide is written for the engineering assistant implementing
AprismPrismate. Read `01-architecture-design.md` first; this document turns it
into concrete, ordered implementation steps with acceptance criteria.

## 1. Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| JDK | Java 25 (Temurin) | Modern profile compile runtime (Aprism doc 08 Sec 2) |
| Gradle | 9.x | Build orchestration |
| Architectury Loom | latest | Fabric source set (Modern) |
| NeoGradle or ModDevGradle | latest | NeoForge source set |
| cosign | 2.2+ | artifact signing |

You will need the Aprism repository checked out as a sibling: Prismate consumes
`aprism-api`, `aprism-manifest`, and `aprism-loader-core` either via
`includeBuild("../Aprism")` (preferred during co-development) or published
`mavenLocal()` artifacts.

## 2. Module layout

Create these Gradle subprojects:

```
common/     -> java-library. Depends: Aprism API/manifest/loader-core (relocated
               at shading), Minecraft (deobf per loader). Contains:
               PrismateBootstrap, AjeDiscovery, AjeExtractor, EmbeddedRuntime,
               LifecycleMapper, HostBridge (interface).
fabric/     -> depends: common + Fabric Loader (loom). Contains: FabricEntrypoint,
               FabricClassloaderBridge, fabric.mod.json.
neoforge/   -> depends: common + NeoForge. Contains: NeoForgeEntrypoint (@Mod),
               NeoForgeClassloaderBridge.
forge/      -> visible refusal stub (DECISION-1 re-confirmed; classic Forge
               deferred post-1.0). Refuses to boot with a named error.
```

Shading rule (critical): relocate `com.aprism.loader` and `com.aprism.manifest`
-> `com.aprism.prismate.internal`; do NOT relocate `com.aprism.api`.

## 3. Core contracts (verbatim from Aprism)

These are exact; copy, do not paraphrase.

- `IAprismMod`: required `void onInitialize(AprismContext)`; optional no-op
  defaults `onPreInitialize`, `onSetup`, `onComplete`.
- `AprismContext`: `getMod()`, `getEventBus()`, `getRegistry()`, `getLogger()`.
- `AprismPhase` order: `PREINIT, INIT, SETUP, COMPLETE`, then side
  `CLIENT`/`SERVER`.
- Manifest record fields: `schemaVersion, id, version, displayName, description,
  environment, entrypoints, mixins, depends, platforms, accessWidener, provides,
  custom`.
- Environment IDs Prismate must supply: `minecraft`, `fabricloader` (Fabric
  build), `neoforge` (NeoForge build), `java`, plus the loader-specific
  `forge` id for the Forge stub. Also inject `aprism` (embedded runtime
  version): Aprism core now supplies this upstream (OPEN-1 closed, v26.0),
  but Prismate keeps the self-injection as a fallback for embedded cores that
  predate the upstream fix. Both paths use the same normalization.

## 4. Implementation steps (ordered)

Do these in order; each has a concrete acceptance test.

### Step 1 â€?Scaffold
Set up `settings.gradle` with the three subprojects and a version catalog. Wire
Aprism modules as a composite build.
Accepts: `./gradlew build` compiles empty modules.

### Step 2 â€?Manifest parsing reuse
In `common`, call Aprism's `AprismManifestParser` to parse a sample
`aprism.manifest.json` from a test `.aje`.
Accepts: unit test parses a valid manifest and rejects a malformed one.

### Step 3 â€?AjeDiscovery + AjeExtractor
Scan a temp dir of `.aje` files; for each, extract `<modid>.jar`, `resources/`,
`mixins/`, `lib/` into `<workdir>/<modid>/`.
Accepts: unit test extracts a synthetic `.aje` and finds all expected entries.

### Step 4 â€?EmbeddedRuntime
Implement dependency resolution using Aprism's `DependencyResolver` with the
environment map (Section 3) and lifecycle dispatch calling `IAprismMod` phase
methods on a sample mod class loaded from an extracted jar.
Accepts: unit test drives PREINIT->INIT->SETUP->COMPLETE on a sample mod and
records the calls in order.

### Step 5 â€?Fabric entrypoint + classloader bridge (M1/M2)
Implement `FabricEntrypoint` as a Fabric mod with an early entrypoint; in
`onInitialize` run discovery -> extraction -> classpath injection -> lifecycle.
Use Fabric's classloader add-jar API (confirm OPEN-4). Add `resources/` so the
host loads the mod's assets.
Accepts: launch a Fabric instance with Prismate + a sample `.aje`; the sample
mod's `onInitialize` runs and (with a resource) the resource is visible.

### Step 6 â€?Mixin passthrough
Register extracted `mixins/*.json` with the host loader's Mixin environment so
`.aje` mods that use Mixin actually patch.
Accepts: a sample `.aje` with a trivial mixin applies it in Fabric.

### Step 7 â€?NeoForge entrypoint + bridge (M3)
Mirror Step 5/6 for NeoForge using `@Mod` constructor + FML setup events.
Accepts: same sample `.aje` loads and initializes on NeoForge.

### Step 8 â€?Mutual exclusion guard (M5)
Detect the Aprism agent (system property, see OPEN-3) at bootstrap; if present,
log a clear error and skip all Prismate work.
Accepts: with agent property set, Prismate logs refusal and does not load mods.

### Step 9 â€?Error reporting polish
Collect per-pack load failures; at the end, log each failed pack + reason.
Accepts: a deliberately broken `.aje` produces a named, readable failure without
stopping other mods.

### Step 10 â€?Signing + release (M6)
Add cosign keyless signing + checksums + GitHub Pre-Release workflow, mirroring
the Aprism and AprismRefract pipelines. Bump Prismate version on the same minor
line as the embedded Aprism core.
Accepts: a signed, checksummed Pre-Release publishes per-loader artifacts.

## 5. Testing strategy

- Unit tests (common): manifest parse, discovery, extraction, dependency
  resolution, lifecycle dispatch order â€?all runnable headless.
- Integration tests (per loader): use the project's MDL launcher tooling (see
  the Aprism workspace) to create real Fabric/NeoForge instances, install
  Prismate + sample `.aje`, launch, and assert the mod initialized (via MDL
  `logs`/`game` commands).
- Negative tests: malformed manifest, missing dependency, agent-present refusal.

## 6. Risks to watch

- Loader classloader injection APIs (OPEN-4/OPEN-5) are loader-internal and may
  differ across versions; verify against the exact pinned loader version before
  committing to the injection path, and keep the `URLClassLoader` fallback
  (doc 01 Sec 9.3) ready.
- Relocating the Aprism runtime without relocating `com.aprism.api` is the most
  error-prone build step; add a build-time check that `com/aprism/api` remains
  unrelocated in the shaded jar.
- Ordering: Aprism mods must be on the classloader before host mods' INIT runs;
  perform injection in the EARLIEST host entrypoint available.

End of guide.











