package com.aprism.prismate.version;

import java.util.List;
import java.util.Optional;

/**
 * The JE version line Prismate supports, mirrored from the Aprism core
 * {@code VersionLineRegistry} (Aprism v26.1-Alpha.7, goal #1). The line spans
 * JE {@code 1.20} through {@code 26.2} and is expressed as contiguous
 * segments, each covering a {@code major.minor} prefix with a fixed
 * obfuscation profile and Java baseline:
 *
 * <ul>
 *   <li>{@code 1.20.x} — obfuscated line, Java 17, Intermediary remapping is
 *       the Aprism agent's job (Prismate loads packs as-is)</li>
 *   <li>{@code 1.21.x} — obfuscated line, Java 21, Intermediary remapping is
 *       the Aprism agent's job (Prismate loads packs as-is)</li>
 *   <li>{@code 26.x} — unobfuscated line, Java 25, no remapping</li>
 * </ul>
 *
 * <p>Prismate keeps its OWN slim copy of the line rather than depending on
 * the Aprism {@code loader-core} module at runtime (the embedded runtime stays
 * minimal and free of Aprism's Mixin service classes, per docs 01 Section
 * 9.1). A composite-build consistency test
 * ({@code VersionLineConsistencyTest}) compares this mirror against the
 * upstream registry and fails loudly on any drift, so the two cannot silently
 * diverge while Aprism keeps developing in parallel (v26.1-Alpha.1).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateVersionLine {

    /** A contiguous segment of the supported JE version line. */
    public record Segment(
            String minorPrefix,
            boolean remapped,
            int javaBaseline,
            String mappingsSource) {
    }

    /** Lowest supported version on the line. */
    public static final String LINE_START = "1.20";

    /** Highest explicitly supported version on the line. */
    public static final String LINE_END = "26.2";

    private static final List<Segment> SEGMENTS = List.of(
            new Segment("1.20", true, 17, "intermediary"),
            new Segment("1.21", true, 21, "intermediary"),
            new Segment("26", false, 25, "none"));

    private PrismateVersionLine() {
    }

    /**
     * Resolves a Minecraft version string to its segment, matching by
     * {@code major.minor} prefix ({@code 1.20.4} matches {@code 1.20};
     * {@code 26.2} and any later {@code 26.x} match {@code 26}). Versions
     * below the line (e.g. {@code 1.19.4}) resolve to empty.
     *
     * @param mcVersion the Minecraft version string
     * @return the matching segment, or empty when below the supported line
     */
    public static Optional<Segment> resolve(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) {
            return Optional.empty();
        }
        String prefix = majorMinorOf(mcVersion.trim());
        if (prefix.isEmpty()) {
            return Optional.empty();
        }
        for (Segment segment : SEGMENTS) {
            if (prefix.equals(segment.minorPrefix())
                    || prefix.startsWith(segment.minorPrefix() + ".")) {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the version falls within the explicitly supported window
     * {@code [1.20, 26.2]}. Versions above {@code 26.2} resolve to the
     * unobfuscated segment but are reported as outside the explicit window
     * (mirrors the upstream {@code isWithinSupportedLine} semantics).
     *
     * @param mcVersion the Minecraft version string
     * @return true when the version is within {@code 1.20 .. 26.2}
     */
    public static boolean isWithinSupportedLine(String mcVersion) {
        Optional<Segment> segment = resolve(mcVersion);
        if (segment.isEmpty()) {
            return false;
        }
        return compareVersions(mcVersion.trim(), LINE_END) <= 0;
    }

    /**
     * @return an unmodifiable snapshot of the supported line segments
     */
    public static List<Segment> supportedLine() {
        return SEGMENTS;
    }

    /**
     * Human-readable description of the supported line, e.g.
     * {@code "1.20 .. 26.2"}.
     *
     * @return the supported line description
     */
    public static String describeLine() {
        return LINE_START + " .. " + LINE_END;
    }

    /**
     * Extracts the {@code major.minor} prefix of a version string, e.g.
     * {@code 1.20} from {@code 1.20.4}. Returns an empty string when the
     * version has no parseable numeric major.
     */
    private static String majorMinorOf(String version) {
        String[] parts = version.split("\\.");
        if (parts.length == 0 || !isNumeric(parts[0])) {
            return "";
        }
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + "." + (isNumeric(parts[1]) ? parts[1] : "0");
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two dotted version strings numerically component by component.
     *
     * @return negative if a &lt; b, 0 if equal, positive if a &gt; b
     */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int ca = i < pa.length && isNumeric(pa[i]) ? Integer.parseInt(pa[i]) : 0;
            int cb = i < pb.length && isNumeric(pb[i]) ? Integer.parseInt(pb[i]) : 0;
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
        }
        return 0;
    }
}
