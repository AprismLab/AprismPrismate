package com.aprism.prismate;

import java.nio.file.Path;

import com.aprism.api.AprismPhase;
import com.aprism.prismate.config.PrismateConfig;
import com.aprism.prismate.host.HostBridge;
import com.aprism.prismate.runtime.EmbeddedRuntime;

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
        /** The pipeline itself crashed; no lifecycle dispatch will follow. */
        BOOT_FAILED
    }

    private final HostBridge bridge;
    private EmbeddedRuntime runtime;
    private BootOutcome bootOutcome;

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
            return bootOutcome;
        }
        PrismateConfig config = PrismateConfig.load(bridge.gameDir());
        if (!config.isEnabled()) {
            bridge.log("AprismPrismate is disabled via configuration; no work will be done");
            bootOutcome = BootOutcome.DISABLED;
            return bootOutcome;
        }
        bridge.log("AprismPrismate " + PrismateVersion.prismateVersion()
                + " booting on " + bridge.loaderName() + " " + bridge.hostLoaderVersion()
                + " (embedded Aprism " + PrismateVersion.embeddedAprismVersion()
                + ", Minecraft " + bridge.minecraftVersion() + ", side " + bridge.side() + ")");
        runtime = EmbeddedRuntime.create(bridge, config);
        try {
            runtime.boot();
            bootOutcome = BootOutcome.OK;
        } catch (RuntimeException e) {
            bridge.log("AprismPrismate boot failed: " + e);
            bootOutcome = BootOutcome.BOOT_FAILED;
        }
        return bootOutcome;
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
    }

    /**
     * Releases runtime resources (fallback classloader).
     */
    public void shutdown() {
        if (runtime != null) {
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
