package com.bofa.notifications.config;

/**
 * DEPRECATED: Oracle 19c RAC configuration has been replaced by RDS PostgreSQL.
 *
 * Migration mapping:
 *   - OracleDataSource                 -> HikariCP via RDS Proxy
 *   - Oracle RAC failover              -> RDS Multi-AZ automatic failover
 *   - Oracle JDBC ReadTimeout          -> HikariCP connection-timeout
 *   - oracle.jdbc.fanEnabled           -> RDS event notifications
 *   - Oracle 12c Dialect               -> PostgreSQL Dialect
 *   - FETCH FIRST N ROWS ONLY          -> LIMIT N
 *
 * See:
 *   - config/aws/PostgresConfig.java for RDS PostgreSQL configuration
 *   - db/migration/V1__create_notification_schema.sql for Flyway schema
 *
 * @deprecated Replaced by PostgreSQL configuration in config/aws/PostgresConfig.java
 */
@Deprecated(since = "4.0.0", forRemoval = true)
public class OracleConfig {
    // Intentionally empty — Oracle JDBC dependencies removed from pom.xml
    // This class is retained as migration documentation
}
