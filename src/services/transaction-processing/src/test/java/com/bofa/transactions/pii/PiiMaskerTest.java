package com.bofa.transactions.pii;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PiiMasker — GLBA-compliant PII masking for logs and reports")
class PiiMaskerTest {

    private PiiMasker masker;

    @BeforeEach
    void setUp() {
        masker = new PiiMasker();
    }

    @Nested
    @DisplayName("maskForLogging — partial masking for internal logs")
    class MaskForLogging {

        @Test
        @DisplayName("masks SSN keeping last 4 digits")
        void masksSsn() {
            String result = masker.maskForLogging("SSN: 123-45-6789");

            assertTrue(result.contains("***-**-6789"));
            assertFalse(result.contains("123-45"));
        }

        @Test
        @DisplayName("masks SSN without dashes")
        void masksSsnNoDashes() {
            String result = masker.maskForLogging("SSN: 123456789");

            assertTrue(result.contains("***-**-6789"));
        }

        @Test
        @DisplayName("masks credit card keeping last 4 digits")
        void masksCreditCard() {
            String result = masker.maskForLogging("Card: 4111-1111-1111-1111");

            assertTrue(result.contains("****-****-****-1111"));
            assertFalse(result.contains("4111-1111-1111-1111"));
        }

        @Test
        @DisplayName("masks email keeping domain")
        void masksEmail() {
            String result = masker.maskForLogging("Contact: john.doe@example.com");

            assertTrue(result.contains("****@example.com"));
            assertFalse(result.contains("john.doe"));
        }

        @Test
        @DisplayName("masks phone number keeping last 4 digits")
        void masksPhone() {
            String result = masker.maskForLogging("Phone: (555) 123-4567");

            assertTrue(result.contains("4567"));
            assertFalse(result.contains("555") && result.contains("123"));
        }

        @Test
        @DisplayName("masks multiple PII in one string")
        void masksMultiplePii() {
            String input = "Customer SSN 123-45-6789, card 4111111111111111, email test@bank.com";
            String result = masker.maskForLogging(input);

            assertFalse(result.contains("123-45-6789"));
            assertFalse(result.contains("4111111111111111"));
            assertFalse(result.contains("test@bank.com"));
        }

        @Test
        @DisplayName("preserves non-PII text unchanged")
        void preservesNonPii() {
            String input = "Transaction approved for amount $1500.00";
            String result = masker.maskForLogging(input);

            assertEquals(input, result);
        }

        @Test
        @DisplayName("returns null for null input")
        void handlesNull() {
            assertNull(masker.maskForLogging(null));
        }
    }

    @Nested
    @DisplayName("redactFully — complete redaction for external reports")
    class RedactFully {

        @Test
        @DisplayName("fully redacts SSN")
        void redactsSsn() {
            String result = masker.redactFully("SSN: 123-45-6789");

            assertTrue(result.contains("[REDACTED-SSN]"));
            assertFalse(result.contains("6789"));
        }

        @Test
        @DisplayName("fully redacts credit card")
        void redactsCard() {
            String result = masker.redactFully("Card: 4111-1111-1111-1111");

            assertTrue(result.contains("[REDACTED-CARD]"));
            assertFalse(result.contains("1111"));
        }

        @Test
        @DisplayName("fully redacts email")
        void redactsEmail() {
            String result = masker.redactFully("Email: user@domain.com");

            assertTrue(result.contains("[REDACTED-EMAIL]"));
            assertFalse(result.contains("user"));
            assertFalse(result.contains("domain.com"));
        }

        @Test
        @DisplayName("fully redacts phone number")
        void redactsPhone() {
            String result = masker.redactFully("Phone: (555) 123-4567");

            assertTrue(result.contains("[REDACTED-PHONE]"));
            assertFalse(result.contains("4567"));
        }

        @Test
        @DisplayName("redacts all PII types in combined text")
        void redactsAllPiiTypes() {
            String input = "SSN: 123-45-6789, Card: 4111111111111111, Email: x@y.com";
            String result = masker.redactFully(input);

            assertTrue(result.contains("[REDACTED-SSN]"));
            assertTrue(result.contains("[REDACTED-CARD]"));
            assertTrue(result.contains("[REDACTED-EMAIL]"));
        }

        @Test
        @DisplayName("returns null for null input")
        void handlesNull() {
            assertNull(masker.redactFully(null));
        }

        @Test
        @DisplayName("does not expose partial PII in redacted output")
        void noPartialLeakage() {
            String result = masker.redactFully("Customer SSN is 999-88-7777");

            assertFalse(result.contains("999"));
            assertFalse(result.contains("88"));
            assertFalse(result.contains("7777"));
        }
    }

    @Nested
    @DisplayName("maskAccountNumber — account number masking")
    class MaskAccountNumber {

        @Test
        @DisplayName("masks account number keeping first 4 digits")
        void masksStandardAccount() {
            String result = masker.maskAccountNumber("1234567890");

            assertEquals("1234******", result);
        }

        @Test
        @DisplayName("masks 17-digit account number")
        void masksLongAccount() {
            String result = masker.maskAccountNumber("12345678901234567");

            assertTrue(result.startsWith("1234"));
            assertEquals(17, result.length());
            assertTrue(result.substring(4).matches("\\*+"));
        }

        @Test
        @DisplayName("masks 8-digit account number")
        void masksShortAccount() {
            String result = masker.maskAccountNumber("12345678");

            assertEquals("1234****", result);
        }

        @Test
        @DisplayName("returns short account unchanged (less than 8 chars)")
        void returnsShortAccountUnchanged() {
            String result = masker.maskAccountNumber("1234567");

            assertEquals("1234567", result);
        }

        @Test
        @DisplayName("returns null for null input")
        void handlesNull() {
            assertNull(masker.maskAccountNumber(null));
        }

        @Test
        @DisplayName("returns empty string unchanged")
        void handlesEmpty() {
            assertEquals("", masker.maskAccountNumber(""));
        }
    }
}
