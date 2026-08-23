package com.aprism.prismate.host;

import java.nio.file.Path;

/**
 * The loader-specific surface Prismate needs from its host loader (docs 01
 * Section 4.1). One implementation exists per loader ({@code fabric/},
 * {@code neoforge/}, {@code forge/}); the shared core in {@code common/}
 * programs against this interface only.
 *
 * <p>Classpath injection is the primary integration path (docs 01 Section
 * 9.3). When {@link #injectJar} returns {@code false}, Prismate falls back to
 * its own child classloader and logs a degraded-mode warning.
 *
 * @author BlockConnect@StarsailsClover
 */
public interface HostBridge {

    /**
     * @return the Aprism loader key of this host loader ({@code Fa}, {@code N},
     *         {@code Fo})
     */
    String loaderKey();

    /**
     * @return the human-readable loader name ({@code Fabric}, {@code NeoForge},
     *         {@code Forge})
     */
    String loaderName();

    /**
     * @return the running host loader version (supplied as the
     *         {@code fabricloader}/{@code neoforge} environment id)
     */
    String hostLoaderVersion();

    /**
     * @return the running Minecraft version (supplied as the {@code minecraft}
     *         environment id)
     */
    String minecraftVersion();

    /**
     * @return the distribution side the host is running on
     */
    EnvSide side();

    /**
     * @return the game instance root (the directory containing {@code mods/})
     */
    Path gameDir();

    /**
     * Injects a jar into the host loader's classloader so its classes and
     * resources become part of the shared class space.
     *
     * @param jar the jar to inject
     * @return {@code true} if the jar was injected into the host classloader;
     *         {@code false} if the host offers no working injection path (the
     *         caller then falls back to a Prismate-managed child classloader)
     */
    boolean injectJar(Path jar);

    /**
     * Best-effort: makes an extracted {@code resources/} directory visible to
     * the host's resource loading. Default is a no-op.
     *
     * @param resourcesDir the extracted resources directory
     */
    default void injectResourceDir(Path resourcesDir) {
        // no-op by default; loader bridges override when the host supports it
    }

    /**
     * Best-effort: registers a mixin config (extracted from a pack's
     * {@code mixins/}) with the host loader's Mixin environment. Default is a
     * no-op.
     *
     * @param configName the mixin config resource path
     */
    default void offerMixinConfig(String configName) {
        // no-op by default; loader bridges override when the host supports it
    }

    /**
     * Best-effort: registers a callback invoked once per host game tick
     * (v26.7-Alpha.3 host-tick bridge). Default is unavailable; loader
     * bridges override when the host offers a tick event surface (Fabric API
     * lifecycle events on Fabric).
     *
     * @param listener the per-tick callback
     * @return {@code true} when the host tick hook was registered;
     *         {@code false} when the host offers none this boot
     */
    default boolean registerTickHook(HostTickListener listener) {
        return false;
    }

    /**
     * Logs through the host loader's logging when available. Default writes to
     * {@code java.util.logging}.
     *
     * @param message the message to log
     */
    default void log(String message) {
        java.util.logging.Logger.getLogger("prismate").info(message);
    }
}
