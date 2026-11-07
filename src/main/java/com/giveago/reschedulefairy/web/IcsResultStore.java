package com.giveago.reschedulefairy.web;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory store for generated .ics files; keeps only the most recent results within a count and size budget. */
@Component
public class IcsResultStore {

    static final int DEFAULT_MAX_ENTRIES = 200;
    static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;

    public record StoredIcs(byte[] content, String filename) {
    }

    private final int maxEntries;
    private final long maxBytes;
    private final Map<String, StoredIcs> results = new LinkedHashMap<>();
    private long totalBytes;

    public IcsResultStore() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
    }

    IcsResultStore(int maxEntries, long maxBytes) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public synchronized String save(byte[] content, String filename) {
        if (content.length > maxBytes) {
            throw new IllegalArgumentException("The rescheduled calendar is too large to keep");
        }
        String id = UUID.randomUUID().toString();
        results.put(id, new StoredIcs(content, filename));
        totalBytes += content.length;
        Iterator<StoredIcs> oldest = results.values().iterator();
        while (results.size() > maxEntries || totalBytes > maxBytes) {
            totalBytes -= oldest.next().content().length;
            oldest.remove();
        }
        return id;
    }

    public synchronized Optional<StoredIcs> find(String id) {
        return Optional.ofNullable(results.get(id));
    }
}
