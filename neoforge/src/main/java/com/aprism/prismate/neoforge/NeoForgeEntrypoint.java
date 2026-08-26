package com.aprism.prismate.neoforge;

import java.util.logging.Logger;

import com.aprism.prismate.PrismateBootstrap;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * The NeoForge entrypoint of AprismPrismate (docs 01 Section 8.2). The
 * {@code @Mod} constructor is the earliest hook: it runs the mutual-exclusion
 * guard and the full load pipeline (discover -> extract -> resolve -> inject),
 * then dispatches PREINIT -> INIT -> SETUP.
 *
 * <p>Side phases map onto {@code FMLClientSetupEvent} /
 * {@code FMLDedicatedServerSetupEvent}; COMPLETE maps onto
 * {@code FMLLoadCompleteEvent} (a late lifecycle event).
 *
 * @author BlockConnect@StarsailsClover
 */
@Mod(NeoForgeEntrypoint.MOD_ID)
@SuppressWarnings({"deprecation", "removal"})
public final class NeoForgeEntrypoint {

    /** The Prismate mod id on NeoForge. */
    public static final String MOD_ID = "aprismprismate";

    private static final Logger LOG = Logger.getLogger("prismate.neoforge");

    private final PrismateBootstrap bootstrap;

    /**
     * NeoForge constructs this class during mod construction (the earliest
     * available hook). FML injects the mod-scoped {@link IEventBus} through
     * the constructor (FML 11 removed the thread-local
     * {@code FMLJavaModLoadingContext}; constructor injection is the
     * supported way to obtain the bus).
     *
     * @param modBus the mod-scoped event bus injected by FML
     */
    @SuppressWarnings({"deprecation", "removal"})
    public NeoForgeEntrypoint(IEventBus modBus) {
        try {
            this.bootstrap = new PrismateBootstrap(new NeoForgeHostBridge());
            PrismateBootstrap.BootOutcome outcome = bootstrap.bootEarly();
            if (outcome != PrismateBootstrap.BootOutcome.OK) {
                return; // refused (agent conflict), disabled, or boot failed
            }
            bootstrap.dispatchEarlyLifecycle(); // PREINIT -> INIT -> SETUP

            modBus.addListener(FMLClientSetupEvent.class, this::onClientSetup);
            modBus.addListener(FMLDedicatedServerSetupEvent.class, this::onServerSetup);
            modBus.addListener(FMLLoadCompleteEvent.class, this::onLoadComplete);
            registerServerStoppingHook();
        } catch (Throwable t) {
            // NeoForge shows fatal errors in a GUI dialog that is invisible
            // from a console harness; mirror the failure to stderr so the
            // smoke log captures the root cause before the dialog appears.
            System.err.println("[PRISMATE-NEOFORGE] entrypoint construction failed:");
            t.printStackTrace(System.err);
            throw t;
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        bootstrap.dispatchSide();
    }

    private void onServerSetup(FMLDedicatedServerSetupEvent event) {
        bootstrap.dispatchSide();
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        bootstrap.dispatchComplete();
        bootstrap.logReport();
    }

    /**
     * Registers the server-stopping hook on the GLOBAL NeoForge bus
     * (v26.10-Alpha.2). ServerStoppingEvent lives on the game bus, not the
     * mod-scoped bus, and the full neoforge jar is not a compile dependency,
     * so the registration is reflective — consistent with the bridge style.
     * Without this, bootstrap.shutdown() never runs on a dedicated server:
     * the status file stays LOADED forever and the fallback classloader
     * leaks until process exit (live-found on the first server matrix run).
     */
    private void registerServerStoppingHook() {
        try {
            Class<?> neoForge = Class.forName("net.neoforged.neoforge.common.NeoForge");
            Object eventBus = neoForge.getField("EVENT_BUS").get(null);
            Class<?> stoppingEvent = Class.forName(
                    "net.neoforged.neoforge.event.server.ServerStoppingEvent");
            Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus");
            java.lang.reflect.Method addListener = iEventBus.getMethod(
                    "addListener", Class.class, java.util.function.Consumer.class);
            Object consumerProxy = java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{java.util.function.Consumer.class},
                    (p, method, args) -> {
                        if (method.getName().equals("accept")) {
                            bootstrap.shutdown();
                        }
                        return null;
                    });
            addListener.invoke(eventBus, stoppingEvent, consumerProxy);
            LOG.info("Server-stopping hook registered; shutdown will publish SHUTDOWN status");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warning("Could not register the server-stopping hook: " + e);
        }
    }
}
