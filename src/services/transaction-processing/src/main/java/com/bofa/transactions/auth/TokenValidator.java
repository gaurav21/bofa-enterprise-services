package com.bofa.transactions.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Validates service-to-service authentication tokens.
 * Uses HMAC-SHA256 with shared secret (to be migrated to AWS Cognito JWT).
 * 
 * Token format: base64(payload).base64(hmac)
 * Payload: serviceId:timestamp:nonce
 * 
 * Token expiry: 5 minutes
 */
@Component
public class TokenValidator {

    private static final Logger log = LoggerFactory.getLogger(TokenValidator.class);
    private static final long TOKEN_EXPIRY_SECONDS = 300; // 5 minutes
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    @Value("${auth.shared-secret:default-dev-secret}")
    private String sharedSecret;

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Empty or null token received");
            return false;
        }

        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                log.warn("Invalid token format: expected 2 parts, got {}", parts.length);
                return false;
            }

            String payloadB64 = parts[0];
            String signatureB64 = parts[1];

            // Verify signature
            String expectedSignature = computeHmac(payloadB64);
            if (!signatureB64.equals(expectedSignature)) {
                log.warn("Token signature mismatch");
                return false;
            }

            // Decode and validate payload
            String payload = new String(Base64.getDecoder().decode(payloadB64), StandardCharsets.UTF_8);
            String[] fields = payload.split(":");
            if (fields.length != 3) {
                log.warn("Invalid token payload format");
                return false;
            }

            String serviceId = fields[0];
            long timestamp = Long.parseLong(fields[1]);

            // Check expiry
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - timestamp) > TOKEN_EXPIRY_SECONDS) {
                log.warn("Token expired: issued at {}, current time {}", timestamp, now);
                return false;
            }

            log.debug("Token validated for service: {}", serviceId);
            return true;

        } catch (Exception e) {
            log.error("Token validation error", e);
            return false;
        }
    }

    private String computeHmac(String data) throws Exception {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec keySpec = new SecretKeySpec(
                sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        mac.init(keySpec);
        byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmac);
    }
}
