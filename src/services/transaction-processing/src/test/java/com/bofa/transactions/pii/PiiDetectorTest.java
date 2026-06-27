package com.bofa.transactions.pii;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PiiDetector — GLBA-compliant PII detection in transaction data")
class PiiDetectorTest {

    private PiiDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PiiDetector();
    }

    @Nested
    @DisplayName("SSN detection")
    class SsnDetection {

        @Test
        @DisplayName("detects SSN with dashes (XXX-XX-XXXX)")
        void detectsSsnWithDashes() {
            assertTrue(detector.containsPii("User SSN is 123-45-6789"));
        }

        @Test
        @DisplayName("detects SSN in mixed text")
        void detectsSsnInText() {
            assertTrue(detector.containsPii("Account holder: John Doe, SSN: 987-65-4321, State: CA"));
        }

        @Test
        @DisplayName("identifies SSN type correctly")
        void identifiesSsnType() {
            List<String> types = detector.identifyPiiTypes("SSN: 123-45-6789");
            assertTrue(types.contains("SSN"));
        }
    }

    @Nested
    @DisplayName("Credit card detection")
    class CreditCardDetection {

        @ParameterizedTest
        @ValueSource(strings = {
            "4111111111111111",
            "4111-1111-1111-1111",
            "4111 1111 1111 1111",
            "5500000000000004",
            "3782822463100051"
        })
        @DisplayName("detects various credit card number formats")
        void detectsCardFormats(String card) {
            assertTrue(detector.containsPii("Card: " + card));
        }

        @Test
        @DisplayName("identifies CREDIT_CARD type")
        void identifiesCardType() {
            List<String> types = detector.identifyPiiTypes("Payment card 4111111111111111");
            assertTrue(types.contains("CREDIT_CARD"));
        }
    }

    @Nested
    @DisplayName("Email detection")
    class EmailDetection {

        @ParameterizedTest
        @ValueSource(strings = {
            "user@example.com",
            "john.doe@bank.org",
            "test+tag@domain.co.uk"
        })
        @DisplayName("detects email addresses")
        void detectsEmails(String email) {
            assertTrue(detector.containsPii("Contact: " + email));
        }

        @Test
        @DisplayName("identifies EMAIL type")
        void identifiesEmailType() {
            List<String> types = detector.identifyPiiTypes("Email: john@example.com");
            assertTrue(types.contains("EMAIL"));
        }
    }

    @Nested
    @DisplayName("Phone number detection")
    class PhoneDetection {

        @ParameterizedTest
        @ValueSource(strings = {
            "(555) 123-4567",
            "555-123-4567",
            "555.123.4567",
            "5551234567"
        })
        @DisplayName("detects US phone number formats")
        void detectsPhoneFormats(String phone) {
            assertTrue(detector.containsPii("Phone: " + phone));
        }

        @Test
        @DisplayName("identifies PHONE type")
        void identifiesPhoneType() {
            List<String> types = detector.identifyPiiTypes("Call (555) 123-4567");
            assertTrue(types.contains("PHONE"));
        }
    }

    @Nested
    @DisplayName("Date of birth detection")
    class DobDetection {

        @Test
        @DisplayName("detects MM/DD/YYYY format")
        void detectsUsFormat() {
            assertTrue(detector.containsPii("DOB: 01/15/1990"));
        }

        @Test
        @DisplayName("detects YYYY-MM-DD format")
        void detectsIsoFormat() {
            assertTrue(detector.containsPii("Birth date: 1990-01-15"));
        }
    }

    @Nested
    @DisplayName("Multiple PII types in single text")
    class MultiplePiiTypes {

        @Test
        @DisplayName("detects multiple PII types in one string")
        void detectsMultipleTypes() {
            String text = "Customer John, SSN 123-45-6789, email john@bank.com, phone (555) 123-4567";

            assertTrue(detector.containsPii(text));
            List<String> types = detector.identifyPiiTypes(text);
            assertTrue(types.size() >= 3);
            assertTrue(types.contains("SSN"));
            assertTrue(types.contains("EMAIL"));
            assertTrue(types.contains("PHONE"));
        }
    }

    @Nested
    @DisplayName("Non-PII text (false positive avoidance)")
    class NoPiiDetection {

        @Test
        @DisplayName("does not flag regular transaction amounts")
        void noFlagAmounts() {
            assertFalse(detector.containsPii("Amount: $1500.00"));
        }

        @Test
        @DisplayName("does not flag short numeric references")
        void noFlagShortNumbers() {
            assertFalse(detector.containsPii("Transaction ID: 12345"));
        }

        @Test
        @DisplayName("does not flag regular text")
        void noFlagText() {
            assertFalse(detector.containsPii("Transfer from checking to savings"));
        }
    }

    @Nested
    @DisplayName("Edge cases and null safety")
    class EdgeCases {

        @Test
        @DisplayName("returns false for null input")
        void handlesNull() {
            assertFalse(detector.containsPii(null));
        }

        @Test
        @DisplayName("returns false for empty string")
        void handlesEmpty() {
            assertFalse(detector.containsPii(""));
        }

        @Test
        @DisplayName("returns empty list for null identifyPiiTypes input")
        void identifyReturnsEmptyForNull() {
            assertTrue(detector.identifyPiiTypes(null).isEmpty());
        }

        @Test
        @DisplayName("returns empty list for empty identifyPiiTypes input")
        void identifyReturnsEmptyForEmpty() {
            assertTrue(detector.identifyPiiTypes("").isEmpty());
        }
    }
}
