package com.bofa.notifications.lambda.service;

import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Balance warning processor — ported from Spring Boot BalanceWarningService.
 *
 * Regulatory: Reg DD requires disclosure of overdraft fees before they occur.
 * Implements rate limiting to avoid notification fatigue.
 *
 * Changes from Spring Boot version:
 * - @Value("${notification.balance.cooldown-hours}") -> environment variable
 * - @Transactional -> explicit connection management in repository
 * - In-memory cooldown map is acceptable for Lambda because each invocation
 *   processes one batch; for cross-invocation dedup we rely on the DB check.
 *
 * Note: In Lambda, the in-memory cooldown map persists across warm invocations
 * but resets on cold starts. The database-backed deduplication in
 * MessageDeduplication provides the durable guarantee.
 */
public class BalanceWarningService {

    private static final Logger log = LoggerFactory.getLogger(BalanceWarningService.class);

    private final int cooldownHours;
    private final PostgresNotificationRepository notificationRepo;
    private final ConcurrentHashMap<String, Instant> lastNotificationTime = new ConcurrentHashMap<>();

    public BalanceWarningService(PostgresNotificationRepository notificationRepo) {
        this.notificationRepo = notificationRepo;
        String cooldownEnv = System.getenv("BALANCE_COOLDOWN_HOURS");
        this.cooldownHours = cooldownEnv != null ? Integer.parseInt(cooldownEnv) : 4;
    }

    public BalanceWarningService(PostgresNotificationRepository notificationRepo, int cooldownHours) {
        this.notificationRepo = notificationRepo;
        this.cooldownHours = cooldownHours;
    }

    public boolean sendBalanceWarning(NotificationEvent event) {
        if (isInCooldown(event.getAccountId(), event.getWarningType())) {
            log.debug("Skipping balance warning for account {} - in cooldown period",
                    event.getAccountId());
            return false;
        }

        log.info("Sending balance warning: account={}, balance={}, threshold={}, type={}",
                event.getAccountId(), event.getCurrentBalance(),
                event.getThreshold(), event.getWarningType());

        String notificationId = UUID.randomUUID().toString();

        Map<String, Object> payload = Map.of(
                "notificationId", notificationId,
                "type", "BALANCE_WARNING",
                "warningType", event.getWarningType(),
                "accountId", event.getAccountId(),
                "currentBalance", event.getCurrentBalance(),
                "threshold", event.getThreshold(),
                "timestamp", Instant.now().toString()
        );

        notificationRepo.saveNotification(notificationId, "BALANCE_WARNING",
                event.getAccountId(), payload.toString(), "PENDING");

        lastNotificationTime.put(
                event.getAccountId() + ":" + event.getWarningType(),
                Instant.now());
        return true;
    }

    private boolean isInCooldown(String accountId, String warningType) {
        String key = accountId + ":" + warningType;
        Instant lastSent = lastNotificationTime.get(key);
        if (lastSent == null) return false;
        return lastSent.plus(cooldownHours, ChronoUnit.HOURS).isAfter(Instant.now());
    }
}
