# AprismPrismate

Aprism | AprismPrismate is a Minecraft: Java Edition mod that runs inside Fabric, NeoForge, and Forge,
enabling these loaders to load native Aprism `*.aje` mods.

> Author: BlockConnect@StarsailsClover | License: Apache‑2.0
> Companion repository for [Aprism](https://github.com/NDBlockConnect/Aprism).

## What Is This

The native Aprism loader loads `.aje` files directly via a javaagent. However, if users are already using Fabric / NeoForge / Forge
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
3. **One Codebase, Three Loaders**: Shared logic resides under `common/`, with separate entrypoints for `fabric/`, `neoforge/`, and `forge/` (MultiLoader template).
4. **Mutually Exclusive with Aprism Agent**: Prismate and the Aprism agent cannot coexist in one instance. Prismate aborts startup if it detects the agent.
5. **Visible Failures**: Malformed packages, missing dependencies, and version mismatches must produce human‑readable named errors; failures are never silently ignored.

## Lifecycle Mapping

| Aprism Phase | Fabric | NeoForge |
|---|---|---|
| PREINIT/INIT/SETUP | Early execution within its own ModInitializer | @Mod constructor |
| CLIENT | ClientModInitializer | FMLClientSetupEvent |
| SERVER | DedicatedServerModInitializer | FMLDedicatedServerSetupEvent |
| COMPLETE | Late‑phase events such as GAME_READY | Late‑lifecycle events |

## Versions & Releases

- Versioning follows the Aprism family convention: `v<year>.<minor>[-Alpha.<n>]`, sharing the same minor‑version line as the embedded Aprism core.
- Artifact naming examples: `AprismPrismate‑v26.0‑Alpha.1‑Fa‑26.2.jar` (Fabric), `-N‑` (NeoForge), `-Fo‑` (Forge).
- Cosign keyless signing + SHA‑256 checksums + GitHub Pre‑Releases, consistent with Aprism / Refract workflows.

## Installation

Place Prismate into your host loader’s `mods/` folder, then drop Aprism `.aje` mods into the same instance’s `mods/`.
**Do not** install the Aprism javaagent in the same instance (they are mutually exclusive).
