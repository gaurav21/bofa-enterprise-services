package com.bofa.notifications.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLogRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private AuditLogRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new AuditLogRepository(jdbcTemplate);
    }

    @Test
    void logEvent_usesLowercaseTableName() {
        repository.logEvent("FRAUD_ALERT_SENT", "ACC001", "Test event", "ref-001");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(),
                anyString(), eq("FRAUD_ALERT_SENT"), eq("ACC001"),
                eq("Test event"), eq("ref-001"), any(),
                eq("NOTIFICATION_SERVICE"), eq("NOTIFICATION_SVC_V4_LAMBDA"));

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("audit_log"), "Should use lowercase table name");
        assertFalse(sql.contains("AUDIT_LOG"), "Should not use Oracle uppercase");
    }

    @Test
    void logEvent_setsLambdaSourceSystem() {
        repository.logEvent("TXN_CONFIRM_SENT", "ACC002", "Confirmation", "ref-002");

        verify(jdbcTemplate).update(anyString(),
                anyString(), anyString(), anyString(),
                anyString(), anyString(), any(),
                eq("NOTIFICATION_SERVICE"),
                eq("NOTIFICATION_SVC_V4_LAMBDA"));
    }

    @Test
    void getAuditTrail_queryIsPostgresCompatible() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-31T23:59:59Z");

        repository.getAuditTrail("ACC001", from, to);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(),
                eq("ACC001"), any(), any());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("audit_log"));
        assertTrue(sql.contains("BETWEEN"));
    }
}
