package com.bofa.notifications.lambda.messaging;

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

/**
 * Application-level deduplication for exactly-once processing.
 *
 * While SQS FIFO provides exactly-once delivery within its deduplication window
 * (5 minutes), we implement application-level dedup for:
 * 1. Cross-window deduplication (messages replayed after > 5 min)
 * 2. Idempotent processing guarantee (replacing MQ XA transactions)
 * 3. Audit trail of processed message IDs
 *
 * Uses a PostgreSQL table with unique constraint on message_id.
 */
public class MessageDeduplication {

    private static final Logger log = LoggerFactory.getLogger(MessageDeduplication.class);
    private final DataSource dataSource;

    public MessageDeduplication() {
        this.dataSource = AwsConfig.dataSource();
    }

    public MessageDeduplication(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Attempts to claim a message for processing.
     * Returns true if this is the first time we've seen this message ID.
     * Uses INSERT ... ON CONFLICT DO NOTHING for atomic check-and-claim.
     */
    public boolean tryClaimMessage(String messageId, String eventType, String accountId) {
        String sql = "INSERT INTO processed_messages (message_id, event_type, account_id, processed_at) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (message_id) DO NOTHING";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, messageId);
            stmt.setString(2, eventType);
            stmt.setString(3, accountId);
            stmt.setTimestamp(4, Timestamp.from(Instant.now()));
            int rows = stmt.executeUpdate();
            if (rows == 0) {
                log.debug("Duplicate message detected: id={}", messageId);
            }
            return rows > 0;
        } catch (SQLException e) {
            log.error("Deduplication check failed for message: {}", messageId, e);
            return false;
        }
    }

    /**
     * Checks if a message has already been processed.
     */
    public boolean isProcessed(String messageId) {
        String sql = "SELECT 1 FROM processed_messages WHERE message_id = ? LIMIT 1";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, messageId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            log.error("Failed to check processed status: {}", messageId, e);
            return false;
        }
    }
}
