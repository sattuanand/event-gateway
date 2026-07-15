package com.eventledger.gateway.service;

import com.eventledger.gateway.api.dto.EventRequest;
import com.eventledger.gateway.api.dto.EventResponse;
import com.eventledger.gateway.domain.EventRecord;
import com.eventledger.gateway.domain.EventStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class EventMapper {

    private static final Logger log = LoggerFactory.getLogger(EventMapper.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public EventMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public EventRecord toNewRecord(EventRequest request) {
        return new EventRecord(
                request.eventId(),
                request.accountId(),
                request.type(),
                request.amount(),
                request.currency().toUpperCase(),
                request.eventTimestamp(),
                writeMetadata(request.metadata()),
                EventStatus.PENDING,
                Instant.now());
    }

    public EventResponse toResponse(EventRecord record) {
        return new EventResponse(
                record.getEventId(),
                record.getAccountId(),
                record.getType(),
                record.getAmount(),
                record.getCurrency(),
                record.getEventTimestamp(),
                readMetadata(record.getMetadata()),
                record.getStatus(),
                record.getReceivedAt());
    }

    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("metadata is not serializable to JSON", e);
        }
    }

    private Map<String, Object> readMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadata, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Stored metadata is not valid JSON, returning null: {}", e.getMessage());
            return null;
        }
    }
}
