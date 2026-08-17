package com.aprism.prismate.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aprism.api.AprismEvent;
import com.aprism.api.AprismEventListener;
import com.aprism.api.AprismPhase;
import com.aprism.api.EventPriority;
import com.aprism.api.gameevent.AbstractGameEvent;

/**
 * Tests for the priority-aware {@link PrismateEventBus} (v26.2-Alpha.2,
 * mirroring Aprism core's AprismEventBusImpl from v26.3-Alpha.6).
 *
 * @author BlockConnect@StarsailsClover
 */
@DisplayName("PrismateEventBus priority dispatch")
class PrismateEventBusPriorityTest {

    private final PrismateEventBus bus = new PrismateEventBus();

    private static class TestEvent extends AbstractGameEvent {
        TestEvent() {
            super(AprismPhase.COMPLETE, true);
        }
    }

    @Test
    @DisplayName("default priority is NORMAL when none is specified")
    void defaultPriorityIsNormal() {
        var order = new java.util.ArrayList<String>();
        bus.register(TestEvent.class, e -> order.add("normal"));
        bus.post(new TestEvent());
        assertThat(order).containsExactly("normal");
    }

    @Test
    @DisplayName("HIGHEST fires before NORMAL before LOW")
    void priorityOrderIsRespected() {
        var order = new java.util.ArrayList<String>();
        bus.register(TestEvent.class, e -> order.add("normal"), EventPriority.NORMAL);
        bus.register(TestEvent.class, e -> order.add("lowest"), EventPriority.LOWEST);
        bus.register(TestEvent.class, e -> order.add("highest"), EventPriority.HIGHEST);
        bus.register(TestEvent.class, e -> order.add("low"), EventPriority.LOW);

        bus.post(new TestEvent());

        assertThat(order).containsExactly("highest", "normal", "low", "lowest");
    }

    @Test
    @DisplayName("same-priority listeners run in registration order")
    void samePriorityRunsInRegistrationOrder() {
        var order = new java.util.ArrayList<String>();
        bus.register(TestEvent.class, e -> order.add("first"), EventPriority.NORMAL);
        bus.register(TestEvent.class, e -> order.add("second"), EventPriority.NORMAL);
        bus.register(TestEvent.class, e -> order.add("third"), EventPriority.NORMAL);

        bus.post(new TestEvent());

        assertThat(order).containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("cancellation short-circuits remaining listeners")
    void cancellationShortCircuits() {
        var order = new java.util.ArrayList<String>();
        bus.register(TestEvent.class, e -> {
            order.add("high");
            e.setCancelled(true);
        }, EventPriority.HIGH);
        bus.register(TestEvent.class, e -> order.add("normal"), EventPriority.NORMAL);

        bus.post(new TestEvent());

        assertThat(order).containsExactly("high");
    }

    @Test
    @DisplayName("unregister removes the listener")
    void unregisterRemovesListener() {
        var order = new java.util.ArrayList<String>();
        AprismEventListener<TestEvent> listener = e -> order.add("fired");
        bus.register(TestEvent.class, listener);
        bus.unregister(TestEvent.class, listener);

        bus.post(new TestEvent());

        assertThat(order).isEmpty();
    }

    @Test
    @DisplayName("null priority defaults to NORMAL")
    void nullPriorityDefaultsToNormal() {
        var order = new java.util.ArrayList<String>();
        bus.register(TestEvent.class, e -> order.add("highest"), EventPriority.HIGHEST);
        bus.register(TestEvent.class, e -> order.add("normal"), null);

        bus.post(new TestEvent());

        assertThat(order).containsExactly("highest", "normal");
    }
}
