package com.bofa.notifications.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable audit log repository for compliance and regulatory requirements.
 * Table: AUDIT_LOG (append-only, no UPDATE or DELETE permitted).
 * 
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
            "INSERT INTO AUDIT_LOG " +
            "(AUDIT_ID, EVENT_TYPE, ACCOUNT_ID, DESCRIPTION, REFERENCE_ID, " +
            "CREATED_AT, CREATED_BY, SOURCE_SYSTEM) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            auditId, eventType, accountId, description, referenceId,
            Timestamp.from(Instant.now()), "NOTIFICATION_SERVICE", "NOTIFICATION_SVC_V3"
        );
    }

    public List<Map<String, Object>> getAuditTrail(String accountId,
                                                     Instant from, Instant to) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM AUDIT_LOG WHERE ACCOUNT_ID = ? " +
            "AND CREATED_AT BETWEEN ? AND ? ORDER BY CREATED_AT ASC",
            accountId, Timestamp.from(from), Timestamp.from(to)
        );
    }

    public List<Map<String, Object>> getByReferenceId(String referenceId) {
        return jdbcTemplate.queryForList(
            "SELECT * FROM AUDIT_LOG WHERE REFERENCE_ID = ? ORDER BY CREATED_AT ASC",
            referenceId
        );
    }
}
