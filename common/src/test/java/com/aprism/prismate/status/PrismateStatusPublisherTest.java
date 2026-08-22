package com.aprism.prismate.status;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * Tests for the machine-readable bridge status publisher (v26.6-Alpha.1
 * upstream alignment): snapshot shape, atomic publish, fail-safe IO, and
 * the schema contract shared with the Aprism agent's StatusPublisher.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateStatusPublisher")
class PrismateStatusPublisherTest {

    @TempDir
    Path tempDir;

    private static final Gson GSON = new Gson();

    private PrismateLoadReport sampleReport() {
        PrismateLoadReport report = new PrismateLoadReport();
        report.recordOk("extraction", "examplemod", "Example Mod", "1.0.0", 22);
        report.recordOk("classpath", "examplemod", "Example Mod", "1.0.0", 2);
        report.recordFailure("extraction", "badmod", null, "1.0.0", 5, "broken jar");
        return report;
    }

    @Nested
    @DisplayName("Snapshot shape")
    class SnapshotShape {

        @Test
        @DisplayName("carries the shared schema, publisher tag, and phase")
        void schemaAndIdentity() {
            Map<String, Object> snap = PrismateStatusPublisher.buildSnapshot(
                    "v26.6-Alpha.1", "v26.7", "Fa", "26.2", "LOADED", null);

            assertThat(snap.get("schemaVersion")).isEqualTo("aprism.status/v1");
            assertThat(snap.get("publisher")).isEqualTo("prismate");
            assertThat(snap.get("prismateVersion")).isEqualTo("v26.6-Alpha.1");
            assertThat(snap.get("aprismVersion")).isEqualTo("v26.7");
            assertThat(snap.get("loaderKey")).isEqualTo("Fa");
            assertThat(snap.get("mcEdit")).isEqualTo("JE");
            assertThat(snap.get("mcVersion")).isEqualTo("26.2");
            assertThat(snap.get("phase")).isEqualTo("LOADED");
            assertThat(snap.get("generatedAt")).asString().isNotBlank();
        }

        @Test
        @DisplayName("null-safe on every string field")
        void nullSafe() {
            Map<String, Object> snap = PrismateStatusPublisher.buildSnapshot(
                    null, null, null, null, null, null);

            assertThat(snap.get("aprismVersion")).isEqualTo("");
            assertThat(snap.get("phase")).isEqualTo("");
            assertThat(snap.get("units")).asList().isEmpty();
        }

        @Test
        @DisplayName("units mirror report entries with ok/failure counts")
        void unitsFromReport() {
            Map<String, Object> snap = PrismateStatusPublisher.buildSnapshot(
                    "v", "a", "N", "26.2", "LOADED", sampleReport());

            assertThat(snap.get("okCount")).isEqualTo(2);
            assertThat(snap.get("failureCount")).isEqualTo(1);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> units =
                    (List<Map<String, Object>>) (Object) snap.get("units");
            assertThat(units).hasSize(3);
            assertThat(units.get(0))
                    .containsEntry("kind", "extraction")
                    .containsEntry("id", "examplemod")
                    .containsEntry("state", "OK")
                    .containsEntry("durationMs", 22L);
            assertThat(units.get(2))
                    .containsEntry("state", "FAILED")
                    .containsEntry("failure", "broken jar");
        }
    }

    @Nested
    @DisplayName("Publish")
    class Publish {

        @Test
        @DisplayName("writes valid JSON atomically and round-trips")
        void publishRoundTrip() throws Exception {
            Map<String, Object> snap = PrismateStatusPublisher.buildSnapshot(
                    "v", "a", "Fa", "26.2", "LOADED", sampleReport());

            Path published = PrismateStatusPublisher.publish(tempDir, snap);

            assertThat(published).isNotNull();
            assertThat(published.getFileName().toString())
                    .isEqualTo(PrismateStatusPublisher.FILE_NAME);
            assertThat(Files.exists(tempDir.resolve(
                    PrismateStatusPublisher.FILE_NAME + ".tmp"))).isFalse();

            Map<String, Object> readBack = GSON.fromJson(Files.readString(published),
                    new TypeToken<Map<String, Object>>() { }.getType());
            assertThat(readBack.get("schemaVersion")).isEqualTo("aprism.status/v1");
            assertThat(readBack.get("publisher")).isEqualTo("prismate");
            assertThat(readBack.get("okCount")).isEqualTo(2.0);
        }

        @Test
        @DisplayName("republish overwrites the previous snapshot")
        void republishOverwrites() throws Exception {
            Path first = PrismateStatusPublisher.publish(tempDir,
                    PrismateStatusPublisher.buildSnapshot("v", "a", "Fa", "26.2", "LOADED", null));
            Path second = PrismateStatusPublisher.publish(tempDir,
                    PrismateStatusPublisher.buildSnapshot("v", "a", "Fa", "26.2", "SHUTDOWN", null));

            assertThat(first).isEqualTo(second);
            Map<String, Object> readBack = GSON.fromJson(Files.readString(second),
                    new TypeToken<Map<String, Object>>() { }.getType());
            assertThat(readBack.get("phase")).isEqualTo("SHUTDOWN");
        }

        @Test
        @DisplayName("fail-safe: null inputs and unwritable targets never throw")
        void failSafe() {
            assertThat(PrismateStatusPublisher.publish(null, Map.of())).isNull();
            assertThat(PrismateStatusPublisher.publish(tempDir, null)).isNull();

            Path fileAsDir = tempDir.resolve("not-a-dir");
            assertThatCode(() -> Files.writeString(fileAsDir, "x")).doesNotThrowAnyException();
            assertThatCode(() -> PrismateStatusPublisher.publish(fileAsDir,
                    PrismateStatusPublisher.buildSnapshot("v", "a", "Fa", "26.2", "LOADED", null)))
                    .doesNotThrowAnyException();
        }
    }
}
