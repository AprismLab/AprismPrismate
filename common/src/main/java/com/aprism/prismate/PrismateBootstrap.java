package com.aprism.prismate;

import java.nio.file.Path;
import java.util.Map;

import com.aprism.api.AprismPhase;
import com.aprism.prismate.config.PrismateConfig;
import com.aprism.prismate.host.HostBridge;
import com.aprism.prismate.runtime.EmbeddedRuntime;
import com.aprism.prismate.status.PrismateStatusPublisher;
import com.aprism.prismate.version.PrismateVersionLine;

/**
 * The loader bridge entrypoint orchestrator (docs 01 Section 4.1). Each host
 * loader's entrypoint class owns an instance and calls, in order:
 *
 * <ol>
 *   <li>{@link #bootEarly()} in the EARLIEST available hook (Fabric
 *       {@code ModInitializer.onInitialize}, NeoForge {@code @Mod}
 *       constructor): runs the agent conflict guard, loads config, and drives
 *       discovery -> extraction -> resolution -> classpath injection.</li>
 *   <li>{@link #dispatchEarlyLifecycle()} at the end of that same early hook:
 *       PREINIT -> INIT -> SETUP.</li>
 *   <li>{@link #dispatchSide()} from the host's side hook
 *       ({@code ClientModInitializer}/{@code DedicatedServerModInitializer} on
 *       Fabric, {@code FMLClientSetupEvent}/{@code FMLDedicatedServerSetupEvent}
 *       on NeoForge).</li>
 *   <li>{@link #dispatchComplete()} from the host's late hook
 *       ({@code GAME_READY}-equivalent) and {@link #logReport()}.</li>
 * </ol>
 *
 * <p>Classpath injection happens during Prismate's OWN early entrypoint so
 * that Aprism mods are loadable before other host mods run (docs 01 Section 8).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateBootstrap {

    /** The outcome of the early boot attempt. */
    public enum BootOutcome {
        /** Pipeline ran; mods (if any) are ready for lifecycle dispatch. */
        OK,
        /** The Aprism agent was detected; Prismate refused to boot. */
        AGENT_CONFLICT,
        /** Prismate is disabled via configuration. */
        DISABLED,
        /** The host Minecraft version is outside Prismate's JE version line. */
        VERSION_UNSUPPORTED,
        /** The pipeline itself crashed; no lifecycle dispatch will follow. */
        BOOT_FAILED
    }

    private final HostBridge bridge;
    private EmbeddedRuntime runtime;
    private BootOutcome bootOutcome;
    private long bootNanos;

    /**
     * @param bridge the host loader bridge
     */
    public PrismateBootstrap(HostBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Runs the mutual-exclusion guard, loads configuration, and drives the
     * full load pipeline. Never throws; every failure mode is logged and
     * reflected in the returned outcome. Idempotent: after the first call,
     * subsequent calls return the recorded outcome without re-running the
     * pipeline (Fabric invokes both {@code preLaunch} and {@code main}).
     *
     * @return the boot outcome
     */
    public BootOutcome bootEarly() {
        if (bootOutcome != null) {
            return bootOutcome;
        }
        if (AgentConflictDetector.isAprismAgentPresent()) {
            bridge.log(AgentConflictDetector.refusalMessage());
            bootOutcome = BootOutcome.AGENT_CONFLICT;
            publishOutcome(bootOutcome);
            return bootOutcome;
        }
        PrismateConfig config = PrismateConfig.load(bridge.gameDir());
        if (!config.isEnabled()) {
            bridge.log("AprismPrismate is disabled via configuration; no work will be done");
            bootOutcome = BootOutcome.DISABLED;
            publishOutcome(bootOutcome);
            return bootOutcome;
        }
        if (PrismateVersionLine.resolve(bridge.minecraftVersion()).isEmpty()) {
            bridge.log("AprismPrismate refuses to boot: host Minecraft "
                    + bridge.minecraftVersion()
                    + " is outside the supported JE version line ("
                    + PrismateVersionLine.describeLine() + ")");
            bootOutcome = BootOutcome.VERSION_UNSUPPORTED;
            publishOutcome(bootOutcome);
            return bootOutcome;
        }
        bridge.log("AprismPrismate " + PrismateVersion.prismateVersion()
                + " booting on " + bridge.loaderName() + " " + bridge.hostLoaderVersion()
                + " (embedded Aprism " + PrismateVersion.embeddedAprismVersion()
                + ", Minecraft " + bridge.minecraftVersion() + ", side " + bridge.side() + ")");
        runtime = EmbeddedRuntime.create(bridge, config);
        bootNanos = System.nanoTime();
        try {
            runtime.boot();
            bootOutcome = BootOutcome.OK;
        } catch (RuntimeException e) {
            bridge.log("AprismPrismate boot failed: " + e);
            bootOutcome = BootOutcome.BOOT_FAILED;
            publishOutcome(bootOutcome);
        }
        return bootOutcome;
    }

    /**
     * Enriches a status snapshot with bridge-operational fields
     * (v26.9-Alpha.2 monotonic additions): uptime, delivered tick count, and
     * the degraded-mode flag.
     */
    private void enrich(Map<String, Object> snapshot) {
        snapshot.put("uptimeMs",
                bootNanos == 0 ? 0L : (System.nanoTime() - bootNanos) / 1_000_000);
        snapshot.put("deliveredTicks", runtime == null ? 0L : runtime.getDeliveredTickCount());
        snapshot.put("degraded", runtime != null && runtime.isDegraded());
    }

    /**
     * Publishes the machine-readable status file with the boot outcome as
     * the phase (v26.6-Alpha.1 MDL deep integration): a refused or failed
     * boot is diagnosable from aprism-status.json without parsing logs.
     */
    private void publishOutcome(BootOutcome outcome) {
        try {
            var snapshot = PrismateStatusPublisher.buildSnapshot(
                    PrismateVersion.prismateVersion(),
                    PrismateVersion.embeddedAprismVersion(),
                    bridge.loaderKey(), bridge.minecraftVersion(),
                    outcome.name(), null);
            PrismateStatusPublisher.publish(bridge.gameDir(), snapshot);
        } catch (RuntimeException e) {
            bridge.log("Could not publish the status file: " + e);
        }
    }

    /**
     * Dispatches PREINIT -> INIT -> SETUP over all loaded mods.
     */
    public void dispatchEarlyLifecycle() {
        if (runtime == null) {
            return;
        }
        runtime.dispatchPhase(AprismPhase.PREINIT);
        runtime.dispatchPhase(AprismPhase.INIT);
        runtime.dispatchPhase(AprismPhase.SETUP);
    }

    /**
     * Dispatches the COMPLETE phase over all loaded mods.
     */
    public void dispatchComplete() {
        if (runtime == null) {
            return;
        }
        runtime.dispatchPhase(AprismPhase.COMPLETE);
        // v26.7-Alpha.3 host-tick bridge: once the lifecycle is complete,
        // start delivering host ticks as GameTickEvents to loaded mods.
        if (runtime.startTicking()) {
            bridge.log("Host tick hook active; GameTickEvents will be delivered");
        }
    }

    /**
     * Dispatches the side phase (CLIENT or SERVER) matching the host's
     * distribution side.
     */
    public void dispatchSide() {
        if (runtime == null) {
            return;
        }
        runtime.dispatchSideLifecycle();
    }

    /**
     * Logs the startup load report including all named failures, and writes it
     * to {@code <gameDir>/prismate/reports/load-report.txt} so users can file
     * bug reports directly from the file (v26.0-Alpha.4).
     */
    public void logReport() {
        if (runtime == null) {
            return;
        }
        bridge.log("\n" + runtime.renderReport());
        Path reportFile = runtime.writeReportFile();
        if (reportFile != null) {
            bridge.log("Prismate load report written to " + reportFile);
        }
        // v26.0-Alpha.7 surface polish: on a first run with no .aje packs,
        // write a one-time guidance file telling the user where to put mods.
        Path guidanceFile = runtime.writeFirstRunGuidanceIfEmpty();
        if (guidanceFile != null) {
            bridge.log("Prismate first-run guidance written to " + guidanceFile);
        }
        // v26.6-Alpha.1 MDL deep integration: publish the machine-readable
        // status file (phase=LOADED) so external tools diagnose the bridge
        // without parsing logs.
        try {
            Map<String, Object> snapshot = PrismateStatusPublisher.buildSnapshot(
                    PrismateVersion.prismateVersion(),
                    PrismateVersion.embeddedAprismVersion(),
                    bridge.loaderKey(), bridge.minecraftVersion(),
                    "LOADED", runtime.getReport());
            enrich(snapshot);
            Path statusFile = PrismateStatusPublisher.publish(bridge.gameDir(), snapshot);
            if (statusFile != null) {
                bridge.log("Prismate status published to " + statusFile);
            }
        } catch (RuntimeException e) {
            bridge.log("Could not publish the status file: " + e);
        }
    }

    /**
     * Releases runtime resources (fallback classloader) and refreshes the
     * machine-readable status file with phase=SHUTDOWN (v26.6-Alpha.1), so a
     * clean exit never leaves a stale LOADED snapshot behind.
     */
    public void shutdown() {
        if (runtime != null) {
            try {
                Map<String, Object> snapshot = PrismateStatusPublisher.buildSnapshot(
                        PrismateVersion.prismateVersion(),
                        PrismateVersion.embeddedAprismVersion(),
                        bridge.loaderKey(), bridge.minecraftVersion(),
                        "SHUTDOWN", runtime.getReport());
                enrich(snapshot);
                PrismateStatusPublisher.publish(bridge.gameDir(), snapshot);
            } catch (RuntimeException e) {
                bridge.log("Could not refresh the status file: " + e);
            }
            runtime.close();
        }
    }

    /**
     * @return the embedded runtime, or {@code null} before a successful
     *         {@link #bootEarly()}
     */
    public EmbeddedRuntime getRuntime() {
        return runtime;
    }
}
