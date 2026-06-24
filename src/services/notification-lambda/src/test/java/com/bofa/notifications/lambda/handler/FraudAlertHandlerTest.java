package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.lambda.messaging.MessageDeduplication;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.service.FraudNotificationService;
import com.bofa.notifications.lambda.service.NotificationCircuitBreaker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudAlertHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Mock private FraudNotificationService fraudService;
    @Mock private MessageDeduplication deduplication;
    @Mock private NotificationCircuitBreaker circuitBreaker;
    @Mock private Context context;

    private FraudAlertHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FraudAlertHandler(fraudService, deduplication, circuitBreaker);
        when(context.getAwsRequestId()).thenReturn("test-request-id");
    }

    @Test
    void handleRequest_processesNewMessage() throws Exception {
        NotificationEvent event = createFraudEvent();
        SQSEvent sqsEvent = createSqsEvent(event, "msg-001");

        when(deduplication.tryClaimMessage(eq("msg-001"), eq("FRAUD_ALERT"), eq("ACC-123")))
                .thenReturn(true);
        when(circuitBreaker.execute(eq("fraud-db"), any(Supplier.class), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<?> supplier = inv.getArgument(1);
                    return supplier.get();
                });

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 1, Duplicates: 0, Total: 1", result);
        verify(deduplication).tryClaimMessage("msg-001", "FRAUD_ALERT", "ACC-123");
    }

    @Test
    void handleRequest_skipsDuplicateMessage() throws Exception {
        NotificationEvent event = createFraudEvent();
        SQSEvent sqsEvent = createSqsEvent(event, "msg-dup");

        when(deduplication.tryClaimMessage(eq("msg-dup"), eq("FRAUD_ALERT"), eq("ACC-123")))
                .thenReturn(false);

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 0, Duplicates: 1, Total: 1", result);
        verify(circuitBreaker, never()).execute(anyString(), any(Supplier.class), any(Supplier.class));
    }

    @Test
    void handleRequest_throwsOnProcessingFailure() throws Exception {
        NotificationEvent event = createFraudEvent();
        SQSEvent sqsEvent = createSqsEvent(event, "msg-fail");

        when(deduplication.tryClaimMessage(eq("msg-fail"), eq("FRAUD_ALERT"), eq("ACC-123")))
                .thenReturn(true);
        when(circuitBreaker.execute(eq("fraud-db"), any(Supplier.class), any(Supplier.class)))
                .thenThrow(new RuntimeException("DB down"));

        assertThrows(RuntimeException.class, () -> handler.handleRequest(sqsEvent, context));
    }

    @Test
    void handleRequest_emptyBatch() {
        SQSEvent sqsEvent = new SQSEvent();
        sqsEvent.setRecords(Collections.emptyList());

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 0, Duplicates: 0, Total: 0", result);
    }

    private NotificationEvent createFraudEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("FRAUD_ALERT");
        event.setAccountId("ACC-123");
        event.setTransactionId("TXN-456");
        event.setAmount(999.99);
        event.setMerchantName("Suspicious Merchant");
        event.setSeverity("HIGH");
        return event;
    }

    private SQSEvent createSqsEvent(NotificationEvent event, String messageId) throws Exception {
        SQSEvent sqsEvent = new SQSEvent();
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setMessageId(messageId);
        message.setBody(MAPPER.writeValueAsString(event));
        sqsEvent.setRecords(List.of(message));
        return sqsEvent;
    }
}
