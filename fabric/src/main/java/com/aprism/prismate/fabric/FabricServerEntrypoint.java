package com.aprism.prismate.fabric;

import com.aprism.prismate.PrismateBootstrap;

import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * The Fabric {@code DedicatedServerModInitializer} entrypoint: dispatches the
 * SERVER side phase over all loaded mods (docs 01 Section 8.1).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class FabricServerEntrypoint implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        PrismateBootstrap bootstrap = FabricPrismateState.peek();
        if (bootstrap != null) {
            bootstrap.dispatchSide();
        }
    }
}
