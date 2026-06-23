package com.bofa.transactions.pii;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects Personally Identifiable Information (PII) in transaction data.
 * Required for GLBA compliance — PII must never appear in logs or
 * non-production environments without masking.
 * 
 * Detects: SSN, credit card numbers, phone numbers, email addresses,
 * date of birth patterns, and account numbers in free-text fields.
 */
@Component
public class PiiDetector {

    private static final List<Pattern> PII_PATTERNS = List.of(
        // SSN: XXX-XX-XXXX or XXXXXXXXX
        Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        Pattern.compile("\\b\\d{9}\\b"),
        // Credit card: 13-19 digits with optional separators
        Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{1,7}\\b"),
        // Phone: various US formats
        Pattern.compile("\\b\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b"),
        // Email
        Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"),
        // Date of birth: MM/DD/YYYY or YYYY-MM-DD
        Pattern.compile("\\b\\d{2}/\\d{2}/\\d{4}\\b"),
        Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b")
    );

    /**
     * Check if the given text contains any PII patterns.
     */
    public boolean containsPii(String text) {
        if (text == null || text.isEmpty()) return false;
        return PII_PATTERNS.stream().anyMatch(p -> p.matcher(text).find());
    }

    /**
     * Identify which types of PII are present.
     */
    public List<String> identifyPiiTypes(String text) {
        if (text == null || text.isEmpty()) return List.of();

        List<String> types = new java.util.ArrayList<>();
        if (Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b").matcher(text).find()) {
            types.add("SSN");
        }
        if (Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{1,7}\\b").matcher(text).find()) {
            types.add("CREDIT_CARD");
        }
        if (Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b").matcher(text).find()) {
            types.add("EMAIL");
        }
        if (Pattern.compile("\\b\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b").matcher(text).find()) {
            types.add("PHONE");
        }
        return types;
    }
}
