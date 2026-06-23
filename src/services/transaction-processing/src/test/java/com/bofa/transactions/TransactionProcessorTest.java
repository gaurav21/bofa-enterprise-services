package com.bofa.transactions;

import com.bofa.transactions.auth.TokenValidator;
import com.bofa.transactions.pii.PiiDetector;
import com.bofa.transactions.pii.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Basic tests for TransactionProcessor.
 * 
 * WARNING: Coverage is critically low at 15.2%.
 * Missing tests for:
 * - ComplianceChecker (OFAC, CTR, structuring detection)
 * - PII detection and masking edge cases
 * - Token expiry and signature validation
 * - Wire transfer limits
 * - Error handling and retry logic
 * - Concurrent transaction processing
 * - Edge cases: negative amounts, Unicode input, null fields
 */
class TransactionProcessorTest {

    private TransactionProcessor processor;

    @Mock
    private TokenValidator tokenValidator;

    private ValidationEngine validationEngine;
    private ComplianceChecker complianceChecker;
    private PiiDetector piiDetector;
    private PiiMasker piiMasker;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        validationEngine = new ValidationEngine();
        complianceChecker = new ComplianceChecker();
        piiDetector = new PiiDetector();
        piiMasker = new PiiMasker();
        processor = new TransactionProcessor(validationEngine, complianceChecker,
                piiDetector, piiMasker, tokenValidator);
    }

    @Test
    void testSimpleTransferApproved() {
        when(tokenValidator.validateToken(anyString())).thenReturn(true);

        Map<String, Object> txn = new HashMap<>();
        txn.put("sourceAccount", "12345678");
        txn.put("destinationAccount", "87654321");
        txn.put("amount", "500.00");
        txn.put("type", "INTERNAL");

        TransactionProcessor.TransactionResult result =
                processor.processTransaction("valid-token", txn);

        assertEquals("APPROVED", result.getStatus());
        assertNotNull(result.getTransactionId());
    }

    @Test
    void testMissingFieldsRejected() {
        when(tokenValidator.validateToken(anyString())).thenReturn(true);

        Map<String, Object> txn = new HashMap<>();
        txn.put("sourceAccount", "12345678");
        // Missing destinationAccount, amount, type

        TransactionProcessor.TransactionResult result =
                processor.processTransaction("valid-token", txn);

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getReason().contains("VALIDATION_FAILED"));
    }

    @Test
    void testInvalidTokenRejected() {
        when(tokenValidator.validateToken(anyString())).thenReturn(false);

        Map<String, Object> txn = new HashMap<>();
        txn.put("sourceAccount", "12345678");
        txn.put("destinationAccount", "87654321");
        txn.put("amount", "100.00");
        txn.put("type", "ACH");

        TransactionProcessor.TransactionResult result =
                processor.processTransaction("bad-token", txn);

        assertEquals("REJECTED", result.getStatus());
        assertTrue(result.getReason().contains("INVALID_TOKEN"));
    }
}
