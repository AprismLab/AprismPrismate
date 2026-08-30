# AprismPrismate Parity Catalogue

> Document 3 of the Prismate set | Version: v26.11-Alpha.2 | Status: Living document
> Author: BlockConnect@StarsailsClover
>
> Definitive inventory of every upstream surface an `.aje` mod can reach under
> the Aprism agent, classified by availability under the Prismate bridge.
> Sources: AprismRuntime public-getter audit (v26.7 GA) + live findings recorded
> in FACT.md Section 7.

## Classification legend

| Mark | Meaning |
|---|---|
| FULL | Identical behavior under Prismate and the agent. |
| PARTIAL | Works under Prismate with documented differences. |
| AGENT-ONLY | Reachable under the agent; not available under Prismate today. |
| NONE | Surface absent from both loaders (upstream roadmap item). |

## Lifecycle core

| Surface | Agent | Prismate | Notes |
|---|---|---|---|
| Manifest-driven entrypoints (main/client/server) | FULL | FULL | Strict phase order identical. |
| `@AprismMod` annotation discovery (main) | FULL | FULL | v26.5 parity; client/server keys await upstream design on BOTH loaders. |
| Event bus (`AprismContext.getEventBus`) | FULL | FULL | Prismate-owned bus implementation. |
| Registry (`AprismContext.getRegistry`) | FULL | FULL | Optional-wrapping semantics verified identical (v26.7-A1 baseline). |
| InterModComms (`AprismContext.getInterModComms`) | FULL | FULL | Prismate-owned mirror of InterModCommsImpl (v26.2-A1). |
| Pack resources via entrypoint classloader | ABSENT | FULL | Live-proven divergence (v26.7-A2): the agent does not expose pack `data/` through the classloader; Prismate's resource injection does. |

## Game-side driving surfaces (runtime-singleton reachable)

These are obtained via `AprismRuntime.instance()` — which does not exist under
Prismate (library mode embeds only api + manifest). Each is either a future
bridge candidate or blocked on upstream moving the access point to context.

| Surface | Agent | Prismate | Classification / bridge path |
|---|---|---|---|
| GameTickEvent delivery | Contextual | FULL | Prismate host-tick bridge delivers END-stage events from host tick loops (v26.7/v26.8); upstream native hooks drive it under the agent when installed. |
| TickScheduler (v26.3-A9) | FULL | NONE | Bridge candidate: needs a context-reachable accessor upstream first (same OPEN as SettingsAccess). |
| CommandRegistration (v26.3-A8) | FULL | NONE | Upstream Brigadier binder is loader-core-side; context accessor proposed upstream (see OPEN note below). |
| KeyBindingRegistry (v26.3-A8) | FULL | NONE | Same blocker as CommandRegistration. |
| NetworkingRegistry (v26.3-A3) | FULL | NONE | Payload construction binds to MC classes; bridge requires the upstream network transport seam. |
| SettingsAccess (v26.7-A6) | FULL | NONE | **OPEN proposal filed upstream**: add `getSettingsAccess()` to AprismContext (IMC precedent). Persistence lives in loader-core; a Prismate-owned mirror would need the same manifest schema parser (embedded) plus its own store. |
| Content binding (items/blocks → live registries, v26.7) | FULL | NONE | Driven by loader-core's installer during agent loads. Prismate would need to invoke the binder reflectively against its own extracted jars — candidate for the next line. |
| Deep API line (bytecode hooks, JvmInsight, NativeBridge, HardwareRegistry, CrossLanguageRuntime, AprismateAgent) | Contract only | NONE | Stock-JVM contract + registries (Doc 09 item 16); real capabilities require AprismJDK. Not a Prismate concern until the JDK line matures. |

## Host-integration surfaces (Prismate-specific)

| Surface | Fabric | NeoForge | Notes |
|---|---|---|---|
| Classpath injection into host loader | FULL (Knot addToClassPath) | Degraded | FML 11 JPMS has no injection seam (docs 01 §13). |
| Resource-dir injection (host-visible) | FULL (Knot serves data/assets) | Classloader-level only | Host resource-manager integration remains deferred (docs 01 §13 issue 2). |
| Mixin passthrough | FULL | Refused at runtime | FML 11 seals the Mixin environment post-bootstrap (docs 01 §13 issue 1). |
| Status publishing (aprism.status/v1) | FULL | FULL | Identical schema/file; one publisher per game root. |
| Tick delivery (GameTickEvent END) | FULL (ClientTickEvents) | FULL (ServerTickEvent$Post) | Loader-symmetric since v26.10. |
| Graceful shutdown status refresh | Via host exit hooks | ServerStoppingEvent → SHUTDOWN snapshot | v26.10-A2. |

