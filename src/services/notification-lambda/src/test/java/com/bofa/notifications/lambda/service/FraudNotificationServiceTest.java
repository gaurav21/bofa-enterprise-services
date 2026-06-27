package com.bofa.notifications.lambda.service;

import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresAuditLogRepository;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudNotificationServiceTest {

    @Mock private PostgresNotificationRepository notificationRepo;
    @Mock private PostgresAuditLogRepository auditLogRepo;

    private FraudNotificationService service;

    @BeforeEach
    void setUp() {
        service = new FraudNotificationService(notificationRepo, auditLogRepo);
    }

    @Test
    void processFraudAlert_savesNotificationAndAudit() {
        NotificationEvent event = createFraudEvent();

        service.processFraudAlert(event);

        verify(notificationRepo).saveNotification(
                anyString(), eq("FRAUD_ALERT"), eq("ACC-123"), anyString(), eq("PENDING"));
        verify(auditLogRepo).logEvent(
                eq("FRAUD_ALERT_SENT"), eq("ACC-123"), contains("TXN-456"), anyString());
        verify(notificationRepo).updateStatus(anyString(), eq("DELIVERED"));
    }

    @Test
    void processFraudAlert_updatesStatusToDelivered() {
        NotificationEvent event = createFraudEvent();

        service.processFraudAlert(event);

        verify(notificationRepo).updateStatus(anyString(), eq("DELIVERED"));
    }

    @Test
    void processFraudAlert_retriesOnFailure() {
        NotificationEvent event = createFraudEvent();

        doThrow(new RuntimeException("DB timeout"))
                .doNothing()
                .when(notificationRepo)
                .saveNotification(anyString(), anyString(), anyString(), anyString(), anyString());

        service.processFraudAlert(event);

        verify(notificationRepo, times(2)).saveNotification(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void processFraudAlert_failsAfterMaxRetries() {
        NotificationEvent event = createFraudEvent();

        doThrow(new RuntimeException("Persistent failure"))
                .when(notificationRepo)
                .saveNotification(anyString(), anyString(), anyString(), anyString(), anyString());

        assertThrows(RuntimeException.class, () -> service.processFraudAlert(event));
        verify(notificationRepo, times(3)).saveNotification(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private NotificationEvent createFraudEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("FRAUD_ALERT");
        event.setAccountId("ACC-123");
        event.setTransactionId("TXN-456");
        event.setAmount(999.99);
        event.setMerchantName("Suspicious Merchant");
        event.setSeverity("HIGH");
        return event;
    }
}
