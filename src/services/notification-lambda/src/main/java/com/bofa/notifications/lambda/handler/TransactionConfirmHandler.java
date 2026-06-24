package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.lambda.messaging.MessageDeduplication;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.persistence.PostgresAuditLogRepository;
import com.bofa.notifications.lambda.persistence.PostgresNotificationRepository;
import com.bofa.notifications.lambda.service.NotificationCircuitBreaker;
import com.bofa.notifications.lambda.service.TransactionConfirmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWS Lambda handler for transaction confirmation processing.
 * Triggered by SQS FIFO queue: bofa-txn-confirmations-{env}.fifo
 *
 * Replaces: MqMessageListener.onMessage() -> case "TRANSACTION_CONFIRM"
 *
 * Reg E compliance: Electronic fund transfer confirmations.
 *
 * Handler reference in Terraform:
 *   com.bofa.notifications.lambda.handler.TransactionConfirmHandler::handleRequest
 */
public class TransactionConfirmHandler implements RequestHandler<SQSEvent, String> {

    private static final Logger log = LoggerFactory.getLogger(TransactionConfirmHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final TransactionConfirmService txnService;
    private final MessageDeduplication deduplication;
    private final NotificationCircuitBreaker circuitBreaker;

    public TransactionConfirmHandler() {
        PostgresNotificationRepository notificationRepo = new PostgresNotificationRepository();
        PostgresAuditLogRepository auditLogRepo = new PostgresAuditLogRepository();
        this.txnService = new TransactionConfirmService(notificationRepo, auditLogRepo);
        this.deduplication = new MessageDeduplication();
        this.circuitBreaker = new NotificationCircuitBreaker();
    }

    public TransactionConfirmHandler(TransactionConfirmService txnService,
                                      MessageDeduplication deduplication,
                                      NotificationCircuitBreaker circuitBreaker) {
        this.txnService = txnService;
        this.deduplication = deduplication;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String handleRequest(SQSEvent sqsEvent, Context context) {
        log.info("Transaction confirm handler invoked: {} message(s), requestId={}",
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

                circuitBreaker.execute("txn-db",
                        () -> { txnService.sendConfirmation(event); return null; },
                        () -> {
                            log.error("CIRCUIT BREAKER OPEN: Transaction confirm degraded for account {}",
                                    event.getAccountId());
                            return null;
                        });

                processed++;

            } catch (Exception e) {
                log.error("Failed to process transaction confirmation: id={}", messageId, e);
                throw new RuntimeException("Transaction confirmation failed — will retry via SQS", e);
            }
        }

        String result = String.format("Processed: %d, Duplicates: %d, Total: %d",
                processed, duplicates, sqsEvent.getRecords().size());
        log.info("Transaction confirm handler complete: {}", result);
        return result;
    }
}
