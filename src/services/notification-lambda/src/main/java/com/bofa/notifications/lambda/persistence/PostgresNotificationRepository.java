package com.bofa.notifications.lambda.persistence;

import com.bofa.notifications.lambda.config.AwsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL notification repository replacing Oracle-based NotificationRepository.
 *
 * Migration notes (Oracle -> PostgreSQL):
 * - FETCH FIRST N ROWS ONLY -> LIMIT N (standard SQL, compatible in PG 12+)
 * - Oracle TIMESTAMP -> PostgreSQL TIMESTAMPTZ (timezone-aware)
 * - Table partitioning preserved via PostgreSQL native range partitioning
 * - Oracle sequences -> PostgreSQL SERIAL / GENERATED ALWAYS AS IDENTITY
 * - NVL() -> COALESCE() (ANSI standard)
 *
 * Table: notification_events (partitioned by month for 7-year retention)
 */
public class PostgresNotificationRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresNotificationRepository.class);
    private final DataSource dataSource;

    public PostgresNotificationRepository() {
        this.dataSource = AwsConfig.dataSource();
    }

    public PostgresNotificationRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void saveNotification(String notificationId, String type,
                                  String accountId, String payload, String status) {
        String sql = "INSERT INTO notification_events " +
                "(notification_id, event_type, account_id, payload, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)";

        Timestamp now = Timestamp.from(Instant.now());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, notificationId);
            stmt.setString(2, type);
            stmt.setString(3, accountId);
            stmt.setString(4, payload);
            stmt.setString(5, status);
            stmt.setTimestamp(6, now);
            stmt.setTimestamp(7, now);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save notification: id={}, type={}", notificationId, type, e);
            throw new RuntimeException("Database write failed", e);
        }
    }

    public void updateStatus(String notificationId, String status) {
        String sql = "UPDATE notification_events SET status = ?, updated_at = ? " +
                "WHERE notification_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setTimestamp(2, Timestamp.from(Instant.now()));
            stmt.setString(3, notificationId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update notification status: id={}", notificationId, e);
            throw new RuntimeException("Database update failed", e);
        }
    }

    /**
     * Find notifications by account ID.
     * Oracle migration: FETCH FIRST ? ROWS ONLY -> LIMIT ?
     */
    public List<Map<String, Object>> findByAccountId(String accountId, int limit) {
        String sql = "SELECT notification_id, event_type, account_id, payload, status, " +
                "created_at, updated_at FROM notification_events " +
                "WHERE account_id = ? ORDER BY created_at DESC LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, accountId);
            stmt.setInt(2, limit);
            return extractResults(stmt.executeQuery());
        } catch (SQLException e) {
            log.error("Failed to find notifications for account: {}", accountId, e);
            throw new RuntimeException("Database read failed", e);
        }
    }

    /**
     * Find pending notifications for retry processing.
     * Oracle migration: FETCH FIRST ? ROWS ONLY -> LIMIT ?
     */
    public List<Map<String, Object>> findPendingNotifications(int batchSize) {
        String sql = "SELECT notification_id, event_type, account_id, payload, status, " +
                "created_at, updated_at FROM notification_events " +
                "WHERE status = 'PENDING' AND created_at > ? " +
                "ORDER BY created_at ASC LIMIT ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(3600)));
            stmt.setInt(2, batchSize);
            return extractResults(stmt.executeQuery());
        } catch (SQLException e) {
            log.error("Failed to find pending notifications", e);
            throw new RuntimeException("Database read failed", e);
        }
    }

    /**
     * Check for duplicate notification (idempotency).
     * Used to implement exactly-once processing with SQS FIFO.
     */
    public boolean exists(String notificationId) {
        String sql = "SELECT 1 FROM notification_events WHERE notification_id = ? LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, notificationId);
            return stmt.executeQuery().next();
        } catch (SQLException e) {
            log.error("Failed to check notification existence: {}", notificationId, e);
            return false;
        }
    }

    private List<Map<String, Object>> extractResults(ResultSet rs) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            row.put("notification_id", rs.getString("notification_id"));
            row.put("event_type", rs.getString("event_type"));
            row.put("account_id", rs.getString("account_id"));
            row.put("payload", rs.getString("payload"));
            row.put("status", rs.getString("status"));
            row.put("created_at", rs.getTimestamp("created_at"));
            row.put("updated_at", rs.getTimestamp("updated_at"));
            results.add(row);
        }
        return results;
    }
}
