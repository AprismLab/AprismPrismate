package com.aprism.prismate.widener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Prismate-side access widener (v26.0-Alpha.4 parity hardening).
 *
 * <p>Host loaders apply access wideners only to mods they discovered
 * themselves; jars injected at runtime get no widener pass from Fabric or
 * NeoForge. Prismate therefore applies the Fabric-style {@code accessWidener
 * v1} rules itself when classes load through its managed classloader, mirroring
 * the semantics of Aprism core's AccessWidener (accessible/extendable/mutable
 * on class/method/field).
 *
 * <p>Rules are indexed by slashed class name for O(1) lookup during
 * transformation. Classes with no rules pass through untouched (no ASM
 * read/write overhead).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateAccessWidener {

    /** Widening rule kinds. */
    public enum Kind { ACCESSIBLE, EXTENDABLE, MUTABLE }

    /**
     * A single widening rule.
     *
     * @param kind   the rule kind
     * @param owner  the slashed owner class name
     * @param name   the member name (may be {@code null} for class rules)
     * @param desc   the member descriptor (may be {@code null} for class rules)
     */
    public record WidenerRule(Kind kind, String owner, String name, String desc) {
    }

    private final Map<String, List<WidenerRule>> rulesByClass = new HashMap<>();

    /**
     * @return the total number of rules registered
     */
    public int ruleCount() {
        return rulesByClass.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Whether any rules target the given slashed class name.
     *
     * @param slashedClassName the slashed class name
     * @return true when rules exist for the class
     */
    public boolean hasRulesFor(String slashedClassName) {
        return rulesByClass.containsKey(slashedClassName);
    }

    /**
     * Parses an access widener file in the Fabric {@code accessWidener v1}
     * format. Blank lines and {@code #} comments are ignored.
     *
     * @param lines the widener text
     * @throws IllegalArgumentException when the header or a rule is malformed
     */
    public void parse(List<String> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("access widener is empty");
        }
        String header = lines.get(0).trim();
        if (!header.startsWith("accessWidener")) {
            throw new IllegalArgumentException(
                    "access widener must start with an 'accessWidener' header, got: " + header);
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 3) {
                throw new IllegalArgumentException("malformed access widener rule: " + line);
            }
            Kind kind = switch (parts[0]) {
                case "accessible" -> Kind.ACCESSIBLE;
                case "extendable" -> Kind.EXTENDABLE;
                case "mutable" -> Kind.MUTABLE;
                default -> throw new IllegalArgumentException(
                        "unknown access widener verb: " + parts[0]);
            };
            String targetType = parts[1];
            switch (targetType) {
                case "class" -> addRule(new WidenerRule(kind, parts[2], null, null));
                case "method" -> {
                    require(kind != Kind.MUTABLE, "mutable does not apply to methods: " + line);
                    require(parts.length >= 5, "method rule needs owner name desc: " + line);
                    addRule(new WidenerRule(kind, parts[2], parts[3], parts[4]));
                }
                case "field" -> {
                    require(parts.length >= 5, "field rule needs owner name desc: " + line);
                    addRule(new WidenerRule(kind, parts[2], parts[3], parts[4]));
                }
                default -> throw new IllegalArgumentException(
                        "unknown access widener target type: " + targetType);
            }
        }
    }

    /**
     * Reads and parses a widener file from disk.
     *
     * @param widenerFile the widener file path
     * @throws IOException when the file cannot be read
     */
    public void parseFile(Path widenerFile) throws IOException {
        try (InputStream in = Files.newInputStream(widenerFile);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            parse(lines);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void addRule(WidenerRule rule) {
        rulesByClass.computeIfAbsent(rule.owner(), k -> new ArrayList<>()).add(rule);
    }

    /**
     * Applies the registered rules to a class's bytes. Returns the original
     * array unchanged when no rules target the class.
     *
     * @param className      the slashed class name (for rule lookup)
     * @param originalBytes  the class bytes
     * @return the transformed (or original) bytes
     */
    public byte[] transform(String className, byte[] originalBytes) {
        List<WidenerRule> rules = rulesByClass.get(className);
        if (rules == null || rules.isEmpty()) {
            return originalBytes;
        }
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new WideningVisitor(writer, rules), 0);
        return writer.toByteArray();
    }

    /**
     * The ASM pass applying widener rules to the visited class.
     */
    private static final class WideningVisitor extends ClassVisitor {

        private final List<WidenerRule> rules;

        private WideningVisitor(ClassVisitor delegate, List<WidenerRule> rules) {
            super(Opcodes.ASM9, delegate);
            this.rules = rules;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            int widened = access;
            for (WidenerRule rule : rules) {
                if (rule.name() != null) {
                    continue; // member rule
                }
                widened = switch (rule.kind()) {
                    case ACCESSIBLE -> makePublic(widened);
                    case EXTENDABLE -> (widened & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_FINAL)
                            | Opcodes.ACC_PROTECTED;
                    case MUTABLE -> widened; // not meaningful for classes
                };
            }
            super.visit(version, widened, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            int widened = access;
            for (WidenerRule rule : rules) {
                if (rule.name() == null || !name.equals(rule.name())
                        || !descriptor.equals(rule.desc())) {
                    continue;
                }
                widened = switch (rule.kind()) {
                    case ACCESSIBLE -> makePublic(widened);
                    case EXTENDABLE -> (widened & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_FINAL)
                            | Opcodes.ACC_PROTECTED;
                    case MUTABLE -> widened; // not meaningful for methods
                };
            }
            return super.visitMethod(widened, name, descriptor, signature, exceptions);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value) {
            int widened = access;
            for (WidenerRule rule : rules) {
                if (rule.name() == null || !name.equals(rule.name())
                        || !descriptor.equals(rule.desc())) {
                    continue;
                }
                widened = switch (rule.kind()) {
                    case ACCESSIBLE -> makePublic(widened);
                    case EXTENDABLE -> (widened & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
                    case MUTABLE -> widened & ~Opcodes.ACC_FINAL;
                };
            }
            return super.visitField(widened, name, descriptor, signature, value);
        }

        private static int makePublic(int access) {
            return (access & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
        }
    }
}
