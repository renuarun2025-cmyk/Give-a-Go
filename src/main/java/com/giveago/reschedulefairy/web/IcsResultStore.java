package com.giveago.reschedulefairy.web;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory store for generated .ics files; keeps only the most recent results. */
@Component
public class IcsResultStore {

    static final int MAX_ENTRIES = 200;

    public record StoredIcs(byte[] content, String filename) {
    }

    private final Map<String, StoredIcs> results = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, StoredIcs> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public synchronized String save(byte[] content, String filename) {
        String id = UUID.randomUUID().toString();
        results.put(id, new StoredIcs(content, filename));
        return id;
    }

    public synchronized Optional<StoredIcs> find(String id) {
        return Optional.ofNullable(results.get(id));
    }
}
