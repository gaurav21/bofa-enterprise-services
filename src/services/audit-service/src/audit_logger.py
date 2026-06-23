"""
BofA Audit Logger Service

Captures immutable audit events for all financial operations.
SOX Section 404 requires complete, tamper-evident audit trails.
All records are append-only — no UPDATE or DELETE operations permitted.

WARNING: This service has ZERO test coverage.
"""

import hashlib
import json
import os
import uuid
from datetime import datetime, timezone
from typing import Dict, List, Optional

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from flask import Flask, jsonify, request
from sqlalchemy import Column, DateTime, String, Text, create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker

app = Flask(__name__)

DATABASE_URL = os.environ.get(
    "DATABASE_URL",
    "oracle+cx_oracle://audit_svc:password@oracle-rac-prod-1.bofa.internal:1521/AUDIT_SVC"
)

engine = create_engine(DATABASE_URL, pool_size=20, max_overflow=10)
Session = sessionmaker(bind=engine)
Base = declarative_base()


class AuditEvent(Base):
    """Immutable audit event record. No UPDATE trigger exists — enforced at DB level."""
    __tablename__ = "audit_events"

    event_id = Column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    event_type = Column(String(50), nullable=False, index=True)
    service_name = Column(String(100), nullable=False, index=True)
    account_id = Column(String(20), index=True)
    user_id = Column(String(50))
    action = Column(String(100), nullable=False)
    details = Column(Text)
    ip_address = Column(String(45))
    integrity_hash = Column(String(64), nullable=False)
    previous_hash = Column(String(64))
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc), nullable=False)


def compute_integrity_hash(event_data: Dict) -> str:
    """Compute SHA-256 hash for tamper detection. Chain links to previous event."""
    canonical = json.dumps(event_data, sort_keys=True, default=str)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def get_last_hash() -> Optional[str]:
    """Get the integrity hash of the most recent audit event for chaining."""
    session = Session()
    try:
        last_event = session.query(AuditEvent).order_by(
            AuditEvent.created_at.desc()
        ).first()
        return last_event.integrity_hash if last_event else None
    finally:
        session.close()


@app.route("/api/audit/events", methods=["POST"])
def create_audit_event():
    """Record a new immutable audit event."""
    data = request.get_json()

    required_fields = ["event_type", "service_name", "action"]
    for field in required_fields:
        if field not in data:
            return jsonify({"error": f"Missing required field: {field}"}), 400

    event_id = str(uuid.uuid4())
    previous_hash = get_last_hash()

    event_data = {
        "event_id": event_id,
        "event_type": data["event_type"],
        "service_name": data["service_name"],
        "account_id": data.get("account_id"),
        "user_id": data.get("user_id"),
        "action": data["action"],
        "details": data.get("details"),
        "previous_hash": previous_hash,
    }

    integrity_hash = compute_integrity_hash(event_data)

    session = Session()
    try:
        event = AuditEvent(
            event_id=event_id,
            event_type=data["event_type"],
            service_name=data["service_name"],
            account_id=data.get("account_id"),
            user_id=data.get("user_id"),
            action=data["action"],
            details=json.dumps(data.get("details", {})),
            ip_address=request.remote_addr,
            integrity_hash=integrity_hash,
            previous_hash=previous_hash,
        )
        session.add(event)
        session.commit()

        return jsonify({"event_id": event_id, "integrity_hash": integrity_hash}), 201
    except Exception as e:
        session.rollback()
        return jsonify({"error": str(e)}), 500
    finally:
        session.close()


@app.route("/api/audit/events", methods=["GET"])
def query_audit_events():
    """Query audit events with filters. Read-only — no modification endpoints exist."""
    account_id = request.args.get("account_id")
    event_type = request.args.get("event_type")
    limit = int(request.args.get("limit", 100))

    session = Session()
    try:
        query = session.query(AuditEvent)
        if account_id:
            query = query.filter(AuditEvent.account_id == account_id)
        if event_type:
            query = query.filter(AuditEvent.event_type == event_type)

        events = query.order_by(AuditEvent.created_at.desc()).limit(limit).all()

        return jsonify([{
            "event_id": e.event_id,
            "event_type": e.event_type,
            "service_name": e.service_name,
            "account_id": e.account_id,
            "action": e.action,
            "integrity_hash": e.integrity_hash,
            "created_at": e.created_at.isoformat(),
        } for e in events])
    finally:
        session.close()


@app.route("/api/audit/verify", methods=["POST"])
def verify_integrity():
    """Verify the integrity chain of audit events. Used during OCC examinations."""
    data = request.get_json()
    event_id = data.get("event_id")

    session = Session()
    try:
        event = session.query(AuditEvent).filter_by(event_id=event_id).first()
        if not event:
            return jsonify({"error": "Event not found"}), 404

        # Recompute hash and verify
        event_data = {
            "event_id": event.event_id,
            "event_type": event.event_type,
            "service_name": event.service_name,
            "account_id": event.account_id,
            "user_id": event.user_id,
            "action": event.action,
            "details": event.details,
            "previous_hash": event.previous_hash,
        }

        expected_hash = compute_integrity_hash(event_data)
        is_valid = expected_hash == event.integrity_hash

        return jsonify({
            "event_id": event_id,
            "integrity_valid": is_valid,
            "stored_hash": event.integrity_hash,
            "computed_hash": expected_hash,
        })
    finally:
        session.close()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8081, debug=False)
