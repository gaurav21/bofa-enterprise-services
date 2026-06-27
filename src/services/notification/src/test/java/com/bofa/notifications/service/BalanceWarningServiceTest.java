package com.bofa.notifications.service;

import com.bofa.notifications.persistence.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BalanceWarningServiceTest {

    private NotificationRepository notificationRepo;
    private BalanceWarningService service;

    @BeforeEach
    void setUp() {
        notificationRepo = mock(NotificationRepository.class);
        service = new BalanceWarningService(notificationRepo);
        ReflectionTestUtils.setField(service, "cooldownHours", 4);
    }

    @Test
    void sendBalanceWarning_firstNotification_sendsSuccessfully() {
        boolean sent = service.sendBalanceWarning("ACC001", 25.50, 100.00, "LOW_BALANCE");

        assertTrue(sent);
        verify(notificationRepo).saveNotification(
                anyString(), eq("BALANCE_WARNING"), eq("ACC001"), anyString(), eq("PENDING"));
    }

    @Test
    void sendBalanceWarning_inCooldown_skips() {
        service.sendBalanceWarning("ACC001", 25.50, 100.00, "LOW_BALANCE");
        boolean sent = service.sendBalanceWarning("ACC001", 20.00, 100.00, "LOW_BALANCE");

        assertFalse(sent);
        verify(notificationRepo, times(1)).saveNotification(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendBalanceWarning_differentTypes_bothSend() {
        boolean sent1 = service.sendBalanceWarning("ACC001", 25.50, 100.00, "LOW_BALANCE");
        boolean sent2 = service.sendBalanceWarning("ACC001", -10.00, 0.00, "OVERDRAFT");

        assertTrue(sent1);
        assertTrue(sent2);
        verify(notificationRepo, times(2)).saveNotification(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendBalanceWarning_differentAccounts_bothSend() {
        boolean sent1 = service.sendBalanceWarning("ACC001", 25.50, 100.00, "LOW_BALANCE");
        boolean sent2 = service.sendBalanceWarning("ACC002", 30.00, 100.00, "LOW_BALANCE");

        assertTrue(sent1);
        assertTrue(sent2);
    }
}
