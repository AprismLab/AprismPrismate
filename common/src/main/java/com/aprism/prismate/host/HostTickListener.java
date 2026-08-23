package com.aprism.prismate.host;

/**
 * Callback the bridge invokes once per host game tick (v26.7-Alpha.3
 * host-tick bridge). The bridge owns the tick counter; the embedded runtime
 * turns callbacks into {@code GameTickEvent} deliveries on the shared bus.
 *
 * @author BlockConnect@StarsailsClover
 */
@FunctionalInterface
public interface HostTickListener {

    /**
     * Called once per host game tick.
     *
     * @param tickNumber the monotonically increasing tick counter (0-based,
     *                   counted by the bridge from hook registration)
     */
    void onTick(long tickNumber);
}
