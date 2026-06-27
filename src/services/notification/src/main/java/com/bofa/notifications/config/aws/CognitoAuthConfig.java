package com.bofa.notifications.config.aws;

import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.annotation.PostConstruct;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * AWS Cognito JWT authentication replacing LDAP/Active Directory.
 *
 * RBAC mapping (AD group -> Cognito scope):
 *   CN=NotificationAdmins  -> notification-service/admin
 *   CN=NotificationService -> notification-service/write
 *   CN=AuditReaders        -> audit-service/read
 *
 * Service-to-service auth uses OAuth2 client credentials flow
 * (replacing LDAP bind with service accounts).
 */
@Configuration
@Profile("aws")
public class CognitoAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(CognitoAuthConfig.class);

    @Value("${aws.cognito.user-pool-id}")
    private String userPoolId;

    @Value("${aws.cognito.region:us-east-1}")
    private String cognitoRegion;

    @Value("${aws.cognito.client-id}")
    private String clientId;

    private JwkProvider jwkProvider;
    private String issuer;

    @PostConstruct
    public void init() {
        this.issuer = String.format("https://cognito-idp.%s.amazonaws.com/%s",
                cognitoRegion, userPoolId);
        String jwksUrl = issuer + "/.well-known/jwks.json";

        try {
            this.jwkProvider = new JwkProviderBuilder(new URL(jwksUrl))
                    .cached(10, 24, TimeUnit.HOURS)
                    .rateLimited(10, 1, TimeUnit.MINUTES)
                    .build();
        } catch (Exception e) {
            log.error("Failed to initialize Cognito JWKS provider", e);
            throw new RuntimeException("Cognito configuration failed", e);
        }
    }

    public DecodedJWT validateToken(String token) {
        try {
            DecodedJWT unverified = JWT.decode(token);
            RSAPublicKey publicKey = (RSAPublicKey) jwkProvider
                    .get(unverified.getKeyId()).getPublicKey();

            JWTVerifier verifier = JWT.require(Algorithm.RSA256(publicKey, null))
                    .withIssuer(issuer)
                    .withAudience(clientId)
                    .acceptLeeway(30)
                    .build();

            return verifier.verify(token);
        } catch (Exception e) {
            log.warn("JWT validation failed", e);
            throw new SecurityException("Invalid or expired JWT token", e);
        }
    }

    public boolean hasScope(DecodedJWT jwt, String requiredScope) {
        List<String> scopes = jwt.getClaim("scope").asList(String.class);
        if (scopes == null) {
            return false;
        }
        return scopes.contains(requiredScope);
    }

    public boolean hasAnyScope(DecodedJWT jwt, Set<String> requiredScopes) {
        List<String> scopes = jwt.getClaim("scope").asList(String.class);
        if (scopes == null) {
            return false;
        }
        return scopes.stream().anyMatch(requiredScopes::contains);
    }

    public String extractAccountId(DecodedJWT jwt) {
        return jwt.getClaim("custom:account_id").asString();
    }
}
