package com.bofa.transactions;

import com.bofa.transactions.auth.TokenValidator;
import com.bofa.transactions.pii.PiiDetector;
import com.bofa.transactions.pii.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Core transaction processing engine.
 * Orchestrates validation, compliance checks, PII handling, and persistence.
 * Handles ACH, wire transfers, internal transfers, and bill pay.
 */
@Service
public class TransactionProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransactionProcessor.class);
    private static final BigDecimal DAILY_WIRE_LIMIT = new BigDecimal("250000.00");
    private static final BigDecimal CTR_THRESHOLD = new BigDecimal("10000.00");

    private final ValidationEngine validationEngine;
    private final ComplianceChecker complianceChecker;
    private final PiiDetector piiDetector;
    private final PiiMasker piiMasker;
    private final TokenValidator tokenValidator;

    public TransactionProcessor(ValidationEngine validationEngine,
                                 ComplianceChecker complianceChecker,
                                 PiiDetector piiDetector,
                                 PiiMasker piiMasker,
                                 TokenValidator tokenValidator) {
        this.validationEngine = validationEngine;
        this.complianceChecker = complianceChecker;
        this.piiDetector = piiDetector;
        this.piiMasker = piiMasker;
        this.tokenValidator = tokenValidator;
    }

    public TransactionResult processTransaction(String authToken,
                                                  Map<String, Object> transactionData) {
        String txnId = UUID.randomUUID().toString();
        log.info("Processing transaction: id={}, type={}", txnId, transactionData.get("type"));

        // Step 1: Authenticate
        if (!tokenValidator.validateToken(authToken)) {
            return TransactionResult.rejected(txnId, "INVALID_TOKEN", "Authentication failed");
        }

        // Step 2: Validate input
        ValidationResult validation = validationEngine.validate(transactionData);
        if (!validation.isValid()) {
            return TransactionResult.rejected(txnId, "VALIDATION_FAILED", validation.getErrors().toString());
        }

        // Step 3: PII detection and masking for logging
        if (piiDetector.containsPii(transactionData.toString())) {
            String masked = piiMasker.maskForLogging(transactionData.toString());
            log.info("Transaction contains PII, masked for logging: {}", masked);
        }

        // Step 4: Compliance checks
        BigDecimal amount = new BigDecimal(transactionData.get("amount").toString());
        ComplianceResult compliance = complianceChecker.checkCompliance(
                transactionData.get("sourceAccount").toString(),
                transactionData.get("destinationAccount").toString(),
                amount,
                transactionData.get("type").toString()
        );

        if (compliance.isBlocked()) {
            log.warn("Transaction blocked by compliance: txn={}, reason={}",
                    txnId, compliance.getReason());
            return TransactionResult.blocked(txnId, compliance.getReason());
        }

        if (compliance.requiresReview()) {
            log.info("Transaction flagged for manual review: txn={}", txnId);
            return TransactionResult.pendingReview(txnId, compliance.getReason());
        }

        // Step 5: Execute (stubbed - would call core banking engine)
        log.info("Transaction approved: id={}, amount={}", txnId, amount);
        return TransactionResult.approved(txnId, Instant.now());
    }

    // Inner classes for results
    public static class TransactionResult {
        private final String transactionId;
        private final String status;
        private final String reason;
        private final Instant processedAt;

        private TransactionResult(String txnId, String status, String reason, Instant processedAt) {
            this.transactionId = txnId;
            this.status = status;
            this.reason = reason;
            this.processedAt = processedAt;
        }

        public static TransactionResult approved(String txnId, Instant at) {
            return new TransactionResult(txnId, "APPROVED", null, at);
        }
        public static TransactionResult rejected(String txnId, String code, String reason) {
            return new TransactionResult(txnId, "REJECTED", code + ": " + reason, Instant.now());
        }
        public static TransactionResult blocked(String txnId, String reason) {
            return new TransactionResult(txnId, "BLOCKED", reason, Instant.now());
        }
        public static TransactionResult pendingReview(String txnId, String reason) {
            return new TransactionResult(txnId, "PENDING_REVIEW", reason, Instant.now());
        }

        public String getTransactionId() { return transactionId; }
        public String getStatus() { return status; }
        public String getReason() { return reason; }
        public Instant getProcessedAt() { return processedAt; }
    }

    public static class ValidationResult {
        private final boolean valid;
        private final java.util.List<String> errors;

        public ValidationResult(boolean valid, java.util.List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }
        public boolean isValid() { return valid; }
        public java.util.List<String> getErrors() { return errors; }
    }

    public static class ComplianceResult {
        private final boolean blocked;
        private final boolean requiresReview;
        private final String reason;

        public ComplianceResult(boolean blocked, boolean requiresReview, String reason) {
            this.blocked = blocked;
            this.requiresReview = requiresReview;
            this.reason = reason;
        }
        public boolean isBlocked() { return blocked; }
        public boolean requiresReview() { return requiresReview; }
        public String getReason() { return reason; }
    }
}
