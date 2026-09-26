package com.hrms.hrms_utility.utility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class JwtUtil {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JwtUtil() {
    }

    /**
     * Extracts the user id (the Keycloak "sub" claim) from a JWT. Employees are created with their
     * Keycloak user id as employeeId, so the returned value can be compared with an employeeId.
     * The signature is NOT verified here; token verification is expected to happen upstream.
     *
     * @param authorizationHeader the Authorization header value, with or without the "Bearer " prefix
     * @return the user id from the token
     * @throws RuntimeException if the token is missing, malformed or has no "sub" claim
     */
    public static String extractUserId(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new RuntimeException("Authorization token is missing.");
        }

        String token = authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length()).trim()
                : authorizationHeader.trim();

        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            throw new RuntimeException("Authorization token is malformed.");
        }

        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = OBJECT_MAPPER.readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode sub = claims.get("sub");
            if (sub == null || sub.asText().isBlank()) {
                throw new RuntimeException("Authorization token does not contain a user id.");
            }
            return sub.asText();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Authorization token is malformed.");
        }
    }
}
