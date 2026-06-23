package com.bofa.notifications.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Oracle repository for notification persistence.
 * Table: NOTIFICATION_EVENTS (partitioned by month for retention compliance).
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
            "INSERT INTO NOTIFICATION_EVENTS " +
            "(NOTIFICATION_ID, EVENT_TYPE, ACCOUNT_ID, PAYLOAD, STATUS, CREATED_AT, UPDATED_AT) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            notificationId, type, accountId, payload, status,
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now())
        );
    }

    public void updateStatus(String notificationId, String status) {
        jdbcTemplate.update(
            "UPDATE NOTIFICATION_EVENTS SET STATUS = ?, UPDATED_AT = ? WHERE NOTIFICATION_ID = ?",
            status, Timestamp.from(Instant.now()), notificationId
        );
    }

    public List<Map<String, Object>> findByAccountId(String accountId, int limit) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM NOTIFICATION_EVENTS WHERE ACCOUNT_ID = ? " +
            "ORDER BY CREATED_AT DESC FETCH FIRST ? ROWS ONLY",
            accountId, limit
        );
    }

    public List<Map<String, Object>> findPendingNotifications(int batchSize) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM NOTIFICATION_EVENTS WHERE STATUS = 'PENDING' " +
            "AND CREATED_AT > ? ORDER BY CREATED_AT ASC FETCH FIRST ? ROWS ONLY",
            Timestamp.from(Instant.now().minusSeconds(3600)), batchSize
        );
    }
}
