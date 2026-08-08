package com.aprism.prismate.discovery;

/**
 * A named, human-readable load failure for one pack (docs 01 principle 5:
 * fail visible, not silent). Failures are collected during discovery,
 * extraction, resolution, and lifecycle dispatch; none of them aborts the
 * whole boot.
 *
 * @param stage    the pipeline stage where the failure occurred
 * @param packId   the mod id (when known), else {@code null}
 * @param fileName the pack file name (when known), else {@code null}
 * @param reason   the readable failure reason
 * @author BlockConnect@StarsailsClover
 */
public record LoadFailure(String stage, String packId, String fileName, String reason) {

    /** Failure stage constants. */
    public static final String DISCOVERY = "discovery";
    public static final String EXTRACTION = "extraction";
    public static final String DEPENDENCY = "dependency";
    public static final String CLASSPATH = "classpath";
    public static final String LIFECYCLE = "lifecycle";

    /**
     * @return a single-line rendering naming the pack and the reason
     */
    public String render() {
        String subject = packId != null ? packId : (fileName != null ? fileName : "unknown pack");
        String suffix = fileName != null && packId != null ? " (" + fileName + ")" : "";
        return "[" + stage + "] " + subject + suffix + ": " + reason;
    }
}
