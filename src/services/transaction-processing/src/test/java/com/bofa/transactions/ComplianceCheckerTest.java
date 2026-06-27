package com.bofa.transactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ComplianceChecker — BSA/AML, OFAC, KYC regulatory compliance")
class ComplianceCheckerTest {

    private ComplianceChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ComplianceChecker();
    }

    @Nested
    @DisplayName("OFAC sanctions screening")
    class OfacScreening {

        @Test
        @DisplayName("blocks transaction to sanctioned entity")
        void blocksOfacSanctionedEntity() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "SANCTIONED_ENTITY_001",
                    new BigDecimal("500.00"), "WIRE");

            assertTrue(result.isBlocked());
            assertTrue(result.getReason().contains("OFAC_BLOCKED"));
        }

        @Test
        @DisplayName("blocks second sanctioned entity")
        void blocksSecondSanctionedEntity() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "SANCTIONED_ENTITY_002",
                    new BigDecimal("100.00"), "ACH");

            assertTrue(result.isBlocked());
            assertTrue(result.getReason().contains("OFAC_BLOCKED"));
        }

        @Test
        @DisplayName("allows transaction to non-sanctioned entity")
        void allowsNonSanctionedEntity() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("500.00"), "INTERNAL");

            assertFalse(result.isBlocked());
        }

        @Test
        @DisplayName("OFAC check is case-sensitive")
        void ofacCheckCaseSensitive() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "sanctioned_entity_001",
                    new BigDecimal("500.00"), "WIRE");

            assertFalse(result.isBlocked());
        }
    }

    @Nested
    @DisplayName("Currency Transaction Report (CTR) requirements")
    class CtrRequirements {

        @Test
        @DisplayName("does not block transactions above $10,000 CTR threshold")
        void doesNotBlockAboveThreshold() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("15000.00"), "ACH");

            assertFalse(result.isBlocked());
        }

        @Test
        @DisplayName("allows transaction exactly at $10,000 CTR threshold")
        void allowsExactThreshold() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("10000.00"), "ACH");

            assertFalse(result.isBlocked());
        }

        @Test
        @DisplayName("allows transaction below CTR threshold without flag")
        void allowsBelowThresholdNoFlag() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("5000.00"), "INTERNAL");

            assertFalse(result.isBlocked());
            assertFalse(result.requiresReview());
            assertEquals("COMPLIANT", result.getReason());
        }
    }

    @Nested
    @DisplayName("Structuring detection (anti-smurfing)")
    class StructuringDetection {

        @Test
        @DisplayName("flags potential structuring for amount between $9,000 and $10,000")
        void flagsStructuringJustBelowThreshold() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("9500.00"), "ACH");

            assertFalse(result.isBlocked());
            assertTrue(result.requiresReview());
            assertTrue(result.getReason().contains("STRUCTURING_SUSPECTED"));
        }

        @Test
        @DisplayName("flags amount just above $9,000")
        void flagsJustAbove9000() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("9001.00"), "ACH");

            assertTrue(result.requiresReview());
            assertTrue(result.getReason().contains("STRUCTURING"));
        }

        @Test
        @DisplayName("does not flag amount at exactly $9,000")
        void doesNotFlagExactly9000() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("9000.00"), "INTERNAL");

            assertFalse(result.requiresReview());
        }

        @Test
        @DisplayName("does not flag small amounts")
        void doesNotFlagSmallAmounts() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("500.00"), "INTERNAL");

            assertFalse(result.requiresReview());
            assertEquals("COMPLIANT", result.getReason());
        }
    }

    @Nested
    @DisplayName("Wire transfer enhanced due diligence")
    class WireTransferEdd {

        @Test
        @DisplayName("requires review for wire transfers above $50,000")
        void requiresReviewAbove50k() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("75000.00"), "WIRE");

            assertFalse(result.isBlocked());
            assertTrue(result.requiresReview());
            assertTrue(result.getReason().contains("ENHANCED_DUE_DILIGENCE"));
        }

        @Test
        @DisplayName("does not require review for wire at exactly $50,000")
        void doesNotRequireAtExactThreshold() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("50000.00"), "WIRE");

            assertFalse(result.requiresReview());
        }

        @Test
        @DisplayName("does not require review for non-wire above $50,000")
        void doesNotRequireForNonWire() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("75000.00"), "ACH");

            // ACH above 50k shouldn't trigger wire-specific EDD
            // (may trigger CTR instead if above 10k)
            assertFalse(result.getReason().contains("ENHANCED_DUE_DILIGENCE"));
        }

        @Test
        @DisplayName("does not require review for wire under $50,000")
        void doesNotRequireUnder50k() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("25000.00"), "WIRE");

            assertFalse(result.requiresReview());
        }
    }

    @Nested
    @DisplayName("Compliant transaction flow")
    class CompliantFlow {

        @Test
        @DisplayName("returns COMPLIANT for simple internal transfer")
        void compliantSimpleTransfer() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("250.00"), "INTERNAL");

            assertFalse(result.isBlocked());
            assertFalse(result.requiresReview());
            assertEquals("COMPLIANT", result.getReason());
        }

        @Test
        @DisplayName("returns COMPLIANT for small wire transfer")
        void compliantSmallWire() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("5000.00"), "WIRE");

            assertFalse(result.isBlocked());
            assertFalse(result.requiresReview());
            assertEquals("COMPLIANT", result.getReason());
        }

        @Test
        @DisplayName("returns COMPLIANT for bill pay")
        void compliantBillPay() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "87654321",
                    new BigDecimal("150.00"), "BILL_PAY");

            assertFalse(result.isBlocked());
            assertFalse(result.requiresReview());
            assertEquals("COMPLIANT", result.getReason());
        }
    }

    @Nested
    @DisplayName("Priority ordering of compliance checks")
    class CheckPriority {

        @Test
        @DisplayName("OFAC block takes priority over structuring detection")
        void ofacTakesPriorityOverStructuring() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "SANCTIONED_ENTITY_001",
                    new BigDecimal("9500.00"), "ACH");

            assertTrue(result.isBlocked());
            assertTrue(result.getReason().contains("OFAC"));
        }

        @Test
        @DisplayName("OFAC block takes priority over wire EDD")
        void ofacTakesPriorityOverWireEdd() {
            TransactionProcessor.ComplianceResult result = checker.checkCompliance(
                    "12345678", "SANCTIONED_ENTITY_002",
                    new BigDecimal("100000.00"), "WIRE");

            assertTrue(result.isBlocked());
            assertTrue(result.getReason().contains("OFAC"));
        }
    }
}