## Known behavioral divergences (accepted, documented)

1. **Resource visibility**: Prismate exposes pack `data/` through the entrypoint
   classloader; the agent does not (v26.7-A2 live finding). Prismate is a strict
   superset here.
2. **NeoForge mixin passthrough**: refused by the host environment
   (architectural, docs 01 §13 issue 1); mods must degrade gracefully.
3. **Tick event stage**: Prismate delivers END-stage only (the bridge cannot
   intercept the host's own processing to offer START).
4. **Error propagation in tick listeners**: Errors (non-Exceptions) escape to
   the host loop by design (tested contract, v26.8 assessment).

## Open items blocking classification upgrades

- **UPSTREAM**: `AprismContext.getSettingsAccess()` proposal (portability-first;
  do NOT ship a Prismate-private accessor).
- **UPSTREAM**: context-reachable accessors for scheduler/commands/keybinding
  (single umbrella proposal covering the runtime-singleton family).
- **PRISMATE**: content-binding invocation path for `.aje` mods loaded through
  the bridge (reflective use of loader-core's installer against extracted jars).
- **TOOLING**: fabric-26.2 Despotes build for in-world visual verification of
  worldgen content.


---

## Appendix A - aprism.status/v1 Field Catalog (normative, v26.15)

Every field in the machine-readable status document, normative as of
v26.15. All fields are additive-only per the monotonic contract; consumers
must tolerate unknown fields.

| Field | Type | Since | Meaning |
|---|---|---|---|
| schemaVersion | string | v26.6 | Always \prism.status/v1\. |
| publisher | string | v26.6 | \prismate\ (or absent when the agent published). |
| aprismVersion | string | v26.6 | Embedded Aprism core version. |
| prismateVersion | string | v26.6 | Running Prismate version. |
| loaderKey | string | v26.6 | Host loader key: \Fa\/\N\/\Fo\. |
| mcEdit | string | v26.6 | Always \JE\. |
| mcVersion | string | v26.6 | Running Minecraft version (runtime-probed). |
| generatedAt | string | v26.6 | ISO-8601 instant. |
| phase | string | v26.6 | \LOADED\/\SHUTDOWN\, or a boot-outcome name for refused boots. |
| okCount | number | v26.6 | Units in OK state. |
| failureCount | number | v26.6 | Units in FAILED state. |
| units[] | array | v26.6 | Per-unit load outcomes. |
| units[].kind | string | v26.6 | Report stage (extraction/classpath/dependency/lifecycle). |
| units[].id | string | v26.6 | Mod or unit id. |
| units[].version | string | v26.6 | Unit version. |
| units[].loaderKey | string | v26.6 | Host loader key. |
| units[].state | string | v26.6 | \OK\/\FAILED\. |
| units[].failure | string | v26.15 | Refusal reason; bounded at 512 chars with an explicit truncation marker (full chain in load-report.txt). |
| units[].durationMs | number | v26.6 | Stage duration in milliseconds. |
| deliveredTicks | number | v26.9 | GameTickEvents delivered by this runtime. |
| uptimeMs | number | v26.9 | Milliseconds since successful boot. |
| degraded | boolean | v26.9 | Host classpath injection unavailable (managed-classloader mode). |
| environment.loader | string | v26.14 | Host loader key. |
| environment.side | string | v26.14 | \CLIENT\/\DEDICATED_SERVER\. |
| environment.javaVersion | string | v26.14 | \java.version\. |
| environment.osName | string | v26.14 | \os.name\. |
| environment.osArch | string | v26.14 | \os.arch\. |
| tickRate | number | v26.14 | Mean ticks/sec over the last refresh interval; omitted on the first publish after boot. |

### Refresh cadence

- \LOADED\ snapshot: published after the load report.
- Periodic refresh: every 600 ticks (~30 s at 20 TPS) once the tick hook is
  active; a failing publish logs one warning per boot (v26.15 dedup) and
  drops further failures to FINE.
- \SHUTDOWN\ snapshot: published on graceful shutdown (ServerStoppingEvent on
  NeoForge; host-exit hooks on Fabric).