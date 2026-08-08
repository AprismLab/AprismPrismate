package com.aprism.prismate.neoforge;

import java.nio.file.Path;
import java.util.logging.Logger;

import com.aprism.prismate.PrismateVersion;
import com.aprism.prismate.host.EnvSide;
import com.aprism.prismate.host.HostBridge;

/**
 * The NeoForge implementation of {@link HostBridge} (docs 01 Section 4.1).
 * Supplies the environment ids for dependency resolution ({@code minecraft},
 * {@code neoforge}, {@code java}, plus the runtime-injected {@code aprism}
 * id) and the classpath injection path.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class NeoForgeHostBridge implements HostBridge {

    private static final Logger LOG = Logger.getLogger("prismate.neoforge");

    private final EnvSide sideOverride;
    private final Path gameDirOverride;

    /**
     * Constructs the bridge with values resolved from the live NeoForge
     * runtime.
     */
    public NeoForgeHostBridge() {
        this(null, null);
    }

    /**
     * Constructs the bridge with explicit values (used when the live runtime
     * is unavailable at construction time, and by tests).
     *
     * @param side    the distribution side, or {@code null} to detect
     * @param gameDir the game directory, or {@code null} to detect
     */
    public NeoForgeHostBridge(EnvSide side, Path gameDir) {
        this.sideOverride = side;
        this.gameDirOverride = gameDir;
    }

    @Override
    public String loaderKey() {
        return "N";
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public String hostLoaderVersion() {
        try {
            Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object versionInfo = fmlLoader.getMethod("versionInfo").invoke(null);
            Object neoForgeVersion = versionInfo.getClass().getMethod("neoForgeVersion")
                    .invoke(versionInfo);
            if (neoForgeVersion != null && !neoForgeVersion.toString().isBlank()) {
                return neoForgeVersion.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to the pin
        }
        return PrismateVersion.pinFml();
    }

    @Override
    public String minecraftVersion() {
        try {
            Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object versionInfo = fmlLoader.getMethod("versionInfo").invoke(null);
            Object mcVersion = versionInfo.getClass().getMethod("mcVersion").invoke(versionInfo);
            if (mcVersion != null && !mcVersion.toString().isBlank()) {
                return mcVersion.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through to the pin
        }
        return PrismateVersion.minecraftVersion();
    }

    @Override
    public EnvSide side() {
        if (sideOverride != null) {
            return sideOverride;
        }
        try {
            Class<?> fmlEnvironment = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = fmlEnvironment.getField("dist").get(null);
            return "CLIENT".equalsIgnoreCase(dist.toString())
                    ? EnvSide.CLIENT
                    : EnvSide.DEDICATED_SERVER;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return EnvSide.CLIENT;
        }
    }

    @Override
    public Path gameDir() {
        if (gameDirOverride != null) {
            return gameDirOverride;
        }
        try {
            Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object gamePath = fmlLoader.getMethod("getGamePath").invoke(null);
            if (gamePath instanceof Path path) {
                return path;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // fall through
        }
        return Path.of(".");
    }

    @Override
    public boolean injectJar(Path jar) {
        return NeoForgeClassloaderBridge.inject(jar);
    }

    @Override
    public void injectResourceDir(Path resourcesDir) {
        LOG.warning("Resource injection for " + resourcesDir
                + " is not implemented on NeoForge yet (planned for v26.0-Alpha.3)");
    }

    @Override
    public void offerMixinConfig(String configName) {
        // Alpha 1: best-effort registration with the host Mixin environment.
        try {
            Class<?> mixins = Class.forName("org.spongepowered.asm.mixin.Mixins");
            mixins.getMethod("addConfiguration", String.class).invoke(null, configName);
        } catch (ReflectiveOperationException e) {
            LOG.warning("Could not register mixin config '" + configName
                    + "' with the NeoForge Mixin environment: " + e);
        }
    }

    @Override
    public void log(String message) {
        LOG.info(message);
    }
}
