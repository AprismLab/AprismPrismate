package com.aprism.prismate;

import java.util.List;

import com.aprism.api.AprismPhase;
import com.aprism.prismate.host.EnvSide;

/**
 * Maps Aprism lifecycle phases onto host-loader lifecycle hooks (docs 01
 * Section 8). The shared core owns the phase SEMANTICS (order, entrypoint
 * keys); each loader entrypoint owns WHICH host hook triggers which dispatch.
 *
 * <p>Dispatch order is strict: {@code PREINIT -> INIT -> SETUP -> COMPLETE},
 * then the side phase ({@code CLIENT} or {@code SERVER}).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class LifecycleMapper {

    private LifecycleMapper() {
    }

    /**
     * The common lifecycle phases in strict dispatch order.
     *
     * @return PREINIT, INIT, SETUP, COMPLETE
     */
    public static List<AprismPhase> commonPhases() {
        return List.of(
                AprismPhase.PREINIT,
                AprismPhase.INIT,
                AprismPhase.SETUP,
                AprismPhase.COMPLETE);
    }

    /**
     * Maps the host's distribution side to the Aprism side phase.
     *
     * @param side the distribution side
     * @return CLIENT or SERVER
     */
    public static AprismPhase sidePhase(EnvSide side) {
        return side == EnvSide.CLIENT ? AprismPhase.CLIENT : AprismPhase.SERVER;
    }

    /**
     * Maps a lifecycle phase to the manifest entrypoint key invoked for it
     * (mirrors Aprism core's phase-to-entrypoint mapping).
     *
     * @param phase the lifecycle phase
     * @return {@code main}, {@code client}, or {@code server}
     */
    public static String entrypointKeyFor(AprismPhase phase) {
        return switch (phase) {
            case PREINIT, INIT, SETUP, COMPLETE -> "main";
            case CLIENT -> "client";
            case SERVER -> "server";
        };
    }

    /**
     * Whether the phase is a distribution side phase.
     *
     * @param phase the lifecycle phase
     * @return true for CLIENT and SERVER
     */
    public static boolean isSidePhase(AprismPhase phase) {
        return phase == AprismPhase.CLIENT || phase == AprismPhase.SERVER;
    }
}
