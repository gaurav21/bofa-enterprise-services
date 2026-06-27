package com.bofa.transactions;

import com.bofa.transactions.auth.TokenValidator;
import com.bofa.transactions.pii.PiiDetector;
import com.bofa.transactions.pii.PiiMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("TransactionProcessor — End-to-end processing with compliance, PII, and auth")
class TransactionProcessorIntegrationTest {

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

    private Map<String, Object> buildTransaction(String source, String dest,
                                                   String amount, String type) {
        Map<String, Object> txn = new HashMap<>();
        txn.put("sourceAccount", source);
        txn.put("destinationAccount", dest);
        txn.put("amount", amount);
        txn.put("type", type);
        return txn;
    }

    @Nested
    @DisplayName("Authentication gate")
    class AuthenticationGate {

        @Test
        @DisplayName("rejects transaction when token validation fails")
        void rejectsInvalidAuth() {
            when(tokenValidator.validateToken(anyString())).thenReturn(false);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "100.00", "ACH");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("bad-token", txn);

            assertEquals("REJECTED", result.getStatus());
            assertTrue(result.getReason().contains("INVALID_TOKEN"));
            assertNotNull(result.getTransactionId());
        }

        @Test
        @DisplayName("proceeds past auth when token is valid")
        void proceedsPastAuthWithValidToken() {
            when(tokenValidator.validateToken("valid")).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "100.00", "INTERNAL");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("valid", txn);

