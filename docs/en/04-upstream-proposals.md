# Prismate Upstream Proposals

> Document 4 of the Prismate set | Version: v26.12-Alpha.1 | Status: Proposal
> Author: BlockConnect@StarsailsClover
> Target project: Aprism (aprism-api)

## Context

Prismate runs `.aje` mods inside Fabric/NeoForge by embedding `aprism-api` +
`aprism-manifest` only (library mode). Upstream surfaces accessed through
`AprismRuntime.instance()` are unreachable in this mode — there is no runtime
singleton. Mods loaded through Prismate therefore cannot use those surfaces,
breaking agent/bridge parity for any mod that touches them.

This document proposes a single umbrella change: **move the per-mod service
accessors onto `AprismContext`**, following the precedent already set by
`getInterModComms()`.

## Proposed accessors (all additive, default-throwing)

```java
public interface AprismContext {
    // existing: getMod/getEventBus/getRegistry/getLogger/getInterModComms

    default TickScheduler getTickScheduler() {
        throw new UnsupportedOperationException();
    }

    default CommandRegistration getCommandRegistration() { ... }   // already added upstream v26.8-A2-era? verify
    default KeyBindingRegistry getKeyBindingRegistry() { ... }
    default SettingsAccess getSettingsAccess() { ... }              // already added upstream v26.8-A2 ✓
    default NetworkingRegistry getNetworking() { ... }              // or narrower send surface
}
```

> Note: `getCommandRegistration()` and `getSettingsAccess()` already exist as
> default-throwing methods on upstream HEAD. The remaining gaps are
> **scheduler** and **networking**; commands/keybinding need only their impls
> wired to whatever accessor the runtime exposes through context.

## Per-surface rationale

| Surface | Why context-reachable matters | Prismate bridge path once exposed |
|---|---|---|
| TickScheduler | Mods schedule delayed/repeating tasks; under Prismate they currently have no handle. | Prismate-owned scheduler implementation driven by the host-tick bridge (already delivers ticks). |
| CommandRegistration | Brigadier binding is loader-core-side; mods need the registration window handle. | Prismate forwards to the host loader's command registry where available (Fabric command API), fail-closed otherwise. |
| KeyBindingRegistry | Same pattern as commands. | Forward to host key-mapping API (Fabric), fail-closed otherwise. |
| SettingsAccess | Persistence lives in loader-core; Prismate can implement the same JSON store against its own work dir. | Prismate-owned mirror of SettingsAccessImpl against manifest-declared schemas (schema parser already embedded). |
| NetworkingRegistry | Transport seam binds to MC payloads. | Prismate forwards to host networking where the loader exposes it; fail-closed on NeoForge until an FML seam exists. |

## Compatibility

All additions are `default` methods that throw `UnsupportedOperationException`,
matching the established pattern (`getItemRegistry`, `getBlockRegistry`,
`getInterModComms`). No implementor breaks. Runtime implementations opt in by
overriding.

## Verification expectations

Each new accessor should ship with:
1. A runtime-side test proving the instance wiring.
2. A contract note in Doc 09 stating whether stock-JVM (agent) and Prismate
   deliveries differ.
