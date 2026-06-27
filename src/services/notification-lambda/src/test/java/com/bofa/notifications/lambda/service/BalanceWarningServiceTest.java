package com.bofa.notifications.lambda.service;

import com.bofa.notifications.lambda.model.NotificationEvent;
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
class BalanceWarningServiceTest {

    @Mock private PostgresNotificationRepository notificationRepo;

    private BalanceWarningService service;

    @BeforeEach
    void setUp() {
        service = new BalanceWarningService(notificationRepo, 4);
    }

    @Test
    void sendBalanceWarning_firstWarningSucceeds() {
        NotificationEvent event = createBalanceEvent();

        boolean result = service.sendBalanceWarning(event);

        assertTrue(result);
        verify(notificationRepo).saveNotification(
                anyString(), eq("BALANCE_WARNING"), eq("ACC-555"),
                anyString(), eq("PENDING"));
    }

    @Test
    void sendBalanceWarning_secondWarningInCooldown() {
        NotificationEvent event = createBalanceEvent();

        assertTrue(service.sendBalanceWarning(event));
        assertFalse(service.sendBalanceWarning(event));

        verify(notificationRepo, times(1)).saveNotification(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendBalanceWarning_differentAccountsNotAffected() {
        NotificationEvent event1 = createBalanceEvent();
        event1.setAccountId("ACC-001");

        NotificationEvent event2 = createBalanceEvent();
        event2.setAccountId("ACC-002");

        assertTrue(service.sendBalanceWarning(event1));
        assertTrue(service.sendBalanceWarning(event2));

        verify(notificationRepo, times(2)).saveNotification(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendBalanceWarning_differentWarningTypesNotAffected() {
        NotificationEvent event1 = createBalanceEvent();
        event1.setWarningType("LOW_BALANCE");

        NotificationEvent event2 = createBalanceEvent();
        event2.setWarningType("OVERDRAFT");

        assertTrue(service.sendBalanceWarning(event1));
        assertTrue(service.sendBalanceWarning(event2));
    }

    private NotificationEvent createBalanceEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("BALANCE_WARNING");
        event.setAccountId("ACC-555");
        event.setCurrentBalance(50.00);
        event.setThreshold(100.00);
        event.setWarningType("LOW_BALANCE");
        return event;
    }
}
