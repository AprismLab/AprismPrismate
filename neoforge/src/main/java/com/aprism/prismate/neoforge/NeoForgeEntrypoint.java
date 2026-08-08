package com.aprism.prismate.neoforge;

import java.util.logging.Logger;

import com.aprism.prismate.PrismateBootstrap;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;

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
     * available hook).
     */
    public NeoForgeEntrypoint() {
        this.bootstrap = new PrismateBootstrap(new NeoForgeHostBridge());
        PrismateBootstrap.BootOutcome outcome = bootstrap.bootEarly();
        if (outcome != PrismateBootstrap.BootOutcome.OK) {
            return; // refused (agent conflict), disabled, or boot failed
        }
        bootstrap.dispatchEarlyLifecycle(); // PREINIT -> INIT -> SETUP

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(FMLClientSetupEvent.class, this::onClientSetup);
        modBus.addListener(FMLDedicatedServerSetupEvent.class, this::onServerSetup);
        modBus.addListener(FMLLoadCompleteEvent.class, this::onLoadComplete);
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
}
