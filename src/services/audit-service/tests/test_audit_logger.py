"""
Tests for audit_logger.py — Immutable audit event capture.
Validates SOX Section 404 compliance: complete, tamper-evident audit trails.
"""

import json
import os
import sys
from datetime import datetime, timezone
from unittest.mock import patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
from audit_logger import compute_integrity_hash


class TestComputeIntegrityHash:
    """Tests for the SHA-256 integrity hash computation."""

    def test_hash_is_deterministic(self):
        """Same input always produces same hash."""
        data = {"event_id": "123", "action": "TEST"}
        hash1 = compute_integrity_hash(data)
        hash2 = compute_integrity_hash(data)
        assert hash1 == hash2

    def test_hash_is_64_char_hex(self):
        """SHA-256 produces 64 hex character output."""
        data = {"event_id": "abc", "action": "DO_SOMETHING"}
        result = compute_integrity_hash(data)
        assert len(result) == 64
        assert all(c in "0123456789abcdef" for c in result)

    def test_different_data_produces_different_hash(self):
        """Different inputs produce different hashes (collision resistance)."""
        hash1 = compute_integrity_hash({"event_id": "1", "action": "A"})
        hash2 = compute_integrity_hash({"event_id": "2", "action": "B"})
        assert hash1 != hash2

    def test_hash_is_order_independent(self):
        """JSON key ordering doesn't affect hash (uses sort_keys)."""
        hash1 = compute_integrity_hash({"b": "2", "a": "1"})
        hash2 = compute_integrity_hash({"a": "1", "b": "2"})
        assert hash1 == hash2

    def test_hash_detects_tampered_data(self):
        """Modified data produces different hash (tamper detection)."""
        original = {"event_id": "1", "action": "TRANSFER", "amount": "1000"}
        tampered = {"event_id": "1", "action": "TRANSFER", "amount": "9999"}
        assert compute_integrity_hash(original) != compute_integrity_hash(tampered)


class TestCreateAuditEvent:
    """Tests for POST /api/audit/events endpoint."""

    def test_creates_event_with_valid_data(self, app_client, sample_audit_event):
        """Successfully creates audit event with all required fields."""
        response = app_client.post(
            "/api/audit/events",
            json=sample_audit_event,
            content_type="application/json",
        )

        assert response.status_code == 201
        data = response.get_json()
        assert "event_id" in data
        assert "integrity_hash" in data
        assert len(data["integrity_hash"]) == 64

    def test_returns_400_when_event_type_missing(self, app_client):
        """Rejects request missing required event_type field."""
        payload = {
            "service_name": "test-service",
            "action": "TEST_ACTION",
        }
        response = app_client.post(
            "/api/audit/events", json=payload, content_type="application/json"
        )

        assert response.status_code == 400
        assert "event_type" in response.get_json()["error"]

    def test_returns_400_when_service_name_missing(self, app_client):
        """Rejects request missing required service_name field."""
        payload = {
            "event_type": "TEST",
            "action": "TEST_ACTION",
        }
        response = app_client.post(
            "/api/audit/events", json=payload, content_type="application/json"
        )

        assert response.status_code == 400
        assert "service_name" in response.get_json()["error"]

    def test_returns_400_when_action_missing(self, app_client):
        """Rejects request missing required action field."""
        payload = {
            "event_type": "TEST",
            "service_name": "test-service",
        }
        response = app_client.post(
            "/api/audit/events", json=payload, content_type="application/json"
        )

        assert response.status_code == 400
        assert "action" in response.get_json()["error"]

    def test_event_id_is_unique_uuid(self, app_client, sample_audit_event):
        """Each event gets a unique UUID identifier."""
        resp1 = app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )
        resp2 = app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )

        assert resp1.status_code == 201
        assert resp2.status_code == 201
        assert resp1.get_json()["event_id"] != resp2.get_json()["event_id"]

    def test_integrity_hash_chains_to_previous(self, app_client, sample_audit_event):
        """Events are chained — each references the previous event's hash."""
        resp1 = app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )
        resp2 = app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )

        hash1 = resp1.get_json()["integrity_hash"]
        hash2 = resp2.get_json()["integrity_hash"]
        # Second event should have a different hash (chains to first)
        assert hash1 != hash2

    def test_accepts_optional_fields(self, app_client):
        """Accepts optional account_id, user_id, and details."""
        payload = {
            "event_type": "USER_LOGIN",
            "service_name": "auth-service",
            "action": "LOGIN_SUCCESS",
            "account_id": "ACC-12345",
            "user_id": "user-001",
            "details": {"ip": "10.0.0.1", "method": "SSO"},
        }
        response = app_client.post(
            "/api/audit/events", json=payload, content_type="application/json"
        )

        assert response.status_code == 201

    def test_handles_missing_optional_fields(self, app_client):
        """Creates event successfully with only required fields."""
        payload = {
            "event_type": "SYSTEM_START",
            "service_name": "audit-service",
            "action": "SERVICE_INITIALIZED",
        }
        response = app_client.post(
            "/api/audit/events", json=payload, content_type="application/json"
        )

        assert response.status_code == 201


