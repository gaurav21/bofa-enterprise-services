package com.bofa.notifications.queue.sqs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Idempotency guard for SQS FIFO message processing.
 *
 * Replaces IBM MQ XA transaction semantics with an idempotency table.
 * Uses MessageDeduplicationId as the idempotency key.
 *
 * SQS FIFO provides exactly-once delivery within a 5-minute window,
 * but this guard extends protection across Lambda invocations and
 * handles edge cases during failover.
 */
@Component
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyGuard(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isDuplicate(String deduplicationId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM processed_messages WHERE deduplication_id = ?",
                    Integer.class, deduplicationId);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("Idempotency check failed for id={}, defaulting to process", deduplicationId, e);
            return false;
        }
    }

    public void markProcessed(String deduplicationId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO processed_messages (deduplication_id, processed_at) " +
                    "VALUES (?, ?) ON CONFLICT (deduplication_id) DO NOTHING",
                    deduplicationId, Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.warn("Failed to record processed message: id={}", deduplicationId, e);
        }
    }
}
