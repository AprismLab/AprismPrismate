package com.aprism.prismate.runtime;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.aprism.prismate.testsupport.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests proving the access widener is applied at class-load time
 * through {@link PrismateModClassLoader}: a jar whose class declares a private
 * final field becomes a class whose field is public and non-final once a
 * matching widener rule is registered (v26.0-Alpha.4 parity hardening).
 *
 * <p>These complement {@code PrismateAccessWidenerTest}, which covers rule
 * parsing and the raw ASM transform in isolation; here the full load path
 * (jar -&gt; classloader -&gt; widener -&gt; defined class) is exercised.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateModClassLoader access widening")
class PrismateModClassLoaderWidenerTest {

    @TempDir
    Path tempDir;

    private static final String CLASS_NAME = "com.test.widened.Holder";
    private static final String SLASHED = CLASS_NAME.replace('.', '/');

    /** Generates a class with a {@code private final int secret} field. */
    private static byte[] holderClassBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, SLASHED, null,
                "java/lang/Object", null);
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "secret", "I", null, null);
        fv.visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private Path writeHolderJar(String fileName) throws Exception {
        byte[] jar = TestFixtures.jarBytes(java.util.Map.of(SLASHED + ".class", holderClassBytes()));
        Path jarPath = tempDir.resolve(fileName);
        java.nio.file.Files.write(jarPath, jar);
        return jarPath;
    }

    @Test
    @DisplayName("widens a private final field to public non-final at load time")
    void widensFieldAtLoadTime() throws Exception {
        PrismateModClassLoader loader = new PrismateModClassLoader(getClass().getClassLoader());
        loader.addJar(writeHolderJar("holder.jar"));
        loader.getWidener().parse(List.of(
                "accessWidener v1 named",
                "accessible field " + SLASHED + " secret I",
                "mutable field " + SLASHED + " secret I"));

        Class<?> holder = loader.loadClass(CLASS_NAME);
        java.lang.reflect.Field secret = holder.getDeclaredField("secret");

        assertThat(java.lang.reflect.Modifier.isPublic(secret.getModifiers()))
                .as("accessible rule makes the field public").isTrue();
        assertThat(java.lang.reflect.Modifier.isFinal(secret.getModifiers()))
                .as("mutable rule strips final").isFalse();
        loader.close();
    }

    @Test
    @DisplayName("leaves unrelated classes untouched at load time")
    void passthroughUnrelatedAtLoadTime() throws Exception {
        PrismateModClassLoader loader = new PrismateModClassLoader(getClass().getClassLoader());
        loader.addJar(writeHolderJar("holder-plain.jar"));
        // Register a rule for a DIFFERENT class, not the holder.
        loader.getWidener().parse(List.of(
                "accessWidener v1 named",
                "accessible field com/test/other/Elsewhere x I"));

        Class<?> holder = loader.loadClass(CLASS_NAME);
        java.lang.reflect.Field secret = holder.getDeclaredField("secret");

        assertThat(java.lang.reflect.Modifier.isPrivate(secret.getModifiers()))
                .as("no matching rule keeps the field private").isTrue();
        assertThat(java.lang.reflect.Modifier.isFinal(secret.getModifiers()))
                .as("no matching rule keeps the field final").isTrue();
        loader.close();
    }

    @Test
    @DisplayName("loader without any widener rules loads classes unmodified")
    void noRulesAtAll() throws Exception {
        PrismateModClassLoader loader = new PrismateModClassLoader(getClass().getClassLoader());
        loader.addJar(writeHolderJar("holder-norules.jar"));

        Class<?> holder = loader.loadClass(CLASS_NAME);
        java.lang.reflect.Field secret = holder.getDeclaredField("secret");

        assertThat(java.lang.reflect.Modifier.isPrivate(secret.getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isFinal(secret.getModifiers())).isTrue();
        loader.close();
    }
}
