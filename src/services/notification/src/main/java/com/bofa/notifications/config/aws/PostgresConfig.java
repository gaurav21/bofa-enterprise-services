package com.bofa.notifications.config.aws;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * RDS PostgreSQL configuration replacing Oracle 19c RAC.
 *
 * Key migration decisions:
 *   - Oracle RAC failover -> RDS Multi-AZ automatic failover
 *   - OracleDataSource -> HikariCP via RDS Proxy (eliminates per-invocation overhead)
 *   - Oracle dialect -> PostgreSQL dialect
 *   - FETCH FIRST N ROWS ONLY -> LIMIT (compatible in PG 12+)
 *   - Oracle sequences -> PostgreSQL sequences
 *
 * Data residency: us-east-1 only. Encryption at rest via KMS.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.bofa.notifications.persistence")
@EnableTransactionManagement
@Profile("aws")
public class PostgresConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int maxPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:2}")
    private int minIdle;

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");

        // Lambda-optimized pool settings (via RDS Proxy)
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(120_000);
        config.setMaxLifetime(300_000);
        config.setValidationTimeout(5_000);
        config.setKeepaliveTime(60_000);

        // SSL for encryption in transit (TLS 1.2+)
        config.addDataSourceProperty("ssl", "true");
        config.addDataSourceProperty("sslmode", "require");

        return new HikariDataSource(config);
    }

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
