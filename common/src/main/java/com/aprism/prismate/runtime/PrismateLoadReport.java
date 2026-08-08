package com.aprism.prismate.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Startup load report for a single Prismate boot (mirrors Aprism's
 * {@code LoadReport} shape and rendering). Collects the outcome of every pack
 * processed during discovery, extraction, resolution, and lifecycle dispatch,
 * distinguishing successful loads from isolated failures so the user can see
 * exactly what loaded and what did not.
 *
 * <p>A single pack failure is isolated: it is recorded here and in the log,
 * but does not abort the rest of the boot (docs 01 principle 5).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateLoadReport {

    /** A single processed pack with its outcome. */
    public record Entry(String stage, String id, String version, Status status, long durationMs, String failure) {
        /** Load outcome. */
        public enum Status { OK, FAILED }
    }

    private final long startNanos = System.nanoTime();
    private final List<Entry> entries = new ArrayList<>();

    /**
     * Records a successfully processed pack.
     *
     * @param stage      the pipeline stage (discovery/extraction/lifecycle)
     * @param id         the mod id
     * @param version    the mod version (may be {@code null})
     * @param durationMs the time taken, in milliseconds
     */
    public void recordOk(String stage, String id, String version, long durationMs) {
        entries.add(new Entry(stage, id, version, Entry.Status.OK, durationMs, null));
    }

    /**
     * Records an isolated pack failure.
     *
     * @param stage      the pipeline stage
     * @param id         the mod id (may be {@code null} when unknown)
     * @param version    the mod version (may be {@code null})
     * @param durationMs the time spent before the failure, in milliseconds
     * @param failure    a short human-readable failure description
     */
    public void recordFailure(String stage, String id, String version, long durationMs, String failure) {
        entries.add(new Entry(stage, id, version, Entry.Status.FAILED, durationMs, failure));
    }

    /**
     * @return all recorded entries, in processing order
     */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * @return the number of successfully processed packs
     */
    public long okCount() {
        return entries.stream().filter(e -> e.status() == Entry.Status.OK).count();
    }

    /**
     * @return the number of failed packs
     */
    public long failureCount() {
        return entries.stream().filter(e -> e.status() == Entry.Status.FAILED).count();
    }

    /**
     * @return the failed entries only
     */
    public List<Entry> failures() {
        return entries.stream().filter(e -> e.status() == Entry.Status.FAILED).toList();
    }

    /**
     * @return total boot time so far, in milliseconds
     */
    public long totalMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Renders the report as a plain-text summary block for the game log.
     *
     * @param prismateVersion the running Prismate version
     * @param hostDescription the host loader description (e.g. {@code Fabric 0.16.14})
     * @return the summary text
     */
    public String toSummary(String prismateVersion, String hostDescription) {
        StringBuilder sb = new StringBuilder();
        sb.append("==== AprismPrismate Load Report (").append(prismateVersion)
                .append(" on ").append(hostDescription).append(") ====\n");
        sb.append("Total boot: ").append(totalMs()).append(" ms\n");
        sb.append("Loaded ").append(okCount()).append(", failed ").append(failureCount()).append('\n');
        for (Entry e : entries) {
            sb.append("  [").append(e.status() == Entry.Status.OK ? "OK  " : "FAIL")
                    .append("] ").append(e.stage()).append(' ');
            sb.append(e.id() != null ? e.id() : "?");
            if (e.version() != null && !e.version().isBlank()) {
                sb.append(' ').append(e.version());
            }
            sb.append(" (").append(e.durationMs()).append(" ms)");
            if (e.failure() != null) {
                sb.append(" -> ").append(e.failure());
            }
            sb.append('\n');
        }
        sb.append("==== End AprismPrismate Load Report ====");
        return sb.toString();
    }
}
