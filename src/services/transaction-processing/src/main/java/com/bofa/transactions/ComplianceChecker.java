package com.bofa.transactions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Regulatory compliance engine for BSA/AML, OFAC, and KYC checks.
 * 
 * BSA: Bank Secrecy Act — requires CTR filing for transactions > $10,000
 * AML: Anti-Money Laundering — pattern detection for structuring
 * OFAC: Office of Foreign Assets Control — sanctions screening
 * KYC: Know Your Customer — identity verification requirements
 * 
 * CRITICAL: This code path has only 15.2% test coverage.
 * OCC examination will specifically audit this module.
 */
@Component
public class ComplianceChecker {

    private static final Logger log = LoggerFactory.getLogger(ComplianceChecker.class);
    private static final BigDecimal CTR_THRESHOLD = new BigDecimal("10000.00");
    private static final BigDecimal WIRE_REVIEW_THRESHOLD = new BigDecimal("50000.00");
    private static final BigDecimal STRUCTURING_WINDOW_TOTAL = new BigDecimal("10000.00");

    // Simplified OFAC SDN list check (real implementation calls OFAC API)
    private static final Set<String> BLOCKED_ENTITIES = Set.of(
        "SANCTIONED_ENTITY_001", "SANCTIONED_ENTITY_002"
    );

    // High-risk country codes (FATF grey/black list)
    private static final Set<String> HIGH_RISK_COUNTRIES = Set.of(
        "IR", "KP", "SY", "MM", "AF", "YE", "SO"
    );

    public TransactionProcessor.ComplianceResult checkCompliance(
            String sourceAccount, String destinationAccount,
            BigDecimal amount, String transactionType) {

        // OFAC sanctions screening
        if (isOfacBlocked(destinationAccount)) {
            log.error("OFAC BLOCK: destination {} is on sanctions list", destinationAccount);
            return new TransactionProcessor.ComplianceResult(true, false,
                    "OFAC_BLOCKED: Destination account matches sanctioned entity");
        }

        // Currency Transaction Report requirement
        if (amount.compareTo(CTR_THRESHOLD) > 0) {
            log.info("CTR required: amount {} exceeds threshold for account {}",
                    amount, sourceAccount);
            fileCurrencyTransactionReport(sourceAccount, destinationAccount, amount);
        }

        // Structuring detection (splitting transactions to avoid CTR)
        if (detectStructuring(sourceAccount, amount)) {
            log.warn("Potential structuring detected: account={}, amount={}",
                    sourceAccount, amount);
            return new TransactionProcessor.ComplianceResult(false, true,
                    "STRUCTURING_SUSPECTED: Multiple transactions below CTR threshold detected");
        }

        // Wire transfer enhanced due diligence
        if ("WIRE".equals(transactionType) && amount.compareTo(WIRE_REVIEW_THRESHOLD) > 0) {
            log.info("Wire transfer requires enhanced review: amount={}", amount);
            return new TransactionProcessor.ComplianceResult(false, true,
                    "ENHANCED_DUE_DILIGENCE: Wire transfer above review threshold");
        }

        // High-risk jurisdiction check
        String destCountry = resolveCountryCode(destinationAccount);
        if (destCountry != null && HIGH_RISK_COUNTRIES.contains(destCountry)) {
            log.warn("High-risk jurisdiction detected: country={}", destCountry);
            return new TransactionProcessor.ComplianceResult(false, true,
                    "HIGH_RISK_JURISDICTION: Destination in FATF-listed country");
        }

        return new TransactionProcessor.ComplianceResult(false, false, "COMPLIANT");
    }

    private boolean isOfacBlocked(String account) {
        // TODO: Replace with real OFAC API call
        return BLOCKED_ENTITIES.contains(account);
    }

    private boolean detectStructuring(String accountId, BigDecimal amount) {
        // TODO: Query last 24h transactions for this account
        // Check if multiple transactions just under $10,000 threshold
        // This is a simplified check — real implementation uses ML model
        return amount.compareTo(new BigDecimal("9000")) > 0
                && amount.compareTo(CTR_THRESHOLD) < 0;
    }

    private void fileCurrencyTransactionReport(String source, String dest, BigDecimal amount) {
        // TODO: Submit CTR to FinCEN via BSA E-Filing
        log.info("Filing CTR: source={}, dest={}, amount={}", source, dest, amount);
    }

    private String resolveCountryCode(String account) {
        // TODO: Look up account country from KYC profile
        return null;
    }
}
