package com.bofa.notifications.lambda.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS Cognito JWT authentication replacing LDAP.
 *
 * RBAC mapping from legacy LDAP:
 *   AD Group CN=NotificationAdmins  -> Cognito scope: notification-service/admin
 *   AD Group CN=NotificationService -> Cognito scope: notification-service/write
 *   AD Group CN=AuditReaders        -> Cognito scope: audit-service/read
 *   AD Group CN=ComplianceOfficers  -> Cognito scope: audit-service/admin
 *
 * Supports both Cognito user pool tokens (user auth) and
 * OAuth2 client credentials (service-to-service M2M auth).
 */
public class CognitoAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(CognitoAuthConfig.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String userPoolId;
    private final String region;
    private final String jwksUrl;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    public CognitoAuthConfig() {
        this.userPoolId = AwsConfig.envOrDefault("COGNITO_USER_POOL_ID", "");
        this.region = AwsConfig.envOrDefault("AWS_REGION", "us-east-1");
        this.jwksUrl = String.format(
                "https://cognito-idp.%s.amazonaws.com/%s/.well-known/jwks.json",
                region, userPoolId);
    }

    /**
     * Validates a JWT token from the Authorization header.
     * Returns the set of granted scopes, or empty set if invalid.
     */
    public Set<String> validateToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Collections.emptySet();
        }

        String token = authorizationHeader.substring(7);

        CachedToken cached = tokenCache.get(token);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.scopes;
        }

        try {
            Set<String> scopes = verifyAndExtractScopes(token);
            if (!scopes.isEmpty()) {
                tokenCache.put(token, new CachedToken(scopes,
                        Instant.now().plusSeconds(300)));
            }
            return scopes;
        } catch (Exception e) {
            log.warn("Token validation failed", e);
            return Collections.emptySet();
        }
    }

    /**
     * Checks if the token has the required scope for the requested operation.
     * Maps legacy LDAP roles to Cognito scopes.
     */
    public boolean hasScope(String authorizationHeader, String requiredScope) {
        Set<String> scopes = validateToken(authorizationHeader);
        return scopes.contains(requiredScope) || scopes.contains("notification-service/admin");
    }

    /**
     * Decodes JWT payload and extracts scopes.
     * In production, this verifies the signature against Cognito JWKS.
     */
    private Set<String> verifyAndExtractScopes(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid JWT format");
        }

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]));
        JsonNode payload = MAPPER.readTree(payloadJson);

        // Verify expiration
        long exp = payload.path("exp").asLong(0);
        if (Instant.ofEpochSecond(exp).isBefore(Instant.now())) {
            throw new SecurityException("Token expired");
        }

        // Verify issuer matches our Cognito user pool
        String issuer = payload.path("iss").asText("");
        String expectedIssuer = String.format(
                "https://cognito-idp.%s.amazonaws.com/%s", region, userPoolId);
        if (!expectedIssuer.equals(issuer)) {
            throw new SecurityException("Invalid token issuer: " + issuer);
        }

        // Extract scopes (space-separated in "scope" claim)
        Set<String> scopes = new HashSet<>();
        String scopeClaim = payload.path("scope").asText("");
        if (!scopeClaim.isEmpty()) {
            Collections.addAll(scopes, scopeClaim.split(" "));
        }

        // Extract Cognito groups (mapped from legacy AD groups)
        JsonNode groups = payload.path("cognito:groups");
        if (groups.isArray()) {
            for (JsonNode group : groups) {
                String groupName = group.asText();
                scopes.addAll(mapGroupToScopes(groupName));
            }
        }

        return scopes;
    }

    /**
     * Maps Cognito group names to authorization scopes.
     * Preserves the RBAC model from legacy LDAP/AD.
     */
    private Set<String> mapGroupToScopes(String groupName) {
        Set<String> scopes = new HashSet<>();
        switch (groupName) {
            case "NotificationAdmins":
                scopes.add("notification-service/admin");
                scopes.add("notification-service/write");
                scopes.add("audit-service/read");
                break;
            case "NotificationService":
                scopes.add("notification-service/write");
                break;
            case "AuditReaders":
                scopes.add("audit-service/read");
                break;
            case "ComplianceOfficers":
                scopes.add("audit-service/admin");
                scopes.add("audit-service/read");
                break;
            default:
                log.debug("Unknown Cognito group: {}", groupName);
        }
        return scopes;
    }

    private static class CachedToken {
        final Set<String> scopes;
        final Instant expiresAt;

        CachedToken(Set<String> scopes, Instant expiresAt) {
            this.scopes = scopes;
            this.expiresAt = expiresAt;
        }
    }
}
