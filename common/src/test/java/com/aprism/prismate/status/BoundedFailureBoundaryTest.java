package com.aprism.prismate.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aprism.prismate.runtime.PrismateLoadReport;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Boundary adversarial pass for the v26.9 bounded failure payloads: exact
 * bound lengths and multi-byte character (surrogate pair) truncation.
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("Bounded failure payloads - boundaries")
class BoundedFailureBoundaryTest {

    @TempDir
    Path tempDir;

    private static final Gson GSON = new Gson();

    private PrismateLoadReport reportWith(String failure) {
        PrismateLoadReport report = new PrismateLoadReport();
        report.recordFailure("lifecycle", "m", null, "1.0.0", 0, failure);
        return report;
    }

    @SuppressWarnings("unchecked")
    private String publishedFailure(Map<String, Object> snap) throws Exception {
        Path published = PrismateStatusPublisher.publish(tempDir, snap);
        Map<String, Object> readBack = GSON.fromJson(Files.readString(published),
                new TypeToken<Map<String, Object>>() { }.getType());
        List<Map<String, Object>> units =
                (List<Map<String, Object>>) (Object) readBack.get("units");
        return (String) units.get(0).get("failure");
    }

    @Test
    @DisplayName("a string exactly at the bound is NOT truncated")
    void exactBoundNotTruncated() throws Exception {
        StringBuilder exact = new StringBuilder();
        while (exact.length() < PrismateStatusPublisher.MAX_FAILURE_CHARS) {
            exact.append('x');
        }
        String failure = publishedFailure(PrismateStatusPublisher.buildSnapshot(
                "v", "a", "Fa", "26.2", "LOADED", reportWith(exact.toString())));
        assertThat(failure).doesNotContain("[truncated").hasSize(exact.length());
    }

    @Test
    @DisplayName("truncation never splits a surrogate pair")
    void truncationRespectsSurrogatePairs() throws Exception {
        // Build a string whose MAX_FAILURE_CHARS boundary falls inside an
        // emoji (surrogate pair = 2 chars).
        StringBuilder sb = new StringBuilder();
        while (sb.length() < PrismateStatusPublisher.MAX_FAILURE_CHARS - 1) {
            sb.append('y');
        }
        sb.append("\ud83d\ude00"); // 😀 at positions straddling the bound
        sb.append("tail");

        assertThatCode(() -> {
            PrismateStatusPublisher.buildSnapshot(
                    "v", "a", "Fa", "26.2", "LOADED", reportWith(sb.toString()));
        }).doesNotThrowAnyException();

        String failure = publishedFailure(PrismateStatusPublisher.buildSnapshot(
                "v", "a", "Fa", "26.2", "LOADED", reportWith(sb.toString())));
        // The published text must be valid UTF-8 round-trip: no lone surrogates.
        assertThatCode(() -> failure.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .doesNotThrowAnyException();
        boolean hasLoneSurrogate = false;
        for (int i = 0; i < failure.length(); i++) {
            char c = failure.charAt(i);
            if (Character.isHighSurrogate(c)
                    && (i + 1 >= failure.length()
                        || !Character.isLowSurrogate(failure.charAt(i + 1)))) {
                hasLoneSurrogate = true;
            }
            if (Character.isLowSurrogate(c)
                    && (i == 0 || !Character.isHighSurrogate(failure.charAt(i - 1)))) {
                hasLoneSurrogate = true;
            }
        }
        assertThat(hasLoneSurrogate)
                .as("truncated text must not contain isolated surrogate halves")
                .isFalse();
    }
}
