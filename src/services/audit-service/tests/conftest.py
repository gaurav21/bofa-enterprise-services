"""
Shared test fixtures for audit service tests.
Uses SQLite in-memory database to avoid Oracle dependency.

Patches SQLAlchemy's create_engine at import time to redirect
the Oracle connection to an in-memory SQLite database.
"""

import json
import os
import sys
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import pytest
import sqlalchemy
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

# Override DATABASE_URL
os.environ["DATABASE_URL"] = "sqlite:///:memory:"

# Create a shared engine for all tests (StaticPool keeps the same connection)
_test_engine = create_engine(
    "sqlite:///:memory:",
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)

# Monkey-patch create_engine in the modules before they're imported
_original_create_engine = sqlalchemy.create_engine


def _patched_create_engine(*args, **kwargs):
    """Redirect all create_engine calls to our test SQLite engine."""
    return _test_engine


sqlalchemy.create_engine = _patched_create_engine

# Now import the modules (they'll get our patched engine)
from audit_logger import AuditEvent, Base, app, compute_integrity_hash

# Create schema
Base.metadata.create_all(_test_engine)

# Restore original (for any test that needs it)
sqlalchemy.create_engine = _original_create_engine


@pytest.fixture(autouse=True)
def clean_database():
    """Clean all data between tests while preserving schema."""
    yield
    # Cleanup after each test
    TestSession = sessionmaker(bind=_test_engine)
    session = TestSession()
    session.query(AuditEvent).delete()
    session.commit()
    session.close()


@pytest.fixture
def in_memory_engine():
    """Provide the shared test engine."""
    return _test_engine


@pytest.fixture
def db_session():
    """Create a test database session."""
    TestSession = sessionmaker(bind=_test_engine)
    session = TestSession()
    yield session
    session.close()


@pytest.fixture
def app_client():
    """Create a Flask test client with in-memory database."""
    TestSession = sessionmaker(bind=_test_engine)

    with patch("audit_logger.Session", TestSession):
        app.config["TESTING"] = True
        with app.test_client() as client:
            yield client


@pytest.fixture
def sample_audit_event():
    """Sample valid audit event payload."""
    return {
        "event_type": "TRANSACTION_PROCESSED",
        "service_name": "transaction-processing",
        "account_id": "12345678",
        "user_id": "svc-txn-processor",
        "action": "APPROVE_TRANSFER",
        "details": {"amount": "1500.00", "currency": "USD"},
    }


@pytest.fixture
def sample_events_in_db(db_session):
    """Pre-populate database with sample audit events."""
    events = []
    prev_hash = None
    for i in range(5):
        event_data = {
            "event_id": f"evt-{i:04d}",
            "event_type": "TRANSACTION_PROCESSED",
            "service_name": "transaction-processing",
            "account_id": f"acct-{i:04d}",
            "user_id": "svc-processor",
            "action": f"ACTION_{i}",
            "details": json.dumps({"index": i}),
            "previous_hash": prev_hash,
        }
        integrity_hash = compute_integrity_hash(event_data)
        audit_event = AuditEvent(
            event_id=f"evt-{i:04d}",
            event_type="TRANSACTION_PROCESSED",
            service_name="transaction-processing",
            account_id=f"acct-{i:04d}",
            user_id="svc-processor",
            action=f"ACTION_{i}",
            details=json.dumps({"index": i}),
            ip_address="10.0.0.1",
            integrity_hash=integrity_hash,
            previous_hash=prev_hash,
            created_at=datetime.now(timezone.utc) - timedelta(hours=5 - i),
        )
        db_session.add(audit_event)
        prev_hash = integrity_hash
        events.append(audit_event)

    db_session.commit()
    return events
