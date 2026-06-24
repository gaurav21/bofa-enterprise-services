package com.bofa.notifications.lambda.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.bofa.notifications.lambda.messaging.MessageDeduplication;
import com.bofa.notifications.lambda.model.NotificationEvent;
import com.bofa.notifications.lambda.service.NotificationCircuitBreaker;
import com.bofa.notifications.lambda.service.TransactionConfirmService;
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
class TransactionConfirmHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Mock private TransactionConfirmService txnService;
    @Mock private MessageDeduplication deduplication;
    @Mock private NotificationCircuitBreaker circuitBreaker;
    @Mock private Context context;

    private TransactionConfirmHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TransactionConfirmHandler(txnService, deduplication, circuitBreaker);
        when(context.getAwsRequestId()).thenReturn("test-req-txn");
    }

    @Test
    void handleRequest_processesTransactionConfirmation() throws Exception {
        NotificationEvent event = createTxnEvent();
        SQSEvent sqsEvent = createSqsEvent(event, "txn-msg-001");

        when(deduplication.tryClaimMessage(eq("txn-msg-001"), eq("TRANSACTION_CONFIRM"), eq("ACC-789")))
                .thenReturn(true);
        when(circuitBreaker.execute(eq("txn-db"), any(Supplier.class), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<?> supplier = inv.getArgument(1);
                    return supplier.get();
                });

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 1, Duplicates: 0, Total: 1", result);
    }

    @Test
    void handleRequest_skipsDuplicates() throws Exception {
        NotificationEvent event = createTxnEvent();
        SQSEvent sqsEvent = createSqsEvent(event, "txn-dup");

        when(deduplication.tryClaimMessage(eq("txn-dup"), eq("TRANSACTION_CONFIRM"), eq("ACC-789")))
                .thenReturn(false);

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 0, Duplicates: 1, Total: 1", result);
    }

    @Test
    void handleRequest_multipleMixedMessages() throws Exception {
        NotificationEvent event1 = createTxnEvent();
        event1.setAccountId("ACC-001");
        NotificationEvent event2 = createTxnEvent();
        event2.setAccountId("ACC-002");

        SQSEvent sqsEvent = new SQSEvent();
        SQSEvent.SQSMessage msg1 = new SQSEvent.SQSMessage();
        msg1.setMessageId("m1");
        msg1.setBody(MAPPER.writeValueAsString(event1));
        SQSEvent.SQSMessage msg2 = new SQSEvent.SQSMessage();
        msg2.setMessageId("m2");
        msg2.setBody(MAPPER.writeValueAsString(event2));
        sqsEvent.setRecords(List.of(msg1, msg2));

        when(deduplication.tryClaimMessage(eq("m1"), any(), any())).thenReturn(true);
        when(deduplication.tryClaimMessage(eq("m2"), any(), any())).thenReturn(false);
        when(circuitBreaker.execute(eq("txn-db"), any(Supplier.class), any(Supplier.class)))
                .thenAnswer(inv -> {
                    Supplier<?> supplier = inv.getArgument(1);
                    return supplier.get();
                });

        String result = handler.handleRequest(sqsEvent, context);

        assertEquals("Processed: 1, Duplicates: 1, Total: 2", result);
    }

    private NotificationEvent createTxnEvent() {
        NotificationEvent event = new NotificationEvent();
        event.setEventType("TRANSACTION_CONFIRM");
        event.setAccountId("ACC-789");
        event.setTransactionId("TXN-012");
        event.setTransactionType("DEBIT");
        event.setAmount(150.00);
        event.setCurrency("USD");
        event.setDescription("Purchase at Store");
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
