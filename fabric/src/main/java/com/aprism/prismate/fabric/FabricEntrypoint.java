package com.aprism.prismate.fabric;

import com.aprism.prismate.PrismateBootstrap;

import net.fabricmc.api.ModInitializer;

/**
 * The Fabric {@code ModInitializer} entrypoint of AprismPrismate (docs 01
 * Section 8.1). Runs the mutual-exclusion guard and the full load pipeline
 * (discover -> extract -> resolve -> inject) during Prismate's own early
 * entrypoint, then dispatches PREINIT -> INIT -> SETUP.
 *
 * <p>COMPLETE is mapped onto Fabric's {@code LifecycleEvents.GAME_READY}
 * when Fabric API is present; without Fabric API it is dispatched at the end
 * of this entrypoint (docs 01 Section 8.1: "GAME_READY (or equivalent)").
 *
 * @author BlockConnect@StarsailsClover
 */
public final class FabricEntrypoint implements ModInitializer {

    @Override
    public void onInitialize() {
        PrismateBootstrap bootstrap = FabricPrismateState.getOrCreate();
        // The pipeline normally already ran in preLaunch (required for Mixin
        // passthrough); bootEarly() is idempotent, so this also covers hosts
        // that skip preLaunch for any reason.
        PrismateBootstrap.BootOutcome outcome = bootstrap.bootEarly();
        if (outcome != PrismateBootstrap.BootOutcome.OK) {
            return; // refused (agent conflict), disabled, or boot failed
        }
        bootstrap.dispatchEarlyLifecycle(); // PREINIT -> INIT -> SETUP

        if (!registerGameReadyComplete(bootstrap)) {
            // No Fabric API GAME_READY hook available: dispatch COMPLETE now.
            bootstrap.dispatchComplete();
            bootstrap.logReport();
        }
    }

    /**
     * Reflectively registers COMPLETE on Fabric API's
     * {@code LifecycleEvents.GAME_READY} when available. Kept reflective so
     * Prismate does not hard-depend on Fabric API.
     *
     * @param bootstrap the Prismate bootstrap
     * @return true when the hook was registered
     */
    private static boolean registerGameReadyComplete(PrismateBootstrap bootstrap) {
        try {
            Class<?> lifecycleEvents = Class.forName(
                    "net.fabricmc.fabric.api.event.lifecycle.v1.LifecycleEvents");
            Object gameReady = lifecycleEvents.getField("GAME_READY").get(null);
            java.lang.reflect.Method register = gameReady.getClass()
                    .getMethod("register", Object.class);
            register.setAccessible(true);
            // The GAME_READY listener type is a functional interface; a lambda
            // would bind to the Fabric API types at compile time, so invoke
            // reflectively through a dynamic proxy instead.
            Class<?> listenerType = Class.forName(
                    "net.fabricmc.fabric.api.event.lifecycle.v1.LifecycleEvents$GameReady");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    new Class<?>[]{listenerType},
                    (p, method, args) -> {
                        if (method.getName().equals("onGameReady")) {
                            bootstrap.dispatchComplete();
                            bootstrap.logReport();
                        }
                        return null;
                    });
            register.invoke(gameReady, proxy);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
