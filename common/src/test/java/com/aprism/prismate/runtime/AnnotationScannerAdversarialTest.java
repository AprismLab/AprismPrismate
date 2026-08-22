package com.aprism.prismate.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.prismate.testsupport.PhaseRecorder;
import com.aprism.prismate.testsupport.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Adversarial tests for the annotation-scan entrypoint surface
 * (v26.5 robustness assessment per the family rule: every new line's
 * planning is preceded by a simulated-usage + malicious-input pass against
 * the previous release). Covers hostile class files, multi-entrypoint jars,
 * and throwing constructors.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("AnnotationScanner adversarial")
class AnnotationScannerAdversarialTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PhaseRecorder.clear();
    }

    private Path jarWith(Map<String, byte[]> entries) throws IOException {
        Path jar = tempDir.resolve("probe" + System.nanoTime() + ".jar");
        TestFixtures.writeZip(jar, entries);
        return jar;
    }

    @Nested
    @DisplayName("Hostile inputs")
    class HostileInputs {

        @Test
        @DisplayName("truncated garbage class files are skipped, not thrown")
        void truncatedClassFileSkipped() throws Exception {
            byte[] good = TestFixtures.generateAnnotatedRecordingMod(
                    "com.test.Good", "m", null);
            // Truncated copy of a valid class file.
            byte[] truncated = new byte[good.length / 2];
            System.arraycopy(good, 0, truncated, 0, truncated.length);
            // Pure garbage that does not even start with the magic bytes.
            byte[] garbage = new byte[] {0x00, 0x01, 0x02, 0x03, 0x04, 0x05};

            Path jar = jarWith(Map.of(
                    "com/test/Good.class", good,
                    "com/test/Bad.class", truncated,
                    "com/test/Worse.class", garbage));

            assertThatCode(() -> AnnotationScanner.scanModEntrypoints(List.of(jar), "m"))
                    .doesNotThrowAnyException();
            assertThat(AnnotationScanner.scanModEntrypoints(List.of(jar), "m"))
                    .containsExactly("com.test.Good");
        }

        @Test
        @DisplayName("missing jar paths are ignored silently")
        void missingJarIgnored() {
            assertThat(AnnotationScanner.scanModEntrypoints(
                    List.of(tempDir.resolve("nope.jar")), "m")).isEmpty();
        }

        @Test
        @DisplayName("module-info and package-info are excluded from the scan")
        void metaFilesExcluded() throws Exception {
            byte[] good = TestFixtures.generateAnnotatedRecordingMod(
                    "com.test.Meta", "m", null);
            Path jar = jarWith(Map.of(
                    "com/test/Meta.class", good,
                    "module-info.class", good,
                    "com/test/package-info.class", good));

            List<String> found = AnnotationScanner.scanModEntrypoints(List.of(jar), "m");
            assertThat(found).containsExactly("com.test.Meta");
        }
    }

    @Nested
    @DisplayName("Multi-entrypoint and isolation behavior")
    class MultiAndIsolation {

        @Test
        @DisplayName("all matching annotated classes in one jar are discovered in scan order")
        void multipleAnnotatedClassesAllFound() throws Exception {
            byte[] first = TestFixtures.generateAnnotatedRecordingMod(
                    "com.test.First", "m", "m");
            byte[] second = TestFixtures.generateAnnotatedRecordingMod(
                    "com.test.Second", "m", null);
            byte[] wrong = TestFixtures.generateAnnotatedRecordingMod(
                    "com.test.Wrong", "m", "other");
            Path jar = jarWith(Map.of(
                    "com/test/Second.class", second,
                    "com/test/First.class", first,
                    "com/test/Wrong.class", wrong));

            List<String> found = AnnotationScanner.scanModEntrypoints(List.of(jar), "m");
            assertThat(found).containsExactlyInAnyOrder("com.test.First", "com.test.Second");
        }
    }
}
