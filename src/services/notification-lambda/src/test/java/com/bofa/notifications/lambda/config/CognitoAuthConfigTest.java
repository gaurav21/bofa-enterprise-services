package com.bofa.notifications.lambda.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CognitoAuthConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CognitoAuthConfig authConfig = new CognitoAuthConfig();

    @Test
    void validateToken_nullHeaderReturnsEmpty() {
        Set<String> scopes = authConfig.validateToken(null);
        assertTrue(scopes.isEmpty());
    }

    @Test
    void validateToken_nonBearerHeaderReturnsEmpty() {
        Set<String> scopes = authConfig.validateToken("Basic dXNlcjpwYXNz");
        assertTrue(scopes.isEmpty());
    }

    @Test
    void validateToken_malformedJwtReturnsEmpty() {
        Set<String> scopes = authConfig.validateToken("Bearer not.a.jwt");
        assertTrue(scopes.isEmpty());
    }

    @Test
    void validateToken_expiredTokenReturnsEmpty() throws Exception {
        String token = createMockJwt(
                Map.of(
                        "exp", Instant.now().minusSeconds(3600).getEpochSecond(),
                        "iss", "https://cognito-idp.us-east-1.amazonaws.com/",
                        "scope", "notification-service/write"
                ));

        Set<String> scopes = authConfig.validateToken("Bearer " + token);
        assertTrue(scopes.isEmpty());
    }

    @Test
    void hasScope_adminHasAllScopes() throws Exception {
        String token = createMockJwt(
                Map.of(
                        "exp", Instant.now().plusSeconds(3600).getEpochSecond(),
                        "iss", "https://cognito-idp.us-east-1.amazonaws.com/",
                        "scope", "notification-service/admin"
                ));

        assertTrue(authConfig.hasScope("Bearer " + token, "notification-service/write"));
        assertTrue(authConfig.hasScope("Bearer " + token, "notification-service/admin"));
    }

    @Test
    void hasScope_writeCannotAccessAdmin() throws Exception {
        String token = createMockJwt(
                Map.of(
                        "exp", Instant.now().plusSeconds(3600).getEpochSecond(),
                        "iss", "https://cognito-idp.us-east-1.amazonaws.com/",
                        "scope", "notification-service/write"
                ));

        assertTrue(authConfig.hasScope("Bearer " + token, "notification-service/write"));
        assertFalse(authConfig.hasScope("Bearer " + token, "notification-service/admin"));
    }

    @Test
    void hasScope_nullAuthorizationReturnsFalse() {
        assertFalse(authConfig.hasScope(null, "notification-service/write"));
    }

    private String createMockJwt(Map<String, Object> claims) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MAPPER.writeValueAsBytes(claims));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("mock-signature".getBytes());
        return header + "." + payload + "." + signature;
    }
}
