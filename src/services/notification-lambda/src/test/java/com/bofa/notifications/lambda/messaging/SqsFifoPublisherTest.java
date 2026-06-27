package com.bofa.notifications.lambda.messaging;

import com.bofa.notifications.lambda.model.NotificationEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqsFifoPublisherTest {

    @Mock private SqsClient sqsClient;

    private SqsFifoPublisher publisher;

    private static final String FRAUD_URL = "https://sqs.us-east-1.amazonaws.com/123/bofa-fraud-alerts.fifo";
    private static final String TXN_URL = "https://sqs.us-east-1.amazonaws.com/123/bofa-txn-confirmations.fifo";
    private static final String BALANCE_URL = "https://sqs.us-east-1.amazonaws.com/123/bofa-balance-warnings.fifo";

    @BeforeEach
    void setUp() {
        publisher = new SqsFifoPublisher(sqsClient, FRAUD_URL, TXN_URL, BALANCE_URL);
    }

    @Test
    void publish_fraudAlertRoutesToFraudQueue() {
        NotificationEvent event = createEvent("FRAUD_ALERT", "ACC-123");
        mockSqsResponse("msg-001");

        publisher.publish(event);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertEquals(FRAUD_URL, captor.getValue().queueUrl());
    }

    @Test
    void publish_txnConfirmRoutesToTxnQueue() {
        NotificationEvent event = createEvent("TRANSACTION_CONFIRM", "ACC-456");
        mockSqsResponse("msg-002");

        publisher.publish(event);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertEquals(TXN_URL, captor.getValue().queueUrl());
    }

    @Test
    void publish_balanceWarningRoutesToBalanceQueue() {
        NotificationEvent event = createEvent("BALANCE_WARNING", "ACC-789");
        mockSqsResponse("msg-003");

        publisher.publish(event);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertEquals(BALANCE_URL, captor.getValue().queueUrl());
    }

    @Test
    void publish_usesAccountIdAsMessageGroupId() {
        NotificationEvent event = createEvent("FRAUD_ALERT", "ACC-ORDERING");
        mockSqsResponse("msg-004");

        publisher.publish(event);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertEquals("ACC-ORDERING", captor.getValue().messageGroupId());
    }

    @Test
    void publish_setsMessageAttributes() {
        NotificationEvent event = createEvent("FRAUD_ALERT", "ACC-123");
        event.setSeverity("CRITICAL");
        mockSqsResponse("msg-005");

        publisher.publish(event);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        SendMessageRequest req = captor.getValue();
        assertEquals("FRAUD_ALERT", req.messageAttributes().get("eventType").stringValue());
        assertEquals("ACC-123", req.messageAttributes().get("accountId").stringValue());
        assertEquals("CRITICAL", req.messageAttributes().get("severity").stringValue());
    }

    @Test
    void publish_returnsMessageId() {
        NotificationEvent event = createEvent("FRAUD_ALERT", "ACC-123");
        mockSqsResponse("returned-id-abc");

        String result = publisher.publish(event);

        assertEquals("returned-id-abc", result);
    }

    @Test
    void publish_unknownEventTypeThrows() {
        NotificationEvent event = createEvent("UNKNOWN_TYPE", "ACC-123");

        assertThrows(IllegalArgumentException.class, () -> publisher.publish(event));
    }

    @Test
    void publish_setsDeduplicationId() {
        NotificationEvent event = createEvent("FRAUD_ALERT", "ACC-123");
        event.setTransactionId("TXN-999");
        mockSqsResponse("msg-dedup");

        publisher.publish(event);

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertNotNull(captor.getValue().messageDeduplicationId());
        assertTrue(captor.getValue().messageDeduplicationId().contains("FRAUD_ALERT"));
    }

    @Test
    void sendToDeadLetterQueue_sendsToDlqUrl() {
        mockSqsResponse("dlq-msg");

        publisher.sendToDeadLetterQueue(FRAUD_URL, "{\"error\":true}", "poison pill");

        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(captor.capture());
        assertTrue(captor.getValue().queueUrl().contains("-dlq.fifo"));
        assertEquals("dlq-manual", captor.getValue().messageGroupId());
    }

    private NotificationEvent createEvent(String type, String accountId) {
        NotificationEvent event = new NotificationEvent();
        event.setEventType(type);
        event.setAccountId(accountId);
        return event;
    }

    private void mockSqsResponse(String messageId) {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder()
                        .messageId(messageId)
                        .sequenceNumber("seq-1")
                        .build());
    }
}
