package com.aprism.prismate.fabric;

import com.aprism.prismate.PrismateBootstrap;

/**
 * Shared Prismate state across the three Fabric entrypoint classes. Fabric
 * instantiates each declared entrypoint separately; the bootstrap must exist
 * exactly once per JVM.
 *
 * @author BlockConnect@StarsailsClover
 */
final class FabricPrismateState {

    private FabricPrismateState() {
    }

    private static volatile PrismateBootstrap bootstrap;

    /**
     * @return the shared bootstrap, creating it on first use
     */
    static synchronized PrismateBootstrap getOrCreate() {
        if (bootstrap == null) {
            bootstrap = new PrismateBootstrap(new FabricHostBridge());
        }
        return bootstrap;
    }

    /**
     * @return the shared bootstrap, or {@code null} if not yet created
     */
    static PrismateBootstrap peek() {
        return bootstrap;
    }
}
