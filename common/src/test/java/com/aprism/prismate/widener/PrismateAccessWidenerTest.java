package com.aprism.prismate.widener;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PrismateAccessWidener}: Fabric-style rule parsing and the
 * ASM widening pass applied by the Prismate-managed classloader.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateAccessWidener")
class PrismateAccessWidenerTest {

    private static final String TARGET = "com/test/widened/Secret";

    /** Generates a class with private members to widen. */
    private static byte[] secretClassBytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, TARGET, null,
                "java/lang/Object", null);
        // private final int secretField
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                "secretField", "I", null, null);
        fv.visitEnd();
        // private void secretMethod()
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "secretMethod", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        // ctor
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

    /** Reads the access flags of a class, field, or method. */
    private static int[] readFlags(byte[] bytes) {
        int[] flags = new int[3]; // class, field, method
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                flags[0] = access;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                    String signature, Object value) {
                if (name.equals("secretField")) {
                    flags[1] = access;
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (name.equals("secretMethod")) {
                    flags[2] = access;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        return flags;
    }

    @Nested
    @DisplayName("Parsing")
    class Parsing {

        @Test
        @DisplayName("parses a valid v1 widener with all rule kinds")
        void parsesValidWidener() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            widener.parse(List.of(
                    "accessWidener v1 named",
                    "# comment",
                    "",
                    "accessible class com/test/widened/Secret",
                    "accessible field com/test/widened/Secret secretField I",
                    "mutable field com/test/widened/Secret secretField I",
                    "extendable method com/test/widened/Secret secretMethod ()V"));
            assertThat(widener.ruleCount()).isEqualTo(4);
            assertThat(widener.hasRulesFor(TARGET)).isTrue();
            assertThat(widener.hasRulesFor("com/test/Other")).isFalse();
        }

        @Test
        @DisplayName("rejects a missing header")
        void rejectsMissingHeader() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            assertThatThrownBy(() -> widener.parse(List.of("accessible class foo/Bar")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("accessWidener");
        }

        @Test
        @DisplayName("rejects an unknown verb")
        void rejectsUnknownVerb() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            assertThatThrownBy(() -> widener.parse(List.of(
                    "accessWidener v1 named", "banana class foo/Bar")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("banana");
        }

        @Test
        @DisplayName("rejects mutable on a method")
        void rejectsMutableMethod() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            assertThatThrownBy(() -> widener.parse(List.of(
                    "accessWidener v1 named",
                    "mutable method foo/Bar doIt ()V")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mutable");
        }

        @Test
        @DisplayName("rejects a truncated rule")
        void rejectsTruncatedRule() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            assertThatThrownBy(() -> widener.parse(List.of(
                    "accessWidener v1 named", "accessible field foo/Bar")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Transformation")
    class Transformation {

        @Test
        @DisplayName("accessible makes a private field public")
        void accessibleField() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            widener.parse(List.of(
                    "accessWidener v1 named",
                    "accessible field " + TARGET + " secretField I"));
            int[] flags = readFlags(widener.transform(TARGET, secretClassBytes()));
            assertThat(flags[1] & Opcodes.ACC_PUBLIC).isNotZero();
            assertThat(flags[1] & Opcodes.ACC_PRIVATE).isZero();
        }

        @Test
        @DisplayName("mutable strips final from a field")
        void mutableField() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            widener.parse(List.of(
                    "accessWidener v1 named",
                    "mutable field " + TARGET + " secretField I"));
            int[] flags = readFlags(widener.transform(TARGET, secretClassBytes()));
            assertThat(flags[1] & Opcodes.ACC_FINAL).isZero();
            // mutable does not change visibility
            assertThat(flags[1] & Opcodes.ACC_PRIVATE).isNotZero();
        }

        @Test
        @DisplayName("accessible makes a private method public")
        void accessibleMethod() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            widener.parse(List.of(
                    "accessWidener v1 named",
                    "accessible method " + TARGET + " secretMethod ()V"));
            int[] flags = readFlags(widener.transform(TARGET, secretClassBytes()));
            assertThat(flags[2] & Opcodes.ACC_PUBLIC).isNotZero();
            assertThat(flags[2] & Opcodes.ACC_PRIVATE).isZero();
        }

        @Test
        @DisplayName("extendable makes a final class protected and non-final")
        void extendableClass() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            widener.parse(List.of(
                    "accessWidener v1 named",
                    "extendable class " + TARGET));
            int[] flags = readFlags(widener.transform(TARGET, secretClassBytes()));
            assertThat(flags[0] & Opcodes.ACC_FINAL).isZero();
            assertThat(flags[0] & Opcodes.ACC_PROTECTED).isNotZero();
        }

        @Test
        @DisplayName("extendable makes a private method protected and non-final")
        void extendableMethod() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            widener.parse(List.of(
                    "accessWidener v1 named",
                    "extendable method " + TARGET + " secretMethod ()V"));
            int[] flags = readFlags(widener.transform(TARGET, secretClassBytes()));
            assertThat(flags[2] & Opcodes.ACC_PROTECTED).isNotZero();
            assertThat(flags[2] & Opcodes.ACC_PRIVATE).isZero();
        }

        @Test
        @DisplayName("passes classes without rules through untouched")
        void passthroughWithoutRules() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            byte[] original = secretClassBytes();
            assertThat(widener.transform(TARGET, original)).isSameAs(original);
        }

        @Test
        @DisplayName("combined rules widen field and method together")
        void combinedRules() {
            PrismateAccessWidener widener = new PrismateAccessWidener();
            widener.parse(List.of(
                    "accessWidener v1 named",
                    "accessible field " + TARGET + " secretField I",
                    "mutable field " + TARGET + " secretField I",
                    "accessible method " + TARGET + " secretMethod ()V"));
            int[] flags = readFlags(widener.transform(TARGET, secretClassBytes()));
            assertThat(flags[1] & Opcodes.ACC_PUBLIC).isNotZero();
            assertThat(flags[1] & Opcodes.ACC_FINAL).isZero();
            assertThat(flags[2] & Opcodes.ACC_PUBLIC).isNotZero();
        }
    }
}
