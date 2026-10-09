package com.settlementengine.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlementengine.core.util.Sha256;
import org.springframework.stereotype.Component;

@Component
public class RequestHasher {

    private final ObjectMapper objectMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(CreateSettlementCommand command) {
        try {
            String canonicalJson = objectMapper.writeValueAsString(command);
            return Sha256.hex(canonicalJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to hash settlement request", e);
        }
    }
}
