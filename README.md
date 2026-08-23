# AprismPrismate

Aprism | AprismPrismate is a Minecraft: Java Edition mod that runs inside Fabric and NeoForge,
enabling these loaders to load native Aprism `*.aje` mods. (The `forge/` module is a visible stub;
classic Forge is deferred post-1.0.)

> Author: BlockConnect@StarsailsClover | License: Apache�?.0
> Companion repository for [Aprism](https://github.com/NDBlockConnect/Aprism).

## What Is This

The native Aprism loader loads `.aje` files directly via a javaagent. However, if users are already using Fabric / NeoForge
and do not wish to switch to the Aprism agent, Prismate acts as the bridge: it runs as an ordinary mod for the host loader,
embeds the (relocated) Aprism runtime within itself, scans the `mods/` directory for `.aje` packages,
extracts contained mod JARs / resources / mixins, injects them into the host loader’s classloader, and drives Aprism’s full lifecycle
(`PREINIT -> INIT -> SETUP -> COMPLETE`, followed by `CLIENT` / `SERVER`‑side phases).

It is the mirror counterpart of [AprismRefract](https://github.com/NDBlockConnect/AprismRefract):
Refract brings other loaders into Aprism; Prismate brings Aprism into other loaders. Together they enable bidirectional ecosystem interoperability.

## Documentation

- Authoritative English source: [docs/01‑architecture‑design.md](docs/01‑architecture‑design.md) (Architecture Design)
  and [docs/02‑developer‑guide.md](docs/02‑developer‑guide.md) (Developer Implementation Guide)
- This Chinese summary is maintained in sync with the English source documents.

## Core Design Highlights

1. **Faithful Runtime Embedding**: Directly reuses Aprism’s `AprismManifestParser` / `DependencyResolver`,
relocated under `com.aprism.prismate.internal`, while **not relocating** `com.aprism.api` to ensure consistent API classes bound by mods.
2. **Delegate to Host Loader**: Does not build custom class‑loader hierarchies. Extracted JARs and resources are injected into the host loader’s classloader and lifecycle.
3. **One Codebase, Two Loaders**: Shared logic resides under `common/`, with separate entrypoints for `fabric/` and `neoforge/` (the `forge/` module is a refusal stub).
4. **Mutually Exclusive with Aprism Agent**: Prismate and the Aprism agent cannot coexist in one instance. Prismate aborts startup if it detects the agent.
5. **Visible Failures**: Malformed packages, missing dependencies, and version mismatches must produce human‑readable named errors; failures are never silently ignored.
6. **Upstream Sync Discipline**: The JE version line (`PrismateVersionLine`) and the bound Aprism mod API are guarded by consistency tests that fail loudly if the embedded Aprism core drifts under Prismate (v26.1‑Alpha.1 / Alpha.6; re-verified at v26.2‑Alpha.3).

## Lifecycle Mapping

| Aprism Phase | Fabric | NeoForge |
|---|---|---|
| PREINIT/INIT/SETUP | Early execution within its own ModInitializer | @Mod constructor |
| CLIENT | ClientModInitializer | FMLClientSetupEvent |
| SERVER | DedicatedServerModInitializer | FMLDedicatedServerSetupEvent |
| COMPLETE | Late‑phase events such as GAME_READY | Late‑lifecycle events |

## Versions & Releases

- Versioning follows the Aprism family convention: `v<year>.<minor>[-Alpha.<n>]`, sharing the same minor‑version line as the embedded Aprism core.
- Artifact naming examples: `AprismPrismate‑v26.3‑Fa�?6.2.jar` (Fabric), `-N‑` (NeoForge).
- Cosign keyless signing + SHA�?56 checksums + CycloneDX SBOM + GitHub Pre‑Releases, consistent with Aprism / Refract workflows.

## Supported Minecraft Versions (JE Line, v26.4+)

Prismate covers the JE line `1.20 .. 26.2` (mirrors Aprism's `VersionLineRegistry`), with real‑game verified landings:

| Segment | Loader support | Verified in real game | Notes |
|---|---|---|---|
| 26.x | Fabric + NeoForge | lifecycle + mixin + resources + soak | unobfuscated, no remap |
| 1.21.x | Fabric | lifecycle + resources | obfuscated; Intermediary remap is the Aprism agent's job |
| 1.20.x | Fabric | lifecycle + resources | obfuscated; same remap boundary |

All four host-integration surfaces (Fabric 26.2, NeoForge 26.2, Fabric 1.21.10,
Fabric 1.20.1) are additionally verified live through MDK/MDL-launched real
instances (`mdl launch <instance> --detach`): correct runtime MC version
reporting, full `PREINIT -> INIT -> SETUP -> COMPLETE` dispatch (COMPLETE via
FMLLoadCompleteEvent on NeoForge), and resource injection on every segment
(v26.4 line; matrix re-proven per line since).

## Machine-Readable Status (v26.6+)

Prismate publishes `<gameDir>/aprism-status.json` after the load report and on
shutdown — the SAME `aprism.status/v1` schema and file name as the Aprism agent
(the two are mutually exclusive in one instance, so exactly one publisher exists
per game root). External tooling such as MDL diagnose reads one file regardless
of which loader published it:

```json
{
  "schemaVersion": "aprism.status/v1",
  "publisher": "prismate",
  "phase": "LOADED",
  "okCount": 8,
  "failureCount": 0,
  "units": [ { "kind": "extraction", "id": "examplemod", "state": "OK", "...": "..." } ]
}
```

Refused boots publish their outcome (`AGENT_CONFLICT`, `DISABLED`,
`VERSION_UNSUPPORTED`, `BOOT_FAILED`) as the phase, so a failed boot is
machine-diagnosable without parsing game logs.

- **NeoForge is 26.x‑only** for v26.6: the bridge targets FML 11; NeoForge 1.20.2�?.21.x runs FML 2�? (a different API) and is out of scope. The Fabric bridge covers those segments.
- **Forge (classic) is a stub** that refuses to boot with a named error (deferred post�?.0).
- **Java runtime floor is 21**, not the 1.20/1.21 official Java 17: the embedded Aprism API is Java 21 bytecode (upstream Aprism compiles at `--release 21`). `fabric.mod.json` keeps `java: ">=21"` so the loader never installs Prismate into a Java�?7 profile. MC 1.20+ is forward‑compatible with newer JVMs, so 1.20.x/1.21.x run under Java 21.
- Packs are loaded as‑is; Prismate does not remap obfuscated classes (docs/01 §13, issues 6�?).

## Installation

Place Prismate into your host loader’s `mods/` folder, then drop Aprism `.aje` mods into the same instance’s `mods/`.
**Do not** install the Aprism javaagent in the same instance (they are mutually exclusive).

