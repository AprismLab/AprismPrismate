package com.aprism.prismate.runtime;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aprism.api.AprismRegistry;

/**
 * Minimal in-memory {@link AprismRegistry} for the embedded runtime. Entries
 * are keyed by {@code namespace:name}.
 *
 * @author BlockConnect@StarsailsClover
 */
public final class PrismateRegistry implements AprismRegistry {

    private final Map<String, Object> entries = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T register(String namespace, String name, T entry) {
        entries.put(namespace + ":" + name, entry);
        return entry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String namespace, String name) {
        return Optional.ofNullable((T) entries.get(namespace + ":" + name));
    }

    @Override
    public Set<String> getNamespaces() {
        Set<String> namespaces = ConcurrentHashMap.newKeySet();
        for (String key : entries.keySet()) {
            int colon = key.indexOf(':');
            namespaces.add(colon > 0 ? key.substring(0, colon) : key);
        }
        return namespaces;
    }
}
