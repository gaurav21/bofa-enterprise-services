package com.bofa.notifications.service;

import com.bofa.notifications.persistence.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Balance warning notifications for low balance and overdraft events.
 * Implements rate limiting to avoid notification fatigue.
 * Regulatory: Reg DD requires disclosure of overdraft fees before they occur.
 *
 * Migration changes:
 *   - @Transactional now backed by PostgreSQL (was Oracle)
 *   - Rate-limiting ConcurrentHashMap is per-Lambda-instance; acceptable
 *     since SQS FIFO routes same MessageGroupId to same consumer
 */
@Service
public class BalanceWarningService {

    private static final Logger log = LoggerFactory.getLogger(BalanceWarningService.class);

    @Value("${notification.balance.cooldown-hours:4}")
    private int cooldownHours;

    private final NotificationRepository notificationRepo;
    private final ConcurrentHashMap<String, Instant> lastNotificationTime = new ConcurrentHashMap<>();

    public BalanceWarningService(NotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
    }

    @Transactional
    public boolean sendBalanceWarning(String accountId, double currentBalance,
                                       double threshold, String warningType) {
        if (isInCooldown(accountId, warningType)) {
            log.debug("Skipping balance warning for account {} - in cooldown period", accountId);
            return false;
        }

        log.info("Sending balance warning: account={}, balance={}, threshold={}, type={}",
                accountId, currentBalance, threshold, warningType);

        String notificationId = UUID.randomUUID().toString();

        Map<String, Object> payload = Map.of(
            "notificationId", notificationId,
            "type", "BALANCE_WARNING",
            "warningType", warningType,
            "accountId", accountId,
            "currentBalance", currentBalance,
            "threshold", threshold,
            "timestamp", Instant.now().toString()
        );

        notificationRepo.saveNotification(notificationId, "BALANCE_WARNING",
                accountId, payload.toString(), "PENDING");

        lastNotificationTime.put(accountId + ":" + warningType, Instant.now());
        return true;
    }

    private boolean isInCooldown(String accountId, String warningType) {
        String key = accountId + ":" + warningType;
        Instant lastSent = lastNotificationTime.get(key);
        if (lastSent == null) return false;
        return lastSent.plus(cooldownHours, ChronoUnit.HOURS).isAfter(Instant.now());
    }
}
