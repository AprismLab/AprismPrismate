package com.aprism.prismate.runtime;

import java.net.URL;
import java.net.URLClassLoader;

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
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateModClassLoader extends URLClassLoader {

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
}
