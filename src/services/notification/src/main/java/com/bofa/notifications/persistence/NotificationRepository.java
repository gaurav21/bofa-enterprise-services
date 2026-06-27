package com.bofa.notifications.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL repository for notification persistence (migrated from Oracle 19c).
 *
 * Oracle -> PostgreSQL SQL migration:
 *   - FETCH FIRST ? ROWS ONLY -> LIMIT ? (PostgreSQL-native)
 *   - Oracle RAC failover     -> RDS Multi-AZ automatic failover
 *   - Oracle partitioning     -> PostgreSQL native partitioning (range by month)
 *   - OracleDataSource        -> HikariCP via RDS Proxy
 *
 * Table: notification_events (partitioned by month for retention compliance).
 * Retention: 7 years per GLBA/SOX requirements.
 */
@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveNotification(String notificationId, String type,
                                  String accountId, String payload, String status) {
        jdbcTemplate.update(
            "INSERT INTO notification_events " +
            "(notification_id, event_type, account_id, payload, status, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            notificationId, type, accountId, payload, status,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    public void updateStatus(String notificationId, String status) {
        jdbcTemplate.update(
            "UPDATE notification_events SET status = ?, updated_at = ? WHERE notification_id = ?",
            status, Timestamp.from(Instant.now()), notificationId
        );
    }

    public List<Map<String, Object>> findByAccountId(String accountId, int limit) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM notification_events WHERE account_id = ? " +
            "ORDER BY created_at DESC LIMIT ?",
            accountId, limit
        );
    }

    public List<Map<String, Object>> findPendingNotifications(int batchSize) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM notification_events WHERE status = 'PENDING' " +
            "AND created_at > ? ORDER BY created_at ASC LIMIT ?",
            Timestamp.from(Instant.now().minusSeconds(3600)), batchSize
        );
    }
}
