package com.bofa.transactions.pii;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Masks PII data for safe logging and non-production use.
 * GLBA and internal policy require all PII to be masked in:
 * - Application logs
 * - Non-production databases
 * - Error reports and stack traces
 * - Monitoring/observability payloads
 */
@Component
public class PiiMasker {

    private static final Pattern SSN_PATTERN = 
            Pattern.compile("\\b(\\d{3})-?(\\d{2})-?(\\d{4})\\b");
    private static final Pattern CARD_PATTERN = 
            Pattern.compile("\\b(\\d{4})[- ]?\\d{4}[- ]?\\d{4}[- ]?(\\d{1,7})\\b");
    private static final Pattern EMAIL_PATTERN = 
            Pattern.compile("\\b([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Z|a-z]{2,})\\b");
    private static final Pattern PHONE_PATTERN = 
            Pattern.compile("\\b\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?(\\d{4})\\b");
    private static final Pattern ACCOUNT_PATTERN = 
            Pattern.compile("\\b(\\d{4})\\d{4,13}\\b");

    /**
     * Mask all detected PII in the given text for safe logging.
     * SSN: ***-**-1234, Card: ****-****-****-5678, etc.
     */
    public String maskForLogging(String text) {
        if (text == null) return null;

        String masked = text;
        masked = SSN_PATTERN.matcher(masked).replaceAll("***-**-$3");
        masked = CARD_PATTERN.matcher(masked).replaceAll("****-****-****-$2");
        masked = EMAIL_PATTERN.matcher(masked).replaceAll("****@$2");
        masked = PHONE_PATTERN.matcher(masked).replaceAll("(***) ***-$1");

        return masked;
    }

    /**
     * Fully redact PII — replaces with [REDACTED] tokens.
     * Used for compliance reports sent to external parties.
     */
    public String redactFully(String text) {
        if (text == null) return null;

        String redacted = text;
        redacted = SSN_PATTERN.matcher(redacted).replaceAll("[REDACTED-SSN]");
        redacted = CARD_PATTERN.matcher(redacted).replaceAll("[REDACTED-CARD]");
        redacted = EMAIL_PATTERN.matcher(redacted).replaceAll("[REDACTED-EMAIL]");
        redacted = PHONE_PATTERN.matcher(redacted).replaceAll("[REDACTED-PHONE]");

        return redacted;
    }

    /**
     * Mask account numbers, keeping first 4 digits visible.
     */
    public String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 8) return accountNumber;
        return accountNumber.substring(0, 4) + "*".repeat(accountNumber.length() - 4);
    }
}
