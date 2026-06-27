package com.bofa.notifications.service;

import com.bofa.notifications.persistence.AuditLogRepository;
import com.bofa.notifications.persistence.NotificationRepository;
import com.bofa.notifications.resilience.CircuitBreakerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FraudNotificationServiceTest {

    private NotificationRepository notificationRepo;
    private AuditLogRepository auditLogRepo;
    private CircuitBreakerService circuitBreaker;
    private FraudNotificationService service;

    @BeforeEach
    void setUp() {
        notificationRepo = mock(NotificationRepository.class);
        auditLogRepo = mock(AuditLogRepository.class);
        circuitBreaker = new CircuitBreakerService();
        service = new FraudNotificationService(notificationRepo, auditLogRepo, circuitBreaker);
    }

    @Test
    void processFraudAlert_savesNotification() {
        service.processFraudAlert("ACC001", "TXN001", 999.99, "MerchantX", "HIGH");

        verify(notificationRepo).saveNotification(
                anyString(), eq("FRAUD_ALERT"), eq("ACC001"), anyString(), eq("PENDING"));
    }

    @Test
    void processFraudAlert_createsAuditTrail() {
        service.processFraudAlert("ACC001", "TXN001", 500.00, "MerchantY", "CRITICAL");

        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogRepo).logEvent(eq("FRAUD_ALERT_SENT"), eq("ACC001"),
                descCaptor.capture(), anyString());

        assertTrue(descCaptor.getValue().contains("TXN001"));
    }

    @Test
    void processFraudAlert_dbFailure_propagatesException() {
        doThrow(new RuntimeException("DB connection lost"))
                .when(notificationRepo).saveNotification(
                        anyString(), anyString(), anyString(), anyString(), anyString());

        assertThrows(RuntimeException.class, () ->
                service.processFraudAlert("ACC001", "TXN001", 100.00, "Merchant", "LOW"));
    }
}
