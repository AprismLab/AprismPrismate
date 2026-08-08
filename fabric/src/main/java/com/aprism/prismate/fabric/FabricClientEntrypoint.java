package com.aprism.prismate.fabric;

import com.aprism.prismate.PrismateBootstrap;

import net.fabricmc.api.ClientModInitializer;

/**
 * The Fabric {@code ClientModInitializer} entrypoint: dispatches the CLIENT
 * side phase over all loaded mods (docs 01 Section 8.1).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class FabricClientEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        PrismateBootstrap bootstrap = FabricPrismateState.peek();
        if (bootstrap != null) {
            bootstrap.dispatchSide();
        }
    }
}
