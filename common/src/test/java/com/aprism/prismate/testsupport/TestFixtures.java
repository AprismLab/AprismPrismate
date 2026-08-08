package com.aprism.prismate.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Builds synthetic {@code .aje} packs, mod jars, and ASM-generated
 * {@code IAprismMod} entrypoint classes for headless tests (mirrors the
 * synthetic-fixture strategy used by the Aprism core test suite).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /**
     * Writes a standard mod manifest JSON.
     *
     * @param id           the mod id
     * @param version      the mod version
     * @param environment  the environment token ({@code *}, {@code client}, ...)
     * @param entrypoint   the main entrypoint class name (may be {@code null})
     * @param depends      dependency id -> range entries
     * @param provides     provided ids
     * @return the manifest JSON text
     */
    public static String manifestJson(String id, String version, String environment,
            String entrypoint, Map<String, String> depends, String... provides) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": 1,\n");
        sb.append("  \"id\": \"").append(id).append("\",\n");
        sb.append("  \"version\": \"").append(version).append("\",\n");
        sb.append("  \"displayName\": \"").append(id).append(" display\",\n");
        sb.append("  \"description\": \"synthetic test mod\",\n");
        sb.append("  \"environment\": \"").append(environment).append("\",\n");
        sb.append("  \"entrypoints\": {");
        if (entrypoint != null) {
            sb.append("\n    \"main\": [\"").append(entrypoint).append("\"]\n  ");
        }
        sb.append("},\n");
        sb.append("  \"mixins\": [],\n");
        sb.append("  \"depends\": {");
        if (depends != null && !depends.isEmpty()) {
            sb.append('\n');
            int i = 0;
            for (Map.Entry<String, String> e : depends.entrySet()) {
                if (i++ > 0) {
                    sb.append(",\n");
                }
                sb.append("    \"").append(e.getKey()).append("\": \"")
                        .append(e.getValue()).append("\"");
            }
            sb.append("\n  ");
        }
        sb.append("},\n");
        sb.append("  \"platforms\": {},\n");
        sb.append("  \"accessWidener\": null,\n");
        sb.append("  \"provides\": [");
        for (int i = 0; i < provides.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(provides[i]).append('"');
        }
        sb.append("],\n");
        sb.append("  \"custom\": {}\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Writes a zip archive with the given entries.
     *
     * @param target  the archive path
     * @param entries entry name -> bytes
     */
    public static void writeZip(Path target, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(target.getParent() != null ? target.getParent() : Path.of("."));
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    /**
     * Builds a standard {@code .aje}: manifest + {@code <modid>.jar} at root.
     *
     * @param target        the pack path
     * @param manifestJson  the manifest JSON text
     * @param modId         the mod id (names the main jar)
     * @param jarBytes      the main jar bytes
     * @param extraEntries  additional entries (resources/, mixins/, lib/)
     */
    public static void writeAje(Path target, String manifestJson, String modId,
            byte[] jarBytes, Map<String, byte[]> extraEntries) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("aprism.manifest.json", manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put(modId + ".jar", jarBytes);
        if (extraEntries != null) {
            entries.putAll(extraEntries);
        }
        writeZip(target, entries);
    }

    /**
     * Builds a jar containing the given entries (no manifest file).
     *
     * @param entries entry name -> bytes
     * @return the jar bytes
     */
    public static byte[] jarBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * Generates an {@code IAprismMod} implementation class whose every phase
     * callback records {@code <modId>:<PHASE>} through
     * {@link PhaseRecorder#record}. The class is NOT present on the test
     * classpath, so it can only load from the injected jar.
     *
     * @param className the binary class name (dot form)
     * @param modId     the mod id used in recorded events
     * @return the class bytes
     */
    public static byte[] generateRecordingMod(String className, String modId) {
        String internalName = className.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", new String[]{"com/aprism/api/IAprismMod"});

        // public <init>()
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        generatePhaseMethod(cw, "onPreInitialize", modId + ":PREINIT");
        generatePhaseMethod(cw, "onInitialize", modId + ":INIT");
        generatePhaseMethod(cw, "onSetup", modId + ":SETUP");
        generatePhaseMethod(cw, "onComplete", modId + ":COMPLETE");

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates an {@code IAprismMod} implementation whose
     * {@code onInitialize} throws a {@link RuntimeException}.
     *
     * @param className the binary class name (dot form)
     * @param message   the exception message
     * @return the class bytes
     */
    public static byte[] generateThrowingMod(String className, String message) {
        String internalName = className.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null,
                "java/lang/Object", new String[]{"com/aprism/api/IAprismMod"});

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        generatePhaseMethod(cw, "onPreInitialize", "throwing:PREINIT");

        // public void onInitialize(AprismContext) { throw new RuntimeException(message); }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize",
                "(Lcom/aprism/api/AprismContext;)V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(message);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException",
                "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(2, 2);
        mv.visitEnd();

        generatePhaseMethod(cw, "onSetup", "throwing:SETUP");
        generatePhaseMethod(cw, "onComplete", "throwing:COMPLETE");

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void generatePhaseMethod(ClassWriter cw, String methodName, String event) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName,
                "(Lcom/aprism/api/AprismContext;)V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(event);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/aprism/prismate/testsupport/PhaseRecorder", "record",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
    }
}
