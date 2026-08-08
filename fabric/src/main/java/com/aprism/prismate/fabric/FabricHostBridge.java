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
        // Fabric serves mod resources through the Knot classloader: adding the
        // extracted resources directory to the classpath makes its assets/data
        // entries part of the shared resource space.
        if (!FabricClassloaderBridge.inject(resourcesDir)) {
            LOG.warning("Resource directory injection failed for " + resourcesDir
                    + "; the mod's assets will not be visible to Fabric");
        }
    }

    @Override
    public void offerMixinConfig(String configName) {
        // Register the extracted mixin config with the host Mixin environment
        // so .aje mods that use Mixin patch through Fabric's own transformer.
        try {
            // Elevate the Mixin compatibility level to match the running JVM.
            // Fabric's MixinServiceKnot declares JAVA_22 as max, but the
            // environment may still sit at the JAVA_8 default if no earlier
            // config triggered elevation. Without this, mixin classes compiled
            // above Java 8 are rejected with "Class version X required is
            // higher than ... JAVA_8 supports class version 52".
            elevateMixinCompatibility();
            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins");
            mixins.getMethod("addConfiguration", String.class).invoke(null, configName);
            LOG.info("Registered mixin config '" + configName
                    + "' with the Fabric Mixin environment");
        } catch (ReflectiveOperationException e) {
            LOG.warning("Could not register mixin config '" + configName
                    + "' with the Fabric Mixin environment: " + e);
        }
    }

    /**
     * Sets the Mixin compatibility level to the highest level the current JVM
     * supports (capped at what the Mixin enum declares). Uses reflection so
     * Prismate does not hard-depend on the Mixin API.
     */
    private void elevateMixinCompatibility() {
        try {
            Class<?> levelClass = Class.forName(
                    "org.spongepowered.asm.mixin.MixinEnvironment$CompatibilityLevel");
            // Walk the enum constants from highest to lowest; pick the first
            // whose isSupported() returns true (i.e. the JVM is at least that
            // version).
            Object[] constants = levelClass.getEnumConstants();
            Object best = null;
            for (Object c : constants) {
                java.lang.reflect.Method isSupported =
                        levelClass.getDeclaredMethod("isSupported");
                isSupported.setAccessible(true);
                if (Boolean.TRUE.equals(isSupported.invoke(c))) {
                    best = c; // keep advancing; enum is declared low-to-high
                }
            }
            if (best != null) {
                Class<?> envClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment");
                envClass.getMethod("setCompatibilityLevel", levelClass).invoke(null, best);
                LOG.info("Mixin compatibility level elevated to " + best);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.fine("Could not elevate Mixin compatibility level: " + e);
        }
    }

    @Override
    public void log(String message) {
        LOG.info(message);
    }
}
