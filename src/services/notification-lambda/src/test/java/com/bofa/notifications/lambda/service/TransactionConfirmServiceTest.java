package com.bofa.notifications.lambda.service;

import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresAuditLogRepository;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionConfirmServiceTest {

    @Mock private PostgresNotificationRepository notificationRepo;
    @Mock private PostgresAuditLogRepository auditLogRepo;

    private TransactionConfirmService service;

    @BeforeEach
    void setUp() {
        service = new TransactionConfirmService(notificationRepo, auditLogRepo);
    }

    @Test
    void sendConfirmation_savesWithDeliveredStatus() {
        NotificationEvent event = createTxnEvent();

        service.sendConfirmation(event);

        verify(notificationRepo).saveNotification(
                anyString(), eq("TRANSACTION_CONFIRM"), eq("ACC-789"),
                anyString(), eq("DELIVERED"));
    }

    @Test
    void sendConfirmation_logsAuditEvent() {
        NotificationEvent event = createTxnEvent();

        service.sendConfirmation(event);

        verify(auditLogRepo).logEvent(
                eq("TXN_CONFIRM_SENT"), eq("ACC-789"),
                contains("DEBIT"), anyString());
    }

    @Test
    void sendConfirmation_defaultsCurrencyToUsd() {
        NotificationEvent event = createTxnEvent();
        event.setCurrency(null);

        service.sendConfirmation(event);

        verify(auditLogRepo).logEvent(
                anyString(), anyString(), contains("USD"), anyString());
    }

    @Test
    void sendConfirmation_handlesNullDescription() {
        NotificationEvent event = createTxnEvent();
        event.setDescription(null);

        service.sendConfirmation(event);

        verify(notificationRepo).saveNotification(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private NotificationEvent createTxnEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("TRANSACTION_CONFIRM");
        event.setAccountId("ACC-789");
        event.setTransactionId("TXN-012");
        event.setTransactionType("DEBIT");
        event.setAmount(150.00);
        event.setCurrency("USD");
        event.setDescription("Purchase at Store");
        return event;
    }
}
