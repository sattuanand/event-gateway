package com.eventledger.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small builder so the tests read as intent rather than as JSON plumbing. */
public final class TestEvents {

    private final Map<String, Object> payload = new LinkedHashMap<>();

    private TestEvents() {
        payload.put("eventId", "evt-001");
        payload.put("accountId", "acct-123");
        payload.put("type", "CREDIT");
        payload.put("amount", 150.00);
        payload.put("currency", "USD");
        payload.put("eventTimestamp", "2026-05-15T14:02:11Z");
        payload.put("metadata", Map.of("source", "mainframe-batch", "batchId", "B-9042"));
    }

    public static TestEvents valid() {
        return new TestEvents();
    }

    public TestEvents with(String field, Object value) {
        payload.put(field, value);
        return this;
    }

    public TestEvents without(String field) {
        payload.remove(field);
        return this;
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(payload);
    }
}
