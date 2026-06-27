package com.bofa.notifications.config.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retrieves secrets from AWS Secrets Manager.
 * Replaces hardcoded credentials and environment variable secrets.
 * All secrets are KMS-encrypted at rest.
 */
@Configuration
public class AwsSecretsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsConfig.class);

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.secrets.db-secret-arn:}")
    private String dbSecretArn;

    private final Map<String, String> secretCache = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SecretsManagerClient secretsClient;

    @PostConstruct
    public void init() {
        this.secretsClient = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    public String getSecretValue(String secretArn, String key) {
        String cacheKey = secretArn + ":" + key;
        return secretCache.computeIfAbsent(cacheKey, k -> {
            try {
                GetSecretValueResponse response = secretsClient.getSecretValue(
                        GetSecretValueRequest.builder()
                                .secretId(secretArn)
                                .build());
                JsonNode json = objectMapper.readTree(response.secretString());
                return json.get(key).asText();
            } catch (Exception e) {
                log.error("Failed to retrieve secret: arn={}, key={}", secretArn, key, e);
                throw new RuntimeException("Secret retrieval failed", e);
            }
        });
    }

    public String getDatabaseUrl() {
        if (dbSecretArn.isEmpty()) {
            return null;
        }
        String host = getSecretValue(dbSecretArn, "host");
        String port = getSecretValue(dbSecretArn, "port");
        String dbName = getSecretValue(dbSecretArn, "dbname");
        return String.format("jdbc:postgresql://%s:%s/%s?sslmode=require", host, port, dbName);
    }

    public String getDatabaseUsername() {
        if (dbSecretArn.isEmpty()) {
            return null;
        }
        return getSecretValue(dbSecretArn, "username");
    }

    public String getDatabasePassword() {
        if (dbSecretArn.isEmpty()) {
            return null;
        }
        return getSecretValue(dbSecretArn, "password");
    }

    public void invalidateCache() {
        secretCache.clear();
    }
}
