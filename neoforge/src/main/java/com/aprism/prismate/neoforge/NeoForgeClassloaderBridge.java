package com.aprism.prismate.neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.logging.Logger;

import com.aprism.prismate.host.HostBridge;

/**
 * The NeoForge implementation of the classpath-injection side of
 * {@link HostBridge} (docs 01 Section 9.3, OPEN-5).
 *
 * <p>NeoForge (FML 2.x) offers no public runtime jar-injection API: mod
 * files are scanned and turned into modules before mods construct. This
 * bridge therefore attempts best-effort reflective paths and otherwise
 * reports injection as unavailable, which makes the embedded runtime fall
 * back to its managed classloader (the documented degraded path). The real
 * integration (participating in NeoForge's mod file / module layer) is the
 * v26.0-Alpha.3 milestone.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class NeoForgeClassloaderBridge {

    private static final Logger LOG = Logger.getLogger("prismate.neoforge");

    private NeoForgeClassloaderBridge() {
    }

    /**
     * Best-effort jar injection into the NeoForge classloading space.
     *
     * @param jar the jar to inject
     * @return true if a working injection path was found
     */
    public static boolean inject(Path jar) {
        // Attempt 1: FMLLoader.getLaunchHandler() exposes the launch handler,
        // whose classloader may be an extendable URLClassLoader in some
        // configurations. This is deliberately conservative: if the classloader
        // is not a URLClassLoader (the JPMS modular case), the attempt fails
        // cleanly and the runtime uses its managed classloader.
        try {
            Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Method getLaunchHandler = fmlLoader.getMethod("getLaunchHandler");
            Object handler = getLaunchHandler.invoke(null);
            if (handler != null) {
                Method getClassLoader = findMethod(handler.getClass(), "getClassLoader");
                if (getClassLoader != null) {
                    Object loader = getClassLoader.invoke(handler);
                    if (loader instanceof java.net.URLClassLoader urlLoader) {
                        Method addUrl = java.net.URLClassLoader.class
                                .getDeclaredMethod("addURL", java.net.URL.class);
                        addUrl.setAccessible(true);
                        addUrl.invoke(urlLoader, jar.toUri().toURL());
                        return true;
                    }
                }
            }
        } catch (ReflectiveOperationException | java.io.IOException | RuntimeException e) {
            LOG.fine("NeoForge injection attempt via launch handler failed: " + e);
        }
        return false;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // keep walking up
            }
        }
        return null;
    }
}
