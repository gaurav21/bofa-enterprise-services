package com.bofa.notifications.config;

import oracle.jdbc.pool.OracleDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Oracle 19c RAC datasource configuration.
 * Uses connection pooling with failover for high availability.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.bofa.notifications.persistence")
@EnableTransactionManagement
public class OracleConfig {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Bean
    public DataSource dataSource() throws SQLException {
        OracleDataSource ds = new OracleDataSource();
        ds.setURL(jdbcUrl);
        ds.setUser(username);
        ds.setPassword(password);
        ds.setImplicitCachingEnabled(true);
        ds.setFastConnectionFailoverEnabled(true);

        // Connection pool settings for high-throughput notification processing
        java.util.Properties props = new java.util.Properties();
        props.setProperty("oracle.jdbc.ReadTimeout", "30000");
        props.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        props.setProperty("oracle.jdbc.fanEnabled", "true");
        ds.setConnectionProperties(props);

        return ds;
    }
}
