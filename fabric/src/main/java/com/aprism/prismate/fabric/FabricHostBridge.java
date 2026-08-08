package com.aprism.prismate.fabric;

import java.nio.file.Path;
import java.util.logging.Logger;

import com.aprism.prismate.PrismateVersion;
import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.host.HostBridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Fabric implementation of {@link HostBridge} (docs 01 Section 4.1).
 * Supplies the environment ids for dependency resolution ({@code minecraft},
 * {@code fabricloader}, {@code java}, plus the runtime-injected
 * {@code aprism} id) and the classpath injection path.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class FabricHostBridge implements HostBridge {

    private static final Logger LOG = Logger.getLogger("prismate.fabric");

    @Override
    public String loaderKey() {
        return "Fa";
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public String hostLoaderVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("fabricloader")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse(PrismateVersion.pinFabricLoader());
        } catch (RuntimeException e) {
            return PrismateVersion.pinFabricLoader();
        }
    }

    @Override
    public String minecraftVersion() {
        return PrismateVersion.minecraftVersion();
    }

    @Override
    public EnvSide side() {
        try {
            return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                    ? EnvSide.CLIENT
                    : EnvSide.DEDICATED_SERVER;
        } catch (RuntimeException e) {
            return EnvSide.CLIENT;
        }
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public boolean injectJar(Path jar) {
        return FabricClassloaderBridge.inject(jar);
    }

    @Override
    public void injectResourceDir(Path resourcesDir) {
        // Alpha 1: extracted resources are not yet wired into Fabric's
        // resource loading; tracked as the v26.0-Alpha.2 exit criterion.
        LOG.warning("Resource injection for " + resourcesDir
                + " is not implemented on Fabric yet (planned for v26.0-Alpha.2)");
    }

    @Override
    public void offerMixinConfig(String configName) {
        // Alpha 1: best-effort registration with the host Mixin environment
        // (mixin passthrough is proven end-to-end in v26.0-Alpha.2).
        try {
            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins");
            mixins.getMethod("addConfiguration", String.class).invoke(null, configName);
        } catch (ReflectiveOperationException e) {
            LOG.warning("Could not register mixin config '" + configName
                    + "' with the Fabric Mixin environment: " + e);
        }
    }

    @Override
    public void log(String message) {
        LOG.info(message);
    }
}
