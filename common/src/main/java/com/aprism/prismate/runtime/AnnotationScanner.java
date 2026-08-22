package com.aprism.prismate.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.aprism.api.AprismMod;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Scans a mod's embedded jar(s) for classes annotated with
 * {@link AprismMod @AprismMod}, without loading them (v26.5-Alpha.1 upstream
 * alignment, mirroring Aprism core's {@code AnnotationScanner}). Uses ASM to
 * read runtime-visible annotations from class files directly.
 *
 * <p>When a mod manifest does not declare an explicit {@code entrypoints}
 * {@code main} key, the embedded runtime delegates to this scanner so that an
 * {@code .aje} mod authored with only the annotation behaves identically
 * whether loaded by the Aprism agent or by Prismate.
 *
 * <p>The scanner reads class files from the pack's extracted jar paths. It
 * returns the fully-qualified class names of all classes carrying
 * {@code @AprismMod} whose {@code value()} either is empty or matches the
 * expected mod id.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class AnnotationScanner {

    /** The ASM API version used by the scanner. */
    private static final int ASM_API = Opcodes.ASM9;

    /** The internal name of the {@link AprismMod} annotation. */
    private static final String APRISM_MOD_DESC =
            Type.getDescriptor(AprismMod.class);

    private AnnotationScanner() {
    }

    /**
     * Scans the given jar paths for classes annotated with
     * {@link AprismMod @AprismMod}.
     *
     * @param jarPaths      the jar file paths to scan (the pack's extracted
     *                      embedded jars)
     * @param expectedModId the mod id from the manifest; used to filter
     *                      {@code @AprismMod(value = "...")} entries; null or
     *                      empty accepts all annotated classes
     * @return the fully-qualified class names carrying a matching annotation,
     *         in scan order
     */
    public static List<String> scanModEntrypoints(List<Path> jarPaths, String expectedModId) {
        List<String> results = new ArrayList<>();
        String normalizedId = expectedModId == null ? "" : expectedModId.trim();
        for (Path jarPath : jarPaths) {
            scanJar(jarPath, normalizedId, results);
        }
        return results;
    }

    /**
     * Scans a single jar file for {@code @AprismMod}-annotated classes. A
     * corrupt or unreadable jar is logged but does not abort the scan of the
     * remaining jars.
     */
    private static void scanJar(Path jarPath, String expectedModId, List<String> results) {
        if (!Files.isRegularFile(jarPath)) {
            return;
        }
        try (FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null)) {
            Path root = fs.getPath("/");
            try (Stream<Path> entries = Files.walk(root)) {
                entries.filter(p -> p.toString().endsWith(".class"))
                       .filter(p -> !p.toString().endsWith("module-info.class"))
                       .filter(p -> !p.toString().endsWith("package-info.class"))
                       .forEach(classFile -> scanClassFile(classFile, expectedModId, results));
            }
        } catch (IOException e) {
            Logger.getLogger("prismate")
                    .warning("AnnotationScanner: failed to scan jar " + jarPath + ": " + e.getMessage());
        }
    }

    /**
     * Reads a single class file and checks for a matching
     * {@code @AprismMod} annotation. Unreadable class files are skipped.
     */
    private static void scanClassFile(Path classFile, String expectedModId, List<String> results) {
        try (InputStream is = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(is);
            ModAnnotationVisitor visitor = new ModAnnotationVisitor(expectedModId);
            reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (visitor.found()) {
                results.add(visitor.className());
            }
        } catch (IOException | ArrayIndexOutOfBoundsException e) {
            // Skip unreadable class files; they may be malformed or non-standard
        }
    }

    /**
     * ASM visitor that detects {@code @AprismMod} on a class and extracts the
     * {@code value()} attribute for filtering.
     */
    private static final class ModAnnotationVisitor extends ClassVisitor {

        private final String expectedModId;
        private String className;
        private boolean hasAprismMod;
        private String annotationValue;

        ModAnnotationVisitor(String expectedModId) {
            super(ASM_API);
            this.expectedModId = expectedModId;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                         String superName, String[] interfaces) {
            this.className = name;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (visible && APRISM_MOD_DESC.equals(descriptor)) {
                hasAprismMod = true;
                return new AnnotationVisitor(ASM_API) {
                    @Override
                    public void visit(String name, Object value) {
                        if ("value".equals(name) && value instanceof String s) {
                            annotationValue = s;
                        }
                    }
                };
            }
            return null;
        }

        boolean found() {
            if (!hasAprismMod) {
                return false;
            }
            if (annotationValue == null || annotationValue.isBlank()) {
                return true;
            }
            if (expectedModId == null || expectedModId.isEmpty()) {
                return true;
            }
            return expectedModId.equals(annotationValue.trim());
        }

        String className() {
            // Convert internal name (com/example/Foo) to dotted form (com.example.Foo)
            return className == null ? "" : className.replace('/', '.');
        }
    }
}