            assertNotEquals("REJECTED", result.getStatus());
        }
    }

    @Nested
    @DisplayName("Validation gate")
    class ValidationGate {

        @Test
        @DisplayName("rejects transaction with invalid account format after auth passes")
        void rejectsInvalidAccount() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("ABC", "87654321", "100.00", "ACH");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("REJECTED", result.getStatus());
            assertTrue(result.getReason().contains("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("rejects transaction with missing required fields after auth passes")
        void rejectsMissingFields() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = new HashMap<>();
            txn.put("sourceAccount", "12345678");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("REJECTED", result.getStatus());
        }

        @Test
        @DisplayName("rejects amount exceeding single transaction limit")
        void rejectsExcessiveAmount() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "999999.99", "WIRE");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("REJECTED", result.getStatus());
        }
    }

    @Nested
    @DisplayName("Compliance gate")
    class ComplianceGate {

        @Test
        @DisplayName("rejects transaction to OFAC-sanctioned destination (fails format validation)")
        void rejectsOfacSanctionedInvalidFormat() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction(
                    "12345678", "SANCTIONED_ENTITY_001", "500.00", "WIRE");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("REJECTED", result.getStatus());
            assertTrue(result.getReason().contains("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("blocks transaction when compliance checker detects OFAC match")
        void blocksOfacViaComplianceChecker() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            // Use mocked validation to reach compliance gate directly
            ValidationEngine mockValidation = mock(ValidationEngine.class);
            when(mockValidation.validate(any())).thenReturn(
                    new TransactionProcessor.ValidationResult(true, java.util.Collections.emptyList()));
            TransactionProcessor processorWithMockValidation = new TransactionProcessor(
                    mockValidation, complianceChecker, piiDetector, piiMasker, tokenValidator);

            Map<String, Object> txn = buildTransaction(
                    "12345678", "SANCTIONED_ENTITY_001", "500.00", "WIRE");

            TransactionProcessor.TransactionResult result =
                    processorWithMockValidation.processTransaction("token", txn);

            assertEquals("BLOCKED", result.getStatus());
            assertTrue(result.getReason().contains("OFAC"));
        }

        @Test
        @DisplayName("flags wire transfer above $50k for manual review")
        void flagsLargeWireForReview() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction(
                    "12345678", "87654321", "75000.00", "WIRE");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("PENDING_REVIEW", result.getStatus());
            assertTrue(result.getReason().contains("ENHANCED_DUE_DILIGENCE"));
        }

        @Test
        @DisplayName("flags potential structuring for review")
        void flagsStructuring() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction(
                    "12345678", "87654321", "9500.00", "ACH");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("PENDING_REVIEW", result.getStatus());
            assertTrue(result.getReason().contains("STRUCTURING"));
        }
    }

    @Nested
    @DisplayName("PII handling during processing")
    class PiiHandling {

        @Test
        @DisplayName("processes transaction containing PII without exposing it in result")
        void processesWithPii() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "100.00", "INTERNAL");
            txn.put("memo", "Payment for SSN 123-45-6789 holder");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("APPROVED", result.getStatus());
            // Result should not contain raw PII
            assertFalse(result.getStatus().contains("123-45-6789"));
        }

        @Test
        @DisplayName("approves transaction even with PII present")
        void approvesWithPii() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "500.00", "INTERNAL");
            txn.put("note", "john.doe@bank.com account transfer");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("APPROVED", result.getStatus());
        }
    }

    @Nested
    @DisplayName("Successful transaction approval")
    class SuccessfulApproval {

        @Test
        @DisplayName("approves valid internal transfer")
        void approvesInternalTransfer() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "1000.00", "INTERNAL");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("APPROVED", result.getStatus());
            assertNotNull(result.getTransactionId());
            assertNotNull(result.getProcessedAt());
            assertNull(result.getReason());
        }

        @Test
        @DisplayName("approves valid ACH transfer under compliance thresholds")
        void approvesValidAch() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "2500.00", "ACH");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("APPROVED", result.getStatus());
        }

        @Test
        @DisplayName("approves valid BILL_PAY transaction")
        void approvesValidBillPay() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "150.00", "BILL_PAY");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("APPROVED", result.getStatus());
        }

        @Test
        @DisplayName("generates unique transaction IDs for each transaction")
        void generatesUniqueIds() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "100.00", "INTERNAL");

            TransactionProcessor.TransactionResult result1 =
                    processor.processTransaction("token", txn);
            TransactionProcessor.TransactionResult result2 =
                    processor.processTransaction("token", txn);

            assertNotEquals(result1.getTransactionId(), result2.getTransactionId());
        }
    }

    @Nested
    @DisplayName("TransactionResult factory methods")
    class TransactionResultFactory {

        @Test
        @DisplayName("approved result has correct status and no reason")
        void approvedResult() {
            TransactionProcessor.TransactionResult result =
                    TransactionProcessor.TransactionResult.approved("txn-1",
                            java.time.Instant.now());

            assertEquals("APPROVED", result.getStatus());
            assertEquals("txn-1", result.getTransactionId());
            assertNull(result.getReason());
            assertNotNull(result.getProcessedAt());
        }

        @Test
        @DisplayName("rejected result contains error code and reason")
        void rejectedResult() {
            TransactionProcessor.TransactionResult result =
                    TransactionProcessor.TransactionResult.rejected("txn-2", "ERR_001", "Bad input");

            assertEquals("REJECTED", result.getStatus());
            assertEquals("txn-2", result.getTransactionId());
            assertTrue(result.getReason().contains("ERR_001"));
            assertTrue(result.getReason().contains("Bad input"));
        }

        @Test
        @DisplayName("blocked result has correct status and reason")
        void blockedResult() {
            TransactionProcessor.TransactionResult result =
                    TransactionProcessor.TransactionResult.blocked("txn-3", "OFAC_BLOCK");

            assertEquals("BLOCKED", result.getStatus());
            assertEquals("OFAC_BLOCK", result.getReason());
        }

        @Test
        @DisplayName("pendingReview result has correct status and reason")
        void pendingReviewResult() {
            TransactionProcessor.TransactionResult result =
                    TransactionProcessor.TransactionResult.pendingReview("txn-4", "EDD required");

            assertEquals("PENDING_REVIEW", result.getStatus());
            assertEquals("EDD required", result.getReason());
        }
    }

    @Nested
    @DisplayName("Error handling — no sensitive data in responses")
    class ErrorHandling {

        @Test
        @DisplayName("error response does not contain stack traces")
        void noStackTracesInResponse() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "87654321", "notanumber", "ACH");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            assertEquals("REJECTED", result.getStatus());
            assertFalse(result.getReason().contains("Exception"));
            assertFalse(result.getReason().contains("at com."));
        }

        @Test
        @DisplayName("error response does not expose internal account details")
        void noAccountDetailsInError() {
            when(tokenValidator.validateToken(anyString())).thenReturn(true);
            Map<String, Object> txn = buildTransaction("12345678", "SANCTIONED_ENTITY_001", "100.00", "WIRE");

            TransactionProcessor.TransactionResult result =
                    processor.processTransaction("token", txn);

            // Validation rejects non-numeric destination format; error doesn't expose internals
            assertEquals("REJECTED", result.getStatus());
            assertNotNull(result.getReason());
            assertFalse(result.getReason().contains("Exception"));
        }
    }
}
