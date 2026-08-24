package com.aprism.prismate.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.aprism.prismate.runtime.PrismateLoadReport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Publishes a machine-readable bridge status file for external tools
 * (v26.6-Alpha.1 upstream alignment, mirroring Aprism core's
 * {@code StatusPublisher} from v26.6-Alpha.2 MDL deep integration).
 *
 * <p>After the load report is logged the bridge writes
 * {@code <gameDir>/aprism-status.json} (schema {@code aprism.status/v1},
 * the SAME schema and file name as the Aprism agent: the agent and Prismate
 * are mutually exclusive in one instance, so there is exactly one publisher
 * per game root and external tooling such as MDL diagnose reads one file
 * regardless of which loader published it). A {@code publisher} field names
 * this bridge; boot refusals publish their outcome as the phase so a failed
 * boot is machine-diagnosable instead of log-only.
 *
 * <p>Publishing is fail-safe: IO errors are logged at FINE level and
 * swallowed so a read-only or missing game directory never breaks the boot.
 * The file is written atomically (temp file + move) so concurrent readers
 * never observe a half-written document.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateStatusPublisher {

    /** The logger for fail-safe IO diagnostics. */
    private static final Logger LOG = Logger.getLogger("prismate.status");

    /** The schema identifier written into every published document. */
    public static final String SCHEMA_VERSION = "aprism.status/v1";

    /** The fixed file name published under the game directory. */
    public static final String FILE_NAME = "aprism-status.json";

    /**
     * Upper bound for per-unit failure text in the machine-readable document
     * (v26.9-Alpha.1). Full, untruncated chains remain in
     * {@code prismate/reports/load-report.txt}; the status file stays bounded
     * no matter how deep a mod's exception chain runs.
     */
    public static final int MAX_FAILURE_CHARS = 512;

    /** Shared Gson instance; the document is small so pretty printing is free. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PrismateStatusPublisher() {
    }

    /**
     * Builds a status snapshot from the bridge state.
     *
     * @param prismateVersion  the running Prismate version (may be null)
     * @param aprismVersion    the embedded Aprism version (may be null)
     * @param loaderKey        the host loader key ({@code Fa}/{@code N}/...)
     * @param mcVersion        the running Minecraft version (may be null)
     * @param phase            the lifecycle phase label (e.g. {@code LOADED},
     *                         {@code SHUTDOWN}, or a boot outcome name for
     *                         refused boots)
     * @param report           the load report with per-unit outcomes (may be
     *                         null before the pipeline ran)
     * @return the snapshot ready to publish
     */
    public static Map<String, Object> buildSnapshot(String prismateVersion, String aprismVersion,
            String loaderKey, String mcVersion, String phase, PrismateLoadReport report) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", SCHEMA_VERSION);
        doc.put("publisher", "prismate");
        doc.put("aprismVersion", aprismVersion == null ? "" : aprismVersion);
        doc.put("prismateVersion", prismateVersion == null ? "" : prismateVersion);
        doc.put("loaderKey", loaderKey == null ? "" : loaderKey);
        doc.put("mcEdit", "JE");
        doc.put("mcVersion", mcVersion == null ? "" : mcVersion);
        doc.put("generatedAt", Instant.now().toString());
        doc.put("phase", phase == null ? "" : phase);

        List<Map<String, Object>> units = new ArrayList<>();
        int okCount = 0;
        int failureCount = 0;
        if (report != null) {
            for (PrismateLoadReport.Entry entry : report.entries()) {
                Map<String, Object> unit = new LinkedHashMap<>();
                unit.put("kind", entry.stage());
                unit.put("id", entry.id() == null ? "" : entry.id());
                unit.put("version", entry.version() == null ? "" : entry.version());
                unit.put("loaderKey", loaderKey == null ? "" : loaderKey);
                unit.put("state", entry.status() == null ? "" : entry.status().name());
                if (entry.failure() != null && !entry.failure().isBlank()) {
                    unit.put("failure", bound(entry.failure()));
                }
                units.add(unit);
                if (entry.status() == PrismateLoadReport.Entry.Status.FAILED) {
                    failureCount++;
                } else {
                    okCount++;
                }
                // Enrich durations from the report itself (per-entry).
                if (entry.durationMs() >= 0) {
                    unit.put("durationMs", entry.durationMs());
                }
            }
        }
        doc.put("okCount", okCount);
        doc.put("failureCount", failureCount);
        doc.put("units", units);
        return doc;
    }

    /**
     * Bounds a failure string for the machine-readable document: over-long
     * chains are truncated with an explicit ellipsis marker so tooling can
     * tell truncation from content.
     */
    private static String bound(String failure) {
        if (failure.length() <= MAX_FAILURE_CHARS) {
            return failure;
        }
        return failure.substring(0, MAX_FAILURE_CHARS)
                + "...[truncated " + (failure.length() - MAX_FAILURE_CHARS)
                + " chars; full chain in load-report.txt]";
    }

    /**
     * Publishes the status snapshot to {@code <gameDir>/aprism-status.json}.
     * Fail-safe: any IO error is logged at FINE level and swallowed.
     *
     * @param gameDir  the game instance root
     * @param snapshot the snapshot to write
     * @return the published file path, or null when publishing failed or the
     *         game dir is null
     */
    public static Path publish(Path gameDir, Map<String, Object> snapshot) {
        if (gameDir == null || snapshot == null) {
            return null;
        }
        try {
            Files.createDirectories(gameDir);
            Path target = gameDir.resolve(FILE_NAME);
            Path tmp = gameDir.resolve(FILE_NAME + ".tmp");
            String json = GSON.toJson(snapshot);
            Files.writeString(tmp, json);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            // Atomic move can fall back on some filesystems; retry non-atomic.
            try {
                Path target = gameDir.resolve(FILE_NAME);
                Path tmp = gameDir.resolve(FILE_NAME + ".tmp");
                if (Files.exists(tmp)) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    return target;
                }
            } catch (IOException ignored) {
                // fall through to the log below
            }
            LOG.fine("StatusPublisher: failed to publish status: " + e.getMessage());
            return null;
        }
    }
}
