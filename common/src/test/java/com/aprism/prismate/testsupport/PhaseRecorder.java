package com.aprism.prismate.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cross-classloader phase recorder. Generated mod entrypoints call
 * {@link #record} from inside the Prismate-managed classloader; the recorder
 * itself lives on the test classpath, so the static log is shared.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PhaseRecorder {

    private static final List<String> LOG = new CopyOnWriteArrayList<>();

    /**
     * Records one lifecycle event.
     *
     * @param event the event description (e.g. {@code "mymod:INIT"})
     */
    public static void record(String event) {
        LOG.add(event);
    }

    /**
     * @return an immutable view of all recorded events in order
     */
    public static List<String> events() {
        return List.copyOf(LOG);
    }

    /** Clears the recorded events. */
    public static void clear() {
        LOG.clear();
    }
}
