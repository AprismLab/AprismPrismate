package com.aprism.prismate.host;

/**
 * The distribution side the host loader is running on. Maps onto the Aprism
 * side phases: {@link #CLIENT} dispatches {@code CLIENT},
 * {@link #DEDICATED_SERVER} dispatches {@code SERVER}.
 *
 * @author BlockConnect@StarsailsClover
 */
public enum EnvSide {
    /** Integrated or dedicated client (rendering present). */
    CLIENT,
    /** Dedicated server. */
    DEDICATED_SERVER;

    /**
     * Whether a mod declaring the given Aprism environment should run on this
     * side. {@code COMMON} mods run everywhere.
     *
     * @param environment the Aprism environment declared by the mod manifest
     * @return whether the mod is active on this side
     */
    public boolean matches(com.aprism.api.Environment environment) {
        if (environment == com.aprism.api.Environment.COMMON) {
            return true;
        }
        return this == CLIENT
                ? environment == com.aprism.api.Environment.CLIENT
                : environment == com.aprism.api.Environment.DEDICATED_SERVER;
    }
}
