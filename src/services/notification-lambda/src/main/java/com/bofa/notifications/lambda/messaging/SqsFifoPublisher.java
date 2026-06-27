package com.bofa.notifications.lambda.messaging;

import com.bofa.notifications.lambda.config.AwsConfig;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * SQS FIFO publisher replacing IBM MQ message production.
 *
 * Key migration mappings:
 * - IBM MQ Queue Manager     -> SQS FIFO Queue URL
 * - MQ Correlation ID        -> SQS MessageGroupId (accountId for per-account ordering)
 * - MQ Message ID            -> SQS MessageDeduplicationId (exactly-once delivery)
 * - MQ Message Selector      -> SQS MessageAttributes + filter policies
 * - MQ Backout Queue         -> SQS Dead Letter Queue (redrive policy)
 * - MQ XA Transaction        -> Idempotent processing + DLQ pattern
 *
 * Message ordering: SQS FIFO guarantees ordering within a MessageGroupId.
 * Using accountId as the group ID preserves per-account ordering from IBM MQ.
 */
public class SqsFifoPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsFifoPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final SqsClient sqsClient;
    private final String fraudAlertQueueUrl;
    private final String txnConfirmQueueUrl;
    private final String balanceWarningQueueUrl;

    public SqsFifoPublisher() {
        this.sqsClient = AwsConfig.sqsClient();
        this.fraudAlertQueueUrl = AwsConfig.envOrDefault("SQS_FRAUD_ALERT_QUEUE_URL", "");
        this.txnConfirmQueueUrl = AwsConfig.envOrDefault("SQS_TXN_CONFIRM_QUEUE_URL", "");
        this.balanceWarningQueueUrl = AwsConfig.envOrDefault("SQS_BALANCE_WARNING_QUEUE_URL", "");
    }

    public SqsFifoPublisher(SqsClient sqsClient, String fraudUrl, String txnUrl, String balanceUrl) {
        this.sqsClient = sqsClient;
        this.fraudAlertQueueUrl = fraudUrl;
        this.txnConfirmQueueUrl = txnUrl;
        this.balanceWarningQueueUrl = balanceUrl;
    }

    /**
     * Publishes a notification event to the appropriate SQS FIFO queue.
     * Routes based on eventType, preserving the routing logic from MqMessageListener.
     */
    public String publish(NotificationEvent event) {
        String queueUrl = resolveQueueUrl(event.getEventType());
        if (queueUrl == null || queueUrl.isEmpty()) {
            throw new IllegalArgumentException("No queue configured for event type: " + event.getEventType());
        }

        try {
            String messageBody = MAPPER.writeValueAsString(event);

            Map<String, MessageAttributeValue> attributes = new HashMap<>();
            attributes.put("eventType", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(event.getEventType())
                    .build());
            attributes.put("accountId", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(event.getAccountId())
                    .build());
            if (event.getSeverity() != null) {
                attributes.put("severity", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(event.getSeverity())
                        .build());
            }

            SendMessageRequest request = SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .messageGroupId(event.toMessageGroupId())
                    .messageDeduplicationId(event.toDeduplicationId())
                    .messageAttributes(attributes)
                    .build();

            SendMessageResponse response = sqsClient.sendMessage(request);

            log.info("Published to SQS FIFO: queue={}, messageId={}, groupId={}, sequenceNumber={}",
                    queueUrl, response.messageId(),
                    event.toMessageGroupId(), response.sequenceNumber());

            return response.messageId();

        } catch (Exception e) {
            log.error("Failed to publish to SQS FIFO: eventType={}, accountId={}",
                    event.getEventType(), event.getAccountId(), e);
            throw new RuntimeException("SQS FIFO publish failed", e);
        }
    }

    /**
     * Sends a message directly to the DLQ for manual inspection.
     * Replaces IBM MQ backout queue pattern.
     */
    public void sendToDeadLetterQueue(String queueUrl, String messageBody, String reason) {
        String dlqUrl = queueUrl.replace(".fifo", "-dlq.fifo");
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(dlqUrl)
                    .messageBody(messageBody)
                    .messageGroupId("dlq-manual")
                    .messageDeduplicationId("dlq-" + System.currentTimeMillis())
                    .messageAttributes(Map.of(
                            "dlqReason", MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(reason)
                                    .build()))
                    .build());
            log.warn("Message sent to DLQ: reason={}", reason);
        } catch (Exception e) {
            log.error("Failed to send to DLQ", e);
        }
    }

    private String resolveQueueUrl(String eventType) {
        switch (eventType) {
            case "FRAUD_ALERT": return fraudAlertQueueUrl;
            case "TRANSACTION_CONFIRM": return txnConfirmQueueUrl;
            case "BALANCE_WARNING": return balanceWarningQueueUrl;
            default: return null;
        }
    }
}
