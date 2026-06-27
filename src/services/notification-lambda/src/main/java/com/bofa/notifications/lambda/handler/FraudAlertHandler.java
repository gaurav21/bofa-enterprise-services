package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.lambda.messaging.MessageDeduplication;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresAuditLogRepository;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import com.bofa.notifications.lambda.service.FraudNotificationService;
import com.bofa.notifications.lambda.service.NotificationCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS Lambda handler for fraud alert processing.
 * Triggered by SQS FIFO queue: bofa-fraud-alerts-{env}.fifo
 *
 * Replaces: MqMessageListener.onMessage() -> case "FRAUD_ALERT"
 *
 * SLA: 30-second delivery guarantee.
 * Provisioned concurrency: 100 (eliminates cold start for critical path).
 * Batch size: 1 (preserves per-account ordering from SQS FIFO).
 *
 * Handler reference in Terraform:
 *   com.bofa.notifications.lambda.handler.FraudAlertHandler::handleRequest
 */
public class FraudAlertHandler implements RequestHandler<SQSEvent, String> {

    private static final Logger log = LoggerFactory.getLogger(FraudAlertHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final FraudNotificationService fraudService;
    private final MessageDeduplication deduplication;
    private final NotificationCircuitBreaker circuitBreaker;

    public FraudAlertHandler() {
        PostgresNotificationRepository notificationRepo = new PostgresNotificationRepository();
        PostgresAuditLogRepository auditLogRepo = new PostgresAuditLogRepository();
        this.fraudService = new FraudNotificationService(notificationRepo, auditLogRepo);
        this.deduplication = new MessageDeduplication();
        this.circuitBreaker = new NotificationCircuitBreaker();
    }

    public FraudAlertHandler(FraudNotificationService fraudService,
                              MessageDeduplication deduplication,
                              NotificationCircuitBreaker circuitBreaker) {
        this.fraudService = fraudService;
        this.deduplication = deduplication;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String handleRequest(SQSEvent sqsEvent, Context context) {
        log.info("Fraud alert handler invoked: {} message(s), requestId={}",
                sqsEvent.getRecords().size(), context.getAwsRequestId());

        int processed = 0;
        int duplicates = 0;

        for (SQSEvent.SQSMessage message : sqsEvent.getRecords()) {
            String messageId = message.getMessageId();

            try {
                NotificationEvent event = MAPPER.readValue(message.getBody(), NotificationEvent.class);

                if (!deduplication.tryClaimMessage(messageId, event.getEventType(), event.getAccountId())) {
                    duplicates++;
                    continue;
                }

                circuitBreaker.execute("fraud-db",
                        () -> { fraudService.processFraudAlert(event); return null; },
                        () -> {
                            log.error("CIRCUIT BREAKER OPEN: Fraud alert processing degraded for account {}",
                                    event.getAccountId());
                            return null;
                        });

                processed++;

            } catch (Exception e) {
                log.error("Failed to process fraud alert message: id={}", messageId, e);
                throw new RuntimeException("Fraud alert processing failed — will retry via SQS", e);
            }
        }

        String result = String.format("Processed: %d, Duplicates: %d, Total: %d",
                processed, duplicates, sqsEvent.getRecords().size());
        log.info("Fraud alert handler complete: {}", result);
        return result;
    }
}
