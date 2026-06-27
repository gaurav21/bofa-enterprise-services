package com.bofa.notifications.queue.sqs;

import com.bofa.notifications.queue.MessageOrderGuarantee;
import com.bofa.notifications.service.BalanceWarningService;
import com.bofa.notifications.service.FraudNotificationService;
import com.bofa.notifications.service.TransactionConfirmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqsMessageProcessorTest {

    private SqsMessageProcessor processor;
    private FraudNotificationService fraudService;
    private TransactionConfirmService txnConfirmService;
    private BalanceWarningService balanceService;
    private MessageOrderGuarantee orderGuarantee;
    private IdempotencyGuard idempotencyGuard;

    @BeforeEach
    void setUp() {
        fraudService = mock(FraudNotificationService.class);
        txnConfirmService = mock(TransactionConfirmService.class);
        balanceService = mock(BalanceWarningService.class);
        orderGuarantee = mock(MessageOrderGuarantee.class);
        idempotencyGuard = mock(IdempotencyGuard.class);

        when(orderGuarantee.canProcess(anyString(), anyLong())).thenReturn(true);
        when(idempotencyGuard.isDuplicate(anyString())).thenReturn(false);

        processor = new SqsMessageProcessor(
                new ObjectMapper(),
                fraudService,
                txnConfirmService,
                balanceService,
                orderGuarantee,
                idempotencyGuard
        );
    }

    @Test
    void processMessage_fraudAlert_routesToFraudService() {
        String body = "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"," +
                "\"transactionId\":\"TXN001\",\"amount\":999.99," +
                "\"merchantName\":\"SuspiciousMerchant\",\"severity\":\"HIGH\"," +
                "\"sequenceNumber\":1}";

        processor.processMessage("msg-001", body, Map.of());

        verify(fraudService).processFraudAlert("ACC001", "TXN001", 999.99,
                "SuspiciousMerchant", "HIGH");
        verify(orderGuarantee).markProcessed("ACC001", 1L);
        verify(idempotencyGuard).markProcessed("msg-001");
    }

    @Test
    void processMessage_transactionConfirm_routesToTxnService() {
        String body = "{\"eventType\":\"TRANSACTION_CONFIRM\",\"accountId\":\"ACC002\"," +
                "\"transactionId\":\"TXN002\",\"transactionType\":\"DEBIT\"," +
                "\"amount\":50.00,\"currency\":\"USD\",\"description\":\"Coffee Shop\"}";

        processor.processMessage("msg-002", body, Map.of());

        verify(txnConfirmService).sendConfirmation("ACC002", "TXN002", "DEBIT",
                50.00, "USD", "Coffee Shop");
    }

    @Test
    void processMessage_balanceWarning_routesToBalanceService() {
        String body = "{\"eventType\":\"BALANCE_WARNING\",\"accountId\":\"ACC003\"," +
                "\"currentBalance\":25.50,\"threshold\":100.00," +
                "\"warningType\":\"LOW_BALANCE\"}";

        processor.processMessage("msg-003", body, Map.of());

        verify(balanceService).sendBalanceWarning("ACC003", 25.50, 100.00, "LOW_BALANCE");
    }

    @Test
    void processMessage_duplicateMessage_skipped() {
        when(idempotencyGuard.isDuplicate("dedup-123")).thenReturn(true);

        String body = "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"," +
                "\"transactionId\":\"TXN001\",\"amount\":100.0," +
                "\"merchantName\":\"Merchant\",\"severity\":\"HIGH\"}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("MessageDeduplicationId", "dedup-123");

        processor.processMessage("msg-004", body, attrs);

        verifyNoInteractions(fraudService);
        verifyNoInteractions(txnConfirmService);
        verifyNoInteractions(balanceService);
    }

    @Test
    void processMessage_outOfOrder_skipped() {
        when(orderGuarantee.canProcess("ACC001", 5L)).thenReturn(false);

        String body = "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"," +
                "\"transactionId\":\"TXN001\",\"amount\":100.0," +
                "\"merchantName\":\"Merchant\",\"severity\":\"HIGH\"," +
                "\"sequenceNumber\":5}";

        processor.processMessage("msg-005", body, Map.of());

        verifyNoInteractions(fraudService);
    }

    @Test
    void processMessage_unknownEventType_noServiceCalled() {
        String body = "{\"eventType\":\"UNKNOWN_TYPE\",\"accountId\":\"ACC001\"}";

        processor.processMessage("msg-006", body, Map.of());

        verifyNoInteractions(fraudService);
        verifyNoInteractions(txnConfirmService);
        verifyNoInteractions(balanceService);
    }

    @Test
    void processMessage_invalidJson_throwsException() {
        assertThrows(RuntimeException.class, () ->
                processor.processMessage("msg-007", "not-valid-json", Map.of()));
    }

    @Test
    void processMessage_usesDeduplicationIdFromAttributes() {
        String body = "{\"eventType\":\"FRAUD_ALERT\",\"accountId\":\"ACC001\"," +
                "\"transactionId\":\"TXN001\",\"amount\":100.0," +
                "\"merchantName\":\"Merchant\",\"severity\":\"HIGH\"}";

        Map<String, String> attrs = new HashMap<>();
        attrs.put("MessageDeduplicationId", "custom-dedup-id");

        processor.processMessage("msg-008", body, attrs);

        verify(idempotencyGuard).isDuplicate("custom-dedup-id");
        verify(idempotencyGuard).markProcessed("custom-dedup-id");
    }
}
