package com.aprism.prismate.fabric;

import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.logging.Logger;

import com.aprism.prismate.host.HostBridge;

/**
 * Injects jars into Fabric Loader's Knot classloader (docs 01 Section 9.3,
 * OPEN-4). Fabric's injection entry point is resolved reflectively against
 * the running Fabric Loader so the bridge tolerates the internal-API shifts
 * Fabric has made across versions:
 *
 * <ol>
 *   <li>{@code net.fabricmc.loader.impl.launch.FabricLauncherBase#getLauncher()}
 *       (returns the active launcher, e.g. Knot), then</li>
 *   <li>{@code FabricLauncher#addToClassPath(Path, String...)} on it.</li>
 * </ol>
 *
 * <p>If no working path is found, {@link #inject(Path)} returns {@code false}
 * and the runtime falls back to its managed classloader (degraded path).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class FabricClassloaderBridge {

    private static final Logger LOG = Logger.getLogger("prismate.fabric");

    private FabricClassloaderBridge() {
    }

    /**
     * Injects a jar into the Fabric classloader.
     *
     * @param jar the jar to inject
     * @return true on successful injection
     */
    public static boolean inject(Path jar) {
        try {
            Class<?> launcherBase = Class.forName(
                    "net.fabricmc.loader.impl.launch.FabricLauncherBase");
            Method getLauncher = launcherBase.getMethod("getLauncher");
            Object launcher = getLauncher.invoke(null);
            if (launcher == null) {
                LOG.warning("Fabric launcher instance unavailable; cannot inject " + jar);
                return false;
            }
            Method addToClassPath = launcher.getClass()
                    .getMethod("addToClassPath", Path.class, String[].class);
            addToClassPath.setAccessible(true);
            addToClassPath.invoke(launcher, jar, (Object) new String[0]);
            return true;
        } catch (ClassNotFoundException e) {
            LOG.warning("Fabric launcher classes not found; injection unavailable: " + e);
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warning("Fabric classpath injection failed for " + jar + ": "
                    + describe(e));
            return false;
        }
    }

    /**
     * Converts a classpath URL back to a filesystem path (used to locate the
     * Prismate jar itself when needed).
     *
     * @param resourceName a resource inside the Prismate jar
     * @return the jar path, or {@code null} if unresolvable
     */
    public static Path locateJarOf(String resourceName) {
        var url = FabricClassloaderBridge.class.getClassLoader().getResource(resourceName);
        if (url == null) {
            return null;
        }
        try {
            String spec = url.toString();
            if (spec.startsWith("jar:")) {
                spec = spec.substring(4, spec.indexOf('!'));
            }
            return Path.of(new java.net.URI(spec));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.toString();
    }
}
