package com.aprism.prismate.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aprism.api.gameevent.GameTickEvent;

/**
 * Static probe for host-tick bridge tests: records delivered tick stages and
 * supports one-shot throwing for isolation checks.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class TickProbe {

    /** Delivered tick stages in delivery order. */
    public static final List<GameTickEvent.Stage> ticks = new CopyOnWriteArrayList<>();

    /** When true, the next recorded event throws (isolation check). */
    public static volatile boolean throwOnNext;

    private TickProbe() {
    }

    /**
     * Listener callback synthesized into test mods.
     *
     * @param stage the delivered tick stage
     */
    public static void record(GameTickEvent.Stage stage) {
        if (throwOnNext) {
            throwOnNext = false;
            throw new RuntimeException("tick-probe intentional failure");
        }
        ticks.add(stage);
    }

    /** Clears all probe state. */
    public static void clear() {
        ticks.clear();
        throwOnNext = false;
    }
}