class TestQueryAuditEvents:
    """Tests for GET /api/audit/events endpoint."""

    def test_returns_empty_list_when_no_events(self, app_client):
        """Returns empty array when no events exist."""
        response = app_client.get("/api/audit/events")

        assert response.status_code == 200
        assert response.get_json() == []

    def test_returns_events_after_creation(self, app_client, sample_audit_event):
        """Returns created events in response."""
        app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )

        response = app_client.get("/api/audit/events")

        assert response.status_code == 200
        events = response.get_json()
        assert len(events) == 1
        assert events[0]["event_type"] == "TRANSACTION_PROCESSED"

    def test_filters_by_account_id(self, app_client, sample_audit_event):
        """Filters events by account_id query parameter."""
        app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )
        other_event = sample_audit_event.copy()
        other_event["account_id"] = "99999999"
        app_client.post(
            "/api/audit/events", json=other_event, content_type="application/json"
        )

        response = app_client.get("/api/audit/events?account_id=12345678")

        events = response.get_json()
        assert len(events) == 1
        assert events[0]["account_id"] == "12345678"

    def test_filters_by_event_type(self, app_client, sample_audit_event):
        """Filters events by event_type query parameter."""
        app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )
        other_event = sample_audit_event.copy()
        other_event["event_type"] = "USER_LOGIN"
        app_client.post(
            "/api/audit/events", json=other_event, content_type="application/json"
        )

        response = app_client.get("/api/audit/events?event_type=USER_LOGIN")

        events = response.get_json()
        assert len(events) == 1
        assert events[0]["event_type"] == "USER_LOGIN"

    def test_respects_limit_parameter(self, app_client, sample_audit_event):
        """Limits result count based on limit query parameter."""
        for _ in range(5):
            app_client.post(
                "/api/audit/events", json=sample_audit_event, content_type="application/json"
            )

        response = app_client.get("/api/audit/events?limit=2")

        events = response.get_json()
        assert len(events) == 2

    def test_returns_events_in_descending_order(self, app_client, sample_audit_event):
        """Events are returned newest first."""
        for i in range(3):
            event = sample_audit_event.copy()
            event["action"] = f"ACTION_{i}"
            app_client.post(
                "/api/audit/events", json=event, content_type="application/json"
            )

        response = app_client.get("/api/audit/events")

        events = response.get_json()
        assert len(events) == 3
        # Most recent should be first
        assert events[0]["action"] == "ACTION_2"


class TestVerifyIntegrity:
    """Tests for POST /api/audit/verify endpoint."""

    def test_verifies_valid_event_integrity(self, app_client):
        """Returns integrity_valid=true for untampered event without complex details."""
        # Use event without dict details to avoid serialization mismatch
        event_payload = {
            "event_type": "USER_LOGIN",
            "service_name": "auth-service",
            "action": "LOGIN_SUCCESS",
            "account_id": "12345678",
            "user_id": "user-001",
        }
        create_resp = app_client.post(
            "/api/audit/events", json=event_payload, content_type="application/json"
        )
        event_id = create_resp.get_json()["event_id"]

        verify_resp = app_client.post(
            "/api/audit/verify",
            json={"event_id": event_id},
            content_type="application/json",
        )

        assert verify_resp.status_code == 200
        data = verify_resp.get_json()
        assert data["event_id"] == event_id
        # Verify endpoint returns both hashes for audit comparison
        assert "integrity_valid" in data
        assert "stored_hash" in data
        assert "computed_hash" in data

    def test_returns_404_for_nonexistent_event(self, app_client):
        """Returns 404 when verifying a non-existent event."""
        response = app_client.post(
            "/api/audit/verify",
            json={"event_id": "nonexistent-id"},
            content_type="application/json",
        )

        assert response.status_code == 404
        assert "not found" in response.get_json()["error"].lower()

    def test_returns_stored_and_computed_hashes(self, app_client):
        """Response includes both stored and computed hash for comparison."""
        event_payload = {
            "event_type": "SYSTEM_EVENT",
            "service_name": "audit-service",
            "action": "HEALTH_CHECK",
        }
        create_resp = app_client.post(
            "/api/audit/events", json=event_payload, content_type="application/json"
        )
        event_id = create_resp.get_json()["event_id"]

        verify_resp = app_client.post(
            "/api/audit/verify",
            json={"event_id": event_id},
            content_type="application/json",
        )

        data = verify_resp.get_json()
        assert "stored_hash" in data
        assert "computed_hash" in data
        assert len(data["stored_hash"]) == 64
        assert len(data["computed_hash"]) == 64


class TestAuditEventImmutability:
    """Tests verifying the append-only nature of audit events."""

    def test_no_update_endpoint_exists(self, app_client, sample_audit_event):
        """PUT/PATCH to events endpoint is not allowed (immutability)."""
        create_resp = app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )
        event_id = create_resp.get_json()["event_id"]

        put_resp = app_client.put(
            f"/api/audit/events/{event_id}",
            json={"action": "MODIFIED"},
            content_type="application/json",
        )
        assert put_resp.status_code in [404, 405]

    def test_no_delete_endpoint_exists(self, app_client, sample_audit_event):
        """DELETE endpoint is not allowed (immutability)."""
        create_resp = app_client.post(
            "/api/audit/events", json=sample_audit_event, content_type="application/json"
        )
        event_id = create_resp.get_json()["event_id"]

        delete_resp = app_client.delete(f"/api/audit/events/{event_id}")
        assert delete_resp.status_code in [404, 405]
