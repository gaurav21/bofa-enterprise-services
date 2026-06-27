package com.bofa.transactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationEngine — Input validation for transaction requests")
class ValidationEngineTest {

    private ValidationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ValidationEngine();
    }

    private Map<String, Object> validTransaction() {
        Map<String, Object> txn = new HashMap<>();
        txn.put("sourceAccount", "12345678");
        txn.put("destinationAccount", "87654321");
        txn.put("amount", "1000.00");
        txn.put("type", "ACH");
        return txn;
    }

    @Nested
    @DisplayName("Required field validation")
    class RequiredFields {

        @Test
        @DisplayName("rejects when sourceAccount is missing")
        void rejectsMissingSourceAccount() {
            Map<String, Object> txn = validTransaction();
            txn.remove("sourceAccount");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("sourceAccount")));
        }

        @Test
        @DisplayName("rejects when destinationAccount is missing")
        void rejectsMissingDestinationAccount() {
            Map<String, Object> txn = validTransaction();
            txn.remove("destinationAccount");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("destinationAccount")));
        }

        @Test
        @DisplayName("rejects when amount is missing")
        void rejectsMissingAmount() {
            Map<String, Object> txn = validTransaction();
            txn.remove("amount");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("amount")));
        }

        @Test
        @DisplayName("rejects when type is missing")
        void rejectsMissingType() {
            Map<String, Object> txn = validTransaction();
            txn.remove("type");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("type")));
        }

        @Test
        @DisplayName("rejects null field values")
        void rejectsNullFieldValues() {
            Map<String, Object> txn = validTransaction();
            txn.put("sourceAccount", null);

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("rejects blank field values")
        void rejectsBlankFieldValues() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "   ");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("reports all missing fields at once")
        void reportsAllMissingFields() {
            Map<String, Object> txn = new HashMap<>();

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertEquals(4, result.getErrors().size());
        }
    }

    @Nested
    @DisplayName("Account format validation")
    class AccountFormat {

        @Test
        @DisplayName("accepts 8-digit account number")
        void accepts8Digits() {
            Map<String, Object> txn = validTransaction();
            txn.put("sourceAccount", "12345678");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("accepts 17-digit account number")
        void accepts17Digits() {
            Map<String, Object> txn = validTransaction();
            txn.put("sourceAccount", "12345678901234567");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("rejects account with fewer than 8 digits")
        void rejectsTooShort() {
            Map<String, Object> txn = validTransaction();
            txn.put("sourceAccount", "1234567");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("source account format")));
        }

        @Test
        @DisplayName("rejects account with more than 17 digits")
        void rejectsTooLong() {
            Map<String, Object> txn = validTransaction();
            txn.put("sourceAccount", "123456789012345678");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("rejects account with alphabetic characters")
        void rejectsAlphabetic() {
            Map<String, Object> txn = validTransaction();
            txn.put("sourceAccount", "1234ABCD");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("rejects when source and destination accounts are the same")
        void rejectsSameAccounts() {
            Map<String, Object> txn = validTransaction();
            txn.put("sourceAccount", "12345678");
            txn.put("destinationAccount", "12345678");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("cannot be the same")));
        }
    }

    @Nested
    @DisplayName("Amount validation")
    class AmountValidation {

        @Test
        @DisplayName("accepts minimum valid amount of $0.01")
        void acceptsMinimumAmount() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "0.01");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("accepts maximum valid amount of $500,000")
        void acceptsMaximumAmount() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "500000.00");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("rejects amount below minimum")
        void rejectsBelowMinimum() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "0.001");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("rejects amount above maximum")
        void rejectsAboveMaximum() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "500000.01");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("exceeds")));
        }

        @Test
        @DisplayName("rejects amount with more than 2 decimal places")
        void rejectsExcessDecimals() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "100.123");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("decimal")));
        }

        @Test
        @DisplayName("rejects non-numeric amount")
        void rejectsNonNumeric() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "abc");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid amount")));
        }

        @Test
        @DisplayName("rejects zero amount")
        void rejectsZero() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "0.00");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }

        @Test
        @DisplayName("rejects negative amount")
        void rejectsNegative() {
            Map<String, Object> txn = validTransaction();
            txn.put("amount", "-100.00");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }
    }

    @Nested
    @DisplayName("Transaction type validation")
    class TransactionType {

        @ParameterizedTest
        @ValueSource(strings = {"ACH", "WIRE", "INTERNAL", "BILL_PAY"})
        @DisplayName("accepts valid transaction types")
        void acceptsValidTypes(String type) {
            Map<String, Object> txn = validTransaction();
            txn.put("type", type);

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("rejects invalid transaction type")
        void rejectsInvalidType() {
            Map<String, Object> txn = validTransaction();
            txn.put("type", "CRYPTO");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("Invalid transaction type")));
        }

        @Test
        @DisplayName("rejects lowercase transaction type")
        void rejectsLowercase() {
            Map<String, Object> txn = validTransaction();
            txn.put("type", "ach");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
        }
    }

    @Nested
    @DisplayName("Routing number validation")
    class RoutingNumber {

        @Test
        @DisplayName("accepts valid routing number with correct checksum")
        void acceptsValidRouting() {
            Map<String, Object> txn = validTransaction();
            txn.put("type", "ACH");
            txn.put("routingNumber", "021000021");  // JPMorgan Chase

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("rejects routing number with wrong length")
        void rejectsWrongLength() {
            Map<String, Object> txn = validTransaction();
            txn.put("type", "ACH");
            txn.put("routingNumber", "12345678");  // 8 digits

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("routing number format")));
        }

        @Test
        @DisplayName("rejects routing number with invalid checksum")
        void rejectsInvalidChecksum() {
            Map<String, Object> txn = validTransaction();
            txn.put("type", "WIRE");
            txn.put("routingNumber", "123456789");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertFalse(result.isValid());
            assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("checksum")));
        }

        @Test
        @DisplayName("routing number not required for INTERNAL transfers")
        void notRequiredForInternal() {
            Map<String, Object> txn = validTransaction();
            txn.put("type", "INTERNAL");
            // No routing number

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("Valid transaction acceptance")
    class ValidTransactions {

        @Test
        @DisplayName("accepts fully valid ACH transaction")
        void acceptsValidAch() {
            Map<String, Object> txn = validTransaction();
            txn.put("routingNumber", "021000021");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
            assertTrue(result.getErrors().isEmpty());
        }

        @Test
        @DisplayName("accepts fully valid internal transfer")
        void acceptsValidInternal() {
            Map<String, Object> txn = validTransaction();
            txn.put("type", "INTERNAL");
            txn.put("amount", "5000.00");

            TransactionProcessor.ValidationResult result = engine.validate(txn);

            assertTrue(result.isValid());
        }
    }
}
