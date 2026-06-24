package com.bofa.notifications.lambda.persistence;

import com.bofa.notifications.lambda.config.AwsConfig;
import com.bofa.notifications.lambda.model.AuditEntry;
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
 * PostgreSQL audit log repository replacing Oracle-based AuditLogRepository.
 * Append-only table — INSERT operations only (no UPDATE/DELETE).
 *
 * SOX Section 404 requires complete audit trails for all financial notifications.
 * Records are never modified or deleted — retention enforced at database level.
 * Retention: 7 years per GLBA/SOX requirements.
 */
public class PostgresAuditLogRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresAuditLogRepository.class);
    private final DataSource dataSource;

    public PostgresAuditLogRepository() {
        this.dataSource = AwsConfig.dataSource();
    }

    public PostgresAuditLogRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void logEvent(String eventType, String accountId,
                          String description, String referenceId) {
        AuditEntry entry = new AuditEntry(eventType, accountId, description, referenceId);
        insertAuditEntry(entry);
    }

    public void insertAuditEntry(AuditEntry entry) {
        String sql = "INSERT INTO audit_log " +
                "(audit_id, event_type, account_id, description, reference_id, " +
                "created_at, created_by, source_system) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, entry.getAuditId());
            stmt.setString(2, entry.getEventType());
            stmt.setString(3, entry.getAccountId());
            stmt.setString(4, entry.getDescription());
            stmt.setString(5, entry.getReferenceId());
            stmt.setTimestamp(6, Timestamp.from(entry.getCreatedAt()));
            stmt.setString(7, entry.getCreatedBy());
            stmt.setString(8, entry.getSourceSystem());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to write audit log entry: type={}, account={}",
                    entry.getEventType(), entry.getAccountId(), e);
            throw new RuntimeException("Audit log write failed — compliance critical", e);
        }
    }

    /**
     * Retrieve audit trail for an account within a time range.
     * Oracle migration: BETWEEN with Timestamp works identically in PostgreSQL.
     */
    public List<Map<String, Object>> getAuditTrail(String accountId,
                                                     Instant from, Instant to) {
        String sql = "SELECT audit_id, event_type, account_id, description, reference_id, " +
                "created_at, created_by, source_system FROM audit_log " +
                "WHERE account_id = ? AND created_at BETWEEN ? AND ? " +
                "ORDER BY created_at ASC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, accountId);
            stmt.setTimestamp(2, Timestamp.from(from));
            stmt.setTimestamp(3, Timestamp.from(to));
            return extractResults(stmt.executeQuery());
        } catch (SQLException e) {
            log.error("Failed to retrieve audit trail: account={}", accountId, e);
            throw new RuntimeException("Audit trail read failed", e);
        }
    }

    public List<Map<String, Object>> getByReferenceId(String referenceId) {
        String sql = "SELECT audit_id, event_type, account_id, description, reference_id, " +
                "created_at, created_by, source_system FROM audit_log " +
                "WHERE reference_id = ? ORDER BY created_at ASC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, referenceId);
            return extractResults(stmt.executeQuery());
        } catch (SQLException e) {
            log.error("Failed to query audit log by reference: {}", referenceId, e);
            throw new RuntimeException("Audit log query failed", e);
        }
    }

    private List<Map<String, Object>> extractResults(ResultSet rs) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            row.put("audit_id", rs.getString("audit_id"));
            row.put("event_type", rs.getString("event_type"));
            row.put("account_id", rs.getString("account_id"));
            row.put("description", rs.getString("description"));
            row.put("reference_id", rs.getString("reference_id"));
            row.put("created_at", rs.getTimestamp("created_at"));
            row.put("created_by", rs.getString("created_by"));
            row.put("source_system", rs.getString("source_system"));
            results.add(row);
        }
        return results;
    }
}
