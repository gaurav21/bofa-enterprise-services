package com.bofa.transactions.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenValidator — Service-to-service HMAC-SHA256 authentication")
class TokenValidatorTest {

    private TokenValidator validator;
    private static final String TEST_SECRET = "test-shared-secret-key";

    @BeforeEach
    void setUp() {
        validator = new TokenValidator();
        ReflectionTestUtils.setField(validator, "sharedSecret", TEST_SECRET);
    }

    private String createValidToken(String serviceId) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String nonce = UUID.randomUUID().toString();
        String payload = serviceId + ":" + timestamp + ":" + nonce;
        String payloadB64 = Base64.getEncoder().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
        String signature = computeHmac(payloadB64);
        return payloadB64 + "." + signature;
    }

    private String createTokenWithTimestamp(String serviceId, long timestamp) throws Exception {
        String nonce = UUID.randomUUID().toString();
        String payload = serviceId + ":" + timestamp + ":" + nonce;
        String payloadB64 = Base64.getEncoder().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
        String signature = computeHmac(payloadB64);
        return payloadB64 + "." + signature;
    }

    private String computeHmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(
                TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmac);
    }

    @Nested
    @DisplayName("Valid token acceptance")
    class ValidTokens {

        @Test
        @DisplayName("accepts a freshly created valid token")
        void acceptsFreshToken() throws Exception {
            String token = createValidToken("transaction-service");

            assertTrue(validator.validateToken(token));
        }

        @Test
        @DisplayName("accepts token from different service IDs")
        void acceptsDifferentServiceIds() throws Exception {
            assertTrue(validator.validateToken(createValidToken("audit-service")));
            assertTrue(validator.validateToken(createValidToken("notification-service")));
            assertTrue(validator.validateToken(createValidToken("payment-gateway")));
        }

        @Test
        @DisplayName("accepts token issued 4 minutes ago (within 5 min window)")
        void acceptsTokenWithin5Minutes() throws Exception {
            long fourMinutesAgo = Instant.now().getEpochSecond() - 240;
            String token = createTokenWithTimestamp("service-a", fourMinutesAgo);

            assertTrue(validator.validateToken(token));
        }
    }

    @Nested
    @DisplayName("Null, blank, and empty token rejection")
    class NullBlankTokens {

        @Test
        @DisplayName("rejects null token")
        void rejectsNull() {
            assertFalse(validator.validateToken(null));
        }

        @Test
        @DisplayName("rejects empty string token")
        void rejectsEmpty() {
            assertFalse(validator.validateToken(""));
        }

        @Test
        @DisplayName("rejects blank/whitespace-only token")
        void rejectsBlank() {
            assertFalse(validator.validateToken("   "));
        }
    }

    @Nested
    @DisplayName("Malformed token rejection")
    class MalformedTokens {

        @Test
        @DisplayName("rejects token without separator dot")
        void rejectsNoDot() {
            assertFalse(validator.validateToken("sometoken_without_dot"));
        }

        @Test
        @DisplayName("rejects token with too many parts")
        void rejectsTooManyParts() {
            assertFalse(validator.validateToken("part1.part2.part3"));
        }

        @Test
        @DisplayName("rejects token with single character")
        void rejectsSingleChar() {
            assertFalse(validator.validateToken("x"));
        }

        @Test
        @DisplayName("rejects token with invalid base64 payload")
        void rejectsInvalidBase64() {
            assertFalse(validator.validateToken("not-valid-base64!!!.signature"));
        }
    }

    @Nested
    @DisplayName("Signature validation")
    class SignatureValidation {

        @Test
        @DisplayName("rejects token with tampered signature")
        void rejectsTamperedSignature() throws Exception {
            String token = createValidToken("service-a");
            String[] parts = token.split("\\.");
            String tamperedSignature = Base64.getEncoder().encodeToString("fake".getBytes());
            String tamperedToken = parts[0] + "." + tamperedSignature;

            assertFalse(validator.validateToken(tamperedToken));
        }

        @Test
        @DisplayName("rejects token signed with different secret")
        void rejectsDifferentSecret() throws Exception {
            long timestamp = Instant.now().getEpochSecond();
            String payload = "service::" + timestamp + ":" + UUID.randomUUID();
            String payloadB64 = Base64.getEncoder().encodeToString(
                    payload.getBytes(StandardCharsets.UTF_8));

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    "wrong-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8));
            String wrongSig = Base64.getEncoder().encodeToString(hmac);

            assertFalse(validator.validateToken(payloadB64 + "." + wrongSig));
        }

        @Test
        @DisplayName("rejects empty signature")
        void rejectsEmptySignature() throws Exception {
            long timestamp = Instant.now().getEpochSecond();
            String payload = "service:" + timestamp + ":nonce";
            String payloadB64 = Base64.getEncoder().encodeToString(
                    payload.getBytes(StandardCharsets.UTF_8));

            assertFalse(validator.validateToken(payloadB64 + "."));
        }
    }

    @Nested
    @DisplayName("Token expiry enforcement")
    class TokenExpiry {

        @Test
        @DisplayName("rejects token issued more than 5 minutes ago")
        void rejectsExpiredToken() throws Exception {
            long sixMinutesAgo = Instant.now().getEpochSecond() - 360;
            String token = createTokenWithTimestamp("service-a", sixMinutesAgo);

            assertFalse(validator.validateToken(token));
        }

        @Test
        @DisplayName("rejects token with future timestamp beyond 5 minutes")
        void rejectsFutureToken() throws Exception {
            long sixMinutesFuture = Instant.now().getEpochSecond() + 360;
            String token = createTokenWithTimestamp("service-a", sixMinutesFuture);

            assertFalse(validator.validateToken(token));
        }

        @Test
        @DisplayName("accepts token at exactly 5 minutes boundary")
        void acceptsAtExactBoundary() throws Exception {
            long exactlyFiveMinutes = Instant.now().getEpochSecond() - 299;
            String token = createTokenWithTimestamp("service-a", exactlyFiveMinutes);

            assertTrue(validator.validateToken(token));
        }
    }

    @Nested
    @DisplayName("Payload format validation")
    class PayloadFormat {

        @Test
        @DisplayName("rejects payload with wrong number of fields")
        void rejectsWrongFieldCount() throws Exception {
            String payload = "only-two-fields:123";
            String payloadB64 = Base64.getEncoder().encodeToString(
                    payload.getBytes(StandardCharsets.UTF_8));
            String signature = computeHmac(payloadB64);

            assertFalse(validator.validateToken(payloadB64 + "." + signature));
        }

        @Test
        @DisplayName("rejects payload with non-numeric timestamp")
        void rejectsNonNumericTimestamp() throws Exception {
            String payload = "service:not-a-number:nonce";
            String payloadB64 = Base64.getEncoder().encodeToString(
                    payload.getBytes(StandardCharsets.UTF_8));
            String signature = computeHmac(payloadB64);

            assertFalse(validator.validateToken(payloadB64 + "." + signature));
        }
    }

    @Nested
    @DisplayName("Security: no sensitive data leak on invalid tokens")
    class NoDataLeak {

        @Test
        @DisplayName("returns false without exception for garbage input")
        void noExceptionOnGarbage() {
            assertDoesNotThrow(() -> validator.validateToken("@#$%^&*()"));
            assertFalse(validator.validateToken("@#$%^&*()"));
        }

        @Test
        @DisplayName("returns false without exception for very long input")
        void noExceptionOnLongInput() {
            String longToken = "a".repeat(10000) + "." + "b".repeat(10000);
            assertDoesNotThrow(() -> validator.validateToken(longToken));
            assertFalse(validator.validateToken(longToken));
        }
    }
}
