package com.aprism.prismate.fabric;

import com.aprism.prismate.PrismateBootstrap;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * The Fabric {@code preLaunch} entrypoint of AprismPrismate. Runs the
 * mutual-exclusion guard and the full load pipeline (discover -> extract ->
 * resolve -> classpath + mixin registration) BEFORE any Minecraft class is
 * loaded.
 *
 * <p>This ordering is mandatory for Mixin passthrough: Fabric invokes
 * {@code ModInitializer} from inside {@code Minecraft.<init>}, at which point
 * the Minecraft class is already loaded and mixins targeting it can no longer
 * be prepared. {@code preLaunch} runs after Fabric Loader initialization and
 * before the game classes load, so injected jars and mixin configs are in
 * place when the Mixin transformer first sees the game classes.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class FabricPreLaunchEntrypoint implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        PrismateBootstrap bootstrap = FabricPrismateState.getOrCreate();
        bootstrap.bootEarly();
    }
}
