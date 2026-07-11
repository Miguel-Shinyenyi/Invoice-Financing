package com.settlementengine.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class RequestHasher {

    private final ObjectMapper objectMapper;

    public RequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(CreateSettlementCommand command) {
        try {
            String canonicalJson = objectMapper.writeValueAsString(command);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unable to hash settlement request", e);
        }
    }
}
