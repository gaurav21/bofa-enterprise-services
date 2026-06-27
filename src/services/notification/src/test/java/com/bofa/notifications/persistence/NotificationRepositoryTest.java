package com.bofa.notifications.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private NotificationRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new NotificationRepository(jdbcTemplate);
    }

    @Test
    void saveNotification_usesPostgresqlSyntax() {
        repository.saveNotification("n-001", "FRAUD_ALERT", "ACC001",
                "{\"test\":true}", "PENDING");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(),
                eq("n-001"), eq("FRAUD_ALERT"), eq("ACC001"),
                eq("{\"test\":true}"), eq("PENDING"), any(), any());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("notification_events"),
                "Table name should be lowercase (PostgreSQL convention)");
        assertFalse(sql.contains("NOTIFICATION_EVENTS"),
                "Should not use Oracle-style uppercase table name");
    }

    @Test
    void findByAccountId_usesLimitNotFetchFirst() {
        repository.findByAccountId("ACC001", 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(),
                eq("ACC001"), eq(10));

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("LIMIT"), "Should use PostgreSQL LIMIT syntax");
        assertFalse(sql.contains("FETCH FIRST"),
                "Should not use Oracle FETCH FIRST syntax");
    }

    @Test
    void findPendingNotifications_usesLimitNotFetchFirst() {
        repository.findPendingNotifications(50);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(),
                any(java.sql.Timestamp.class), eq(50));

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("LIMIT"), "Should use PostgreSQL LIMIT syntax");
    }

    @Test
    void updateStatus_usesLowercaseTable() {
        repository.updateStatus("n-001", "DELIVERED");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), eq("DELIVERED"), any(), eq("n-001"));

        assertTrue(sqlCaptor.getValue().contains("notification_events"));
    }
}
