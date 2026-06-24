package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.lambda.messaging.MessageDeduplication;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import com.bofa.notifications.lambda.service.BalanceWarningService;
import com.bofa.notifications.lambda.service.NotificationCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS Lambda handler for balance warning processing.
 * Triggered by SQS FIFO queue: bofa-balance-warnings-{env}.fifo
 *
 * Replaces: MqMessageListener.onMessage() -> case "BALANCE_WARNING"
 *
 * Reg DD compliance: Overdraft fee disclosure.
 * Rate limiting: 4-hour cooldown per account per warning type.
 *
 * Handler reference in Terraform:
 *   com.bofa.notifications.lambda.handler.BalanceWarningHandler::handleRequest
 */
public class BalanceWarningHandler implements RequestHandler<SQSEvent, String> {

    private static final Logger log = LoggerFactory.getLogger(BalanceWarningHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final BalanceWarningService balanceService;
    private final MessageDeduplication deduplication;
    private final NotificationCircuitBreaker circuitBreaker;

    public BalanceWarningHandler() {
        PostgresNotificationRepository notificationRepo = new PostgresNotificationRepository();
        this.balanceService = new BalanceWarningService(notificationRepo);
        this.deduplication = new MessageDeduplication();
        this.circuitBreaker = new NotificationCircuitBreaker();
    }

    public BalanceWarningHandler(BalanceWarningService balanceService,
                                  MessageDeduplication deduplication,
                                  NotificationCircuitBreaker circuitBreaker) {
        this.balanceService = balanceService;
        this.deduplication = deduplication;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String handleRequest(SQSEvent sqsEvent, Context context) {
        log.info("Balance warning handler invoked: {} message(s), requestId={}",
                sqsEvent.getRecords().size(), context.getAwsRequestId());

        int processed = 0;
        int skippedCooldown = 0;
        int duplicates = 0;

        for (SQSEvent.SQSMessage message : sqsEvent.getRecords()) {
            String messageId = message.getMessageId();

            try {
                NotificationEvent event = MAPPER.readValue(message.getBody(), NotificationEvent.class);

                if (!deduplication.tryClaimMessage(messageId, event.getEventType(), event.getAccountId())) {
                    duplicates++;
                    continue;
                }

                boolean sent = circuitBreaker.execute("balance-db",
                        () -> balanceService.sendBalanceWarning(event),
                        () -> {
                            log.error("CIRCUIT BREAKER OPEN: Balance warning degraded for account {}",
                                    event.getAccountId());
                            return false;
                        });

                if (sent) {
                    processed++;
                } else {
                    skippedCooldown++;
                }

            } catch (Exception e) {
                log.error("Failed to process balance warning: id={}", messageId, e);
                throw new RuntimeException("Balance warning processing failed — will retry via SQS", e);
            }
        }

        String result = String.format("Processed: %d, Cooldown: %d, Duplicates: %d, Total: %d",
                processed, skippedCooldown, duplicates, sqsEvent.getRecords().size());
        log.info("Balance warning handler complete: {}", result);
        return result;
    }
}
