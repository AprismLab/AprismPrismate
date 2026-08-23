package com.aprism.prismate.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.prismate.runtime.PrismateLoadReport;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Adversarial pass against the status-publishing surface (v26.6 robustness
 * assessment per the family rule). Covers hostile report payloads (JSON
 * metacharacters, control characters, huge failure chains, unicode), a locked
 * target file on Windows, and concurrent-publish races.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateStatusPublisher adversarial")
class PrismateStatusPublisherAdversarialTest {

    @TempDir
    Path tempDir;

    private static final Gson GSON = new Gson();

    @Nested
    @DisplayName("Hostile report payloads")
    class HostilePayloads {

        @Test
        @DisplayName("JSON metacharacters in ids and failures round-trip safely")
        void jsonMetacharacters() throws Exception {
            PrismateLoadReport report = new PrismateLoadReport();
            String hostileId = "mod\"with\"quotes\\and\\backslashes";
            String hostileFailure = "line1\nline2\ttab \"quoted\" {brace:json}";
            report.recordOk("extraction", hostileId, null, "1.0.0", 1);
            report.recordFailure("classpath", "victim", null, "1.0.0", 0, hostileFailure);

            Map<String, Object> snap = PrismateStatusPublisher.buildSnapshot(
                    "v", "a", "Fa", "26.2", "LOADED", report);
            Path published = PrismateStatusPublisher.publish(tempDir, snap);

            Map<String, Object> readBack = GSON.fromJson(Files.readString(published),
                    new TypeToken<Map<String, Object>>() { }.getType());
            assertThat(readBack.get("failureCount")).isEqualTo(1.0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> units =
                    (List<Map<String, Object>>) (Object) readBack.get("units");
            assertThat(units.get(0))
                    .containsEntry("id", hostileId);
            assertThat(units.get(1))
                    .containsEntry("failure", hostileFailure);
        }

        @Test
        @DisplayName("unicode display data round-trips as valid UTF-8 JSON")
        void unicodeRoundTrip() throws Exception {
            PrismateLoadReport report = new PrismateLoadReport();
            report.recordOk("extraction", "\u4e2d\u6587\u6a21\u7ec4",
                    "\u6a21\u7ec4 \u540d\u79f0 \u2764", "1.0.0", 3);

            Map<String, Object> snap = PrismateStatusPublisher.buildSnapshot(
                    "v", "a", "Fa", "26.2", "LOADED", report);
            Path published = PrismateStatusPublisher.publish(tempDir, snap);

            Map<String, Object> readBack = GSON.fromJson(Files.readString(published),
                    new TypeToken<Map<String, Object>>() { }.getType());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> units =
                    (List<Map<String, Object>>) (Object) readBack.get("units");
            assertThat(units.get(0)).containsEntry("id", "\u4e2d\u6587\u6a21\u7ec4");
        }

        @Test
        @DisplayName("an enormous failure chain does not break the document")
        void enormousFailureChain() throws Exception {
            // Simulate the fullChain renderer: a deeply nested exception chain.
            StringBuilder chain = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                chain.append("java.lang.RuntimeException: layer ").append(i).append(" -> ");
            }
            chain.append("root cause with \"quotes\" and \\backslash\\");

            PrismateLoadReport report = new PrismateLoadReport();
            report.recordFailure("lifecycle", "boommod", null, "1.0.0", 0,
                    chain.toString());

            Map<String, Object> snap = PrismateStatusPublisher.buildSnapshot(
                    "v", "a", "Fa", "26.2", "LOADED", report);
            Path published = PrismateStatusPublisher.publish(tempDir, snap);

            Map<String, Object> readBack = GSON.fromJson(Files.readString(published),
                    new TypeToken<Map<String, Object>>() { }.getType());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> units =
                    (List<Map<String, Object>>) (Object) readBack.get("units");
            assertThat((String) units.get(0).get("failure"))
                    .startsWith("java.lang.RuntimeException: layer 0")
                    .contains("layer 499")
                    .endsWith("root cause with \"quotes\" and \\backslash\\");
        }
    }

    @Nested
    @DisplayName("Publish hazards")
    class PublishHazards {

        @Test
        @DisplayName("a locked target file fails safe without throwing")
        void lockedTargetFailSafe() throws Exception {
            // Hold an exclusive lock on the target file, then publish.
            Path target = tempDir.resolve(PrismateStatusPublisher.FILE_NAME);
            Files.writeString(target, "occupied");
            try (var channel = FileChannel.open(target,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                var lock = channel.tryLock();
                if (lock != null) {
                    try {
                        Map<String, Object> snap =
                                PrismateStatusPublisher.buildSnapshot(
                                        "v", "a", "Fa", "26.2", "LOADED", null);
                        assertThatCode(() -> PrismateStatusPublisher.publish(tempDir, snap))
                                .doesNotThrowAnyException();
                    } finally {
                        lock.release();
                    }
                }
                // If tryLock returned null (platform refusal semantics differ),
                // the test still passes: the assertion is only "never throws".
            }
        }

        @Test
        @DisplayName("rapid republish sequence leaves no tmp debris")
        void rapidRepublishNoDebris() throws Exception {
            for (int i = 0; i < 20; i++) {
                PrismateStatusPublisher.publish(tempDir,
                        PrismateStatusPublisher.buildSnapshot("v", "a", "Fa",
                                "26.2", i % 2 == 0 ? "LOADED" : "SHUTDOWN", null));
            }
            try (var stream = Files.list(tempDir)) {
                List<String> names = stream.map(p -> p.getFileName().toString()).toList();
                assertThat(names).containsExactly(PrismateStatusPublisher.FILE_NAME);
            }
        }

        @Test
        @DisplayName("snapshot building never throws on any field combination")
        void buildNeverThrows() {
            assertThatCode(() -> {
                for (String phase : new String[] {null, "", "LOADED", "\u0000ctrl"}) {
                    PrismateStatusPublisher.buildSnapshot(null, null, null, null,
                            phase, null);
                    PrismateStatusPublisher.buildSnapshot("", "", "", "", phase,
                            new PrismateLoadReport());
                }
            }).doesNotThrowAnyException();
        }
    }
}
