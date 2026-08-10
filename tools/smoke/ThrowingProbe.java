package com.example.faultsmoke;

import com.aprism.api.AprismContext;
import com.aprism.api.IAprismMod;

/**
 * Fault-injection probe for the v26.1-Alpha.8 real-game harness hardening
 * drill. Its {@code onInitialize} deliberately throws so the harness can
 * assert Prismate's per-mod failure isolation and named-failure reporting in
 * a live game: this pack must be recorded as a named lifecycle failure
 * WITHOUT stopping the healthy pack next to it.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class ThrowingProbe implements IAprismMod {

    /** Marker string the harness greps the load report for. */
    public static final String FAULT_MESSAGE = "intentional fault-injection failure";

    @Override
    public void onInitialize(AprismContext context) {
        throw new RuntimeException(FAULT_MESSAGE);
    }
}
