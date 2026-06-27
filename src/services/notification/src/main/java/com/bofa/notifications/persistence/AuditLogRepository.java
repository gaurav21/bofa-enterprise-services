package com.bofa.notifications.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable audit log repository for compliance (migrated from Oracle to PostgreSQL).
 *
 * Oracle -> PostgreSQL migration:
 *   - BETWEEN ? AND ? clause preserved (compatible)
 *   - UUID generation unchanged (application-level)
 *   - Timestamp handling unchanged (java.sql.Timestamp)
 *
 * Table: audit_log (append-only, no UPDATE or DELETE permitted).
 * SOX Section 404 requires complete audit trails for all financial notifications.
 * Records are never modified or deleted — retention enforced at database level.
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void logEvent(String eventType, String accountId,
                          String description, String referenceId) {
        String auditId = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO audit_log " +
            "(audit_id, event_type, account_id, description, reference_id, " +
            "created_at, created_by, source_system) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            auditId, eventType, accountId, description, referenceId,
            Timestamp.from(Instant.now()), "NOTIFICATION_SERVICE", "NOTIFICATION_SVC_V4_LAMBDA"
        );
    }

    public List<Map<String, Object>> getAuditTrail(String accountId,
                                                     Instant from, Instant to) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM audit_log WHERE account_id = ? " +
            "AND created_at BETWEEN ? AND ? ORDER BY created_at ASC",
            accountId, Timestamp.from(from), Timestamp.from(to)
        );
    }

    public List<Map<String, Object>> getByReferenceId(String referenceId) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM audit_log WHERE reference_id = ? ORDER BY created_at ASC",
            referenceId
        );
    }
}
