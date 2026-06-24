package com.bofa.notifications.lambda.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.sqs.SqsClient;

import javax.sql.DataSource;

/**
 * Centralized AWS service configuration.
 * All services use us-east-1 per BofA data residency requirements.
 * Credentials: IAM role-based (Lambda execution role) — no embedded secrets.
 */
public final class AwsConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsConfig.class);
    private static final Region REGION = Region.US_EAST_1;

    private static volatile SqsClient sqsClient;
    private static volatile DataSource dataSource;
    private static volatile SecretsManagerClient secretsManagerClient;

    private AwsConfig() {}

    public static SqsClient sqsClient() {
        if (sqsClient == null) {
            synchronized (AwsConfig.class) {
                if (sqsClient == null) {
                    sqsClient = SqsClient.builder()
                            .region(REGION)
                            .build();
                    log.info("SQS client initialized for region {}", REGION);
                }
            }
        }
        return sqsClient;
    }

    public static SecretsManagerClient secretsManagerClient() {
        if (secretsManagerClient == null) {
            synchronized (AwsConfig.class) {
                if (secretsManagerClient == null) {
                    secretsManagerClient = SecretsManagerClient.builder()
                            .region(REGION)
                            .build();
                }
            }
        }
        return secretsManagerClient;
    }

    /**
     * Creates a connection pool to RDS PostgreSQL.
     * Uses RDS Proxy endpoint to minimize connection overhead per Lambda invocation.
     * TLS 1.2+ enforced per encryption-in-transit requirements.
     */
    public static DataSource dataSource() {
        if (dataSource == null) {
            synchronized (AwsConfig.class) {
                if (dataSource == null) {
                    String dbHost = envOrDefault("DB_HOST", "localhost");
                    String dbPort = envOrDefault("DB_PORT", "5432");
                    String dbName = envOrDefault("DB_NAME", "notifications");
                    String dbUser = envOrDefault("DB_USER", "notif_admin");
                    String dbPasswordSecret = envOrDefault("DB_PASSWORD_SECRET_ARN", "");

                    String dbPassword;
                    if (!dbPasswordSecret.isEmpty()) {
                        dbPassword = resolveSecret(dbPasswordSecret);
                    } else {
                        dbPassword = envOrDefault("DB_PASSWORD", "");
                    }

                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(String.format(
                            "jdbc:postgresql://%s:%s/%s?sslmode=require&sslrootcert=/tmp/rds-combined-ca-bundle.pem",
                            dbHost, dbPort, dbName));
                    config.setUsername(dbUser);
                    config.setPassword(dbPassword);
                    config.setDriverClassName("org.postgresql.Driver");

                    // Lambda-optimized pool: small pool, fast timeout
                    config.setMaximumPoolSize(5);
                    config.setMinimumIdle(1);
                    config.setConnectionTimeout(5000);
                    config.setIdleTimeout(120000);
                    config.setMaxLifetime(300000);
                    config.setLeakDetectionThreshold(30000);

                    config.addDataSourceProperty("reWriteBatchedInserts", "true");
                    config.addDataSourceProperty("ApplicationName", "notification-lambda");

                    dataSource = new HikariDataSource(config);
                    log.info("RDS PostgreSQL connection pool initialized: host={}, db={}", dbHost, dbName);
                }
            }
        }
        return dataSource;
    }

    private static String resolveSecret(String secretArn) {
        try {
            return secretsManagerClient().getSecretValue(
                    GetSecretValueRequest.builder()
                            .secretId(secretArn)
                            .build()
            ).secretString();
        } catch (Exception e) {
            log.error("Failed to resolve secret: {}", secretArn, e);
            throw new RuntimeException("Cannot resolve database credentials from Secrets Manager", e);
        }
    }

    public static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value != null ? value : defaultValue;
    }
}
