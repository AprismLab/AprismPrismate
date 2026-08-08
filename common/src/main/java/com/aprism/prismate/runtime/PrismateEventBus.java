package com.aprism.prismate.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aprism.api.AprismEvent;
import com.aprism.api.AprismEventBus;
import com.aprism.api.AprismEventListener;

/**
 * Thread-safe {@link AprismEventBus} implementation for the embedded runtime
 * (semantic mirror of Aprism's own event bus: listeners keyed by event type,
 * synchronous dispatch, cancellation short-circuits the remaining listeners).
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateEventBus implements AprismEventBus {

    private final Map<Class<?>, List<AprismEventListener<?>>> listeners = new ConcurrentHashMap<>();

    @Override
    public <E extends AprismEvent> void register(Class<E> eventType, AprismEventListener<E> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public <E extends AprismEvent> void unregister(Class<E> eventType, AprismEventListener<E> listener) {
        List<AprismEventListener<?>> bucket = listeners.get(eventType);
        if (bucket != null) {
            bucket.remove(listener);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void post(AprismEvent event) {
        List<AprismEventListener<?>> bucket = listeners.get(event.getClass());
        if (bucket == null) {
            return;
        }
        for (AprismEventListener<?> listener : bucket) {
            ((AprismEventListener<AprismEvent>) listener).onEvent(event);
            if (event.isCancelled()) {
                break;
            }
        }
    }
}
