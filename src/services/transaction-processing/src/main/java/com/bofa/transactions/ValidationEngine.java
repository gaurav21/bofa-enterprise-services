package com.bofa.transactions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Multi-layer input validation for transaction requests.
 * Validates account formats, amounts, routing numbers, and business rules.
 * 
 * Known gaps: Unicode normalization attacks, negative amount edge cases,
 * and international routing number formats are not fully covered.
 */
@Component
public class ValidationEngine {

    private static final Logger log = LoggerFactory.getLogger(ValidationEngine.class);
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^\\d{8,17}$");
    private static final Pattern ROUTING_PATTERN = Pattern.compile("^\\d{9}$");
    private static final BigDecimal MAX_SINGLE_TRANSACTION = new BigDecimal("500000.00");
    private static final BigDecimal MIN_TRANSACTION = new BigDecimal("0.01");

    public TransactionProcessor.ValidationResult validate(Map<String, Object> data) {
        List<String> errors = new ArrayList<>();

        // Required fields
        validateRequired(data, "sourceAccount", errors);
        validateRequired(data, "destinationAccount", errors);
        validateRequired(data, "amount", errors);
        validateRequired(data, "type", errors);

        if (!errors.isEmpty()) {
            return new TransactionProcessor.ValidationResult(false, errors);
        }

        // Account format validation
        String sourceAccount = data.get("sourceAccount").toString();
        String destAccount = data.get("destinationAccount").toString();

        if (!ACCOUNT_PATTERN.matcher(sourceAccount).matches()) {
            errors.add("Invalid source account format: must be 8-17 digits");
        }
        if (!ACCOUNT_PATTERN.matcher(destAccount).matches()) {
            errors.add("Invalid destination account format: must be 8-17 digits");
        }
        if (sourceAccount.equals(destAccount)) {
            errors.add("Source and destination accounts cannot be the same");
        }

        // Amount validation
        try {
            BigDecimal amount = new BigDecimal(data.get("amount").toString());
            if (amount.compareTo(MIN_TRANSACTION) < 0) {
                errors.add("Amount must be at least $0.01");
            }
            if (amount.compareTo(MAX_SINGLE_TRANSACTION) > 0) {
                errors.add("Amount exceeds single transaction limit of $500,000");
            }
            if (amount.scale() > 2) {
                errors.add("Amount cannot have more than 2 decimal places");
            }
        } catch (NumberFormatException e) {
            errors.add("Invalid amount format");
        }

        // Transaction type validation
        String type = data.get("type").toString();
        List<String> validTypes = List.of("ACH", "WIRE", "INTERNAL", "BILL_PAY");
        if (!validTypes.contains(type)) {
            errors.add("Invalid transaction type: " + type);
        }

        // Routing number (required for ACH and WIRE)
        if (("ACH".equals(type) || "WIRE".equals(type)) && data.containsKey("routingNumber")) {
            String routing = data.get("routingNumber").toString();
            if (!ROUTING_PATTERN.matcher(routing).matches()) {
                errors.add("Invalid routing number format: must be 9 digits");
            } else if (!validateRoutingChecksum(routing)) {
                errors.add("Invalid routing number checksum");
            }
        }

        return new TransactionProcessor.ValidationResult(errors.isEmpty(), errors);
    }

    private void validateRequired(Map<String, Object> data, String field, List<String> errors) {
        if (!data.containsKey(field) || data.get(field) == null
                || data.get(field).toString().isBlank()) {
            errors.add("Missing required field: " + field);
        }
    }

    private boolean validateRoutingChecksum(String routing) {
        // ABA routing number checksum: 3(d1+d4+d7) + 7(d2+d5+d8) + (d3+d6+d9) mod 10 == 0
        int[] digits = routing.chars().map(c -> c - '0').toArray();
        int checksum = 3 * (digits[0] + digits[3] + digits[6])
                     + 7 * (digits[1] + digits[4] + digits[7])
                     + (digits[2] + digits[5] + digits[8]);
        return checksum % 10 == 0;
    }
}
