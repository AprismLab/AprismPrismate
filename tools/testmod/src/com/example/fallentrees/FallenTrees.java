package com.example.fallentrees;

import com.aprism.api.AprismContext;
import com.aprism.api.AprismEventBus;
import com.aprism.api.AprismMod;
import com.aprism.api.IAprismMod;
import com.aprism.api.gameevent.GameTickEvent;

/**
 * Fallen-trees long-term test mod (v26.7 line vehicle).
 *
 * <p>Goal: real-content behavioral parity measurement between the Aprism
 * agent and the Prismate bridge. This skeleton exercises the surfaces a
 * production worldgen mod touches: lifecycle phases, event-bus subscription
 * (game tick), registry round-trip, and pack resources visibility.
 *
 * <p>Every observable prints a [FALLENTREES] marker so both loaders' logs can
 * be diffed mechanically.
 */
@AprismMod("fallentrees")
public class FallenTrees implements IAprismMod {

    private static final String MARKER = "[FALLENTREES]";

    /** Tick events observed so far (bounded logging). */
    private long tickCount = 0;

    @Override
    public void onPreInitialize(AprismContext context) {
        System.out.println(MARKER + " PREINIT loader-side, mod="
                + context.getMod().getId() + " v" + context.getMod().getVersion());
    }

    @Override
    public void onInitialize(AprismContext context) {
        System.out.println(MARKER + " INIT begin");

        // Event-bus subscription attempt: does this loader deliver game ticks?
        try {
            AprismEventBus bus = context.getEventBus();
            bus.register(GameTickEvent.class, event -> {
                tickCount++;
                if (tickCount <= 3) {
                    System.out.println(MARKER + " TICK " + tickCount
                            + " stage=" + event.getStage());
                } else if (tickCount == 4) {
                    System.out.println(MARKER + " TICK stream confirmed"
                            + " (further ticks not logged)");
                }
            });
            System.out.println(MARKER + " INIT GameTickEvent listener registered OK");
        } catch (Throwable t) {
            System.out.println(MARKER + " INIT GameTickEvent registration FAILED: " + t);
        }

        // Registry round-trip: register a marker entry and read it back.
        try {
            context.getRegistry().register("fallentrees", "worldgen_probe",
                    "fallen-tree-feature-v0");
            Object raw = context.getRegistry().get("fallentrees", "worldgen_probe");
            Object value = raw instanceof java.util.Optional<?> opt
                    ? opt.orElse(null)
                    : raw;
            System.out.println(MARKER + " INIT registry round-trip "
                    + ("fallen-tree-feature-v0".equals(value) ? "OK" : "MISMATCH: " + value)
                    + " (raw type: " + (raw == null ? "null" : raw.getClass().getSimpleName()) + ")");
        } catch (Throwable t) {
            System.out.println(MARKER + " INIT registry round-trip FAILED: " + t);
        }

        System.out.println(MARKER + " INIT end");
    }

    @Override
    public void onSetup(AprismContext context) {
        // Resource visibility probe: the pack's data/ directory must be
        // reachable through the context classloader for datapack-driven
        // worldgen to work.
        ClassLoader cl = FallenTrees.class.getClassLoader();
        String probe = "data/fallentrees/worldgen_probe.txt";
        boolean visible = cl.getResource(probe) != null;
        System.out.println(MARKER + " SETUP resource probe " + probe
                + " visible=" + visible);
    }

    @Override
    public void onComplete(AprismContext context) {
        System.out.println(MARKER + " COMPLETE fallen-trees ready"
                + " (ticks observed so far: " + tickCount + ")");
    }
}
