package com.aprism.prismate.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;

import com.aprism.prismate.widener.PrismateAccessWidener;

/**
 * Child classloader used by Prismate when the host loader offers no working
 * jar-injection path (the documented degraded path, docs 01 Section 9.3).
 *
 * <p>Parent-first delegation is deliberate: mod classes reference
 * {@code com.aprism.api}, and that package lives on Prismate's own classloader
 * (the host loader's mod classloader). Parent-first resolution guarantees
 * mods bind to the SAME {@code com.aprism.api} classes Prismate uses, keeping
 * {@code instanceof} and lifecycle dispatch intact (docs 01 Section 9.1).
 *
 * <p>Access widening (v26.0-Alpha.4): classes resolved from the mod jars pass
 * through the registered {@link PrismateAccessWidener} so {@code .aje} mods
 * that declare an {@code accessWidener} get their widened visibility even
 * though the host loader applies no widener pass to runtime-injected jars.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateModClassLoader extends URLClassLoader {

    private final PrismateAccessWidener widener = new PrismateAccessWidener();

    /**
     * @param parent the host loader's classloader (Prismate's own loader)
     */
    public PrismateModClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    /**
     * Adds a jar to this classloader's search path.
     *
     * @param jar the jar to add
     */
    public void addJar(java.nio.file.Path jar) {
        try {
            addURL(jar.toUri().toURL());
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Cannot convert to URL: " + jar, e);
        }
    }

    /**
     * Adds an extracted {@code resources/} directory to this classloader's
     * search path (v26.1-Alpha.4). This makes a pack's resource entries
     * resolvable via {@code getResource}/{@code getResourceAsStream} for
     * classes loaded through this loader, closing the NeoForge resource gap
     * at the classloader level where the host offers no resource-injection
     * path. Host-level resource-manager integration (visible to the host's
     * own resource reload) remains a separate, host-specific concern.
     *
     * @param resourcesDir the extracted resources directory to add
     */
    public void addResourceDir(java.nio.file.Path resourcesDir) {
        try {
            addURL(resourcesDir.toUri().toURL());
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("Cannot convert to URL: " + resourcesDir, e);
        }
    }

    /**
     * @return the access widener applied to classes loaded by this loader
     */
    public PrismateAccessWidener getWidener() {
        return widener;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String slashed = name.replace('.', '/');
        // Fast path: no widener rules target this class, so delegate to the
        // stock URLClassLoader (zero extra overhead, identical behavior).
        if (!widener.hasRulesFor(slashed)) {
            return super.findClass(name);
        }
        String resourcePath = slashed + ".class";
        URL url = findResource(resourcePath);
        if (url == null) {
            return super.findClass(name);
        }
        byte[] bytes;
        try {
            // useCaches=false keeps JarURLConnection from parking the opened
            // JarFile in its static JarFileFactory cache; a cached JarFile
            // holds a Windows file lock that URLClassLoader.close() cannot
            // release, which blocks work-dir cleanup.
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            try (InputStream in = connection.getInputStream()) {
                bytes = readAll(in);
            }
        } catch (IOException e) {
            throw new ClassNotFoundException("Cannot read class bytes for " + name, e);
        }
        bytes = widener.transform(slashed, bytes);
        return defineClass(name, bytes, 0, bytes.length);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
