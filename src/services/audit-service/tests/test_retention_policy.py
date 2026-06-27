"""
Tests for retention_policy.py — Data retention policy enforcement.
Validates GLBA, SOX, and BSA retention period compliance.
"""

import os
import sys
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
os.environ["DATABASE_URL"] = "sqlite:///:memory:"


class TestRetentionCategory:
    """Tests for RetentionCategory enum — regulatory retention periods."""

    def test_financial_record_retention_7_years(self):
        """Financial records have 7-year (2555 day) retention per GLBA/SOX."""
        from retention_policy import RetentionCategory

        assert RetentionCategory.FINANCIAL_RECORD.retention_days == 2555
        assert RetentionCategory.FINANCIAL_RECORD.category_name == "financial_record"

    def test_ctr_sar_retention_5_years(self):
        """CTR/SAR filings have 5-year (1825 day) retention per BSA."""
        from retention_policy import RetentionCategory

        assert RetentionCategory.CTR_SAR.retention_days == 1825
        assert RetentionCategory.CTR_SAR.category_name == "ctr_sar"

    def test_access_log_retention_3_years(self):
        """Access logs have 3-year (1095 day) retention per internal policy."""
        from retention_policy import RetentionCategory

        assert RetentionCategory.ACCESS_LOG.retention_days == 1095
        assert RetentionCategory.ACCESS_LOG.category_name == "access_log"

    def test_pii_data_retention_7_years(self):
        """PII data has 7-year retention post-closure."""
        from retention_policy import RetentionCategory

        assert RetentionCategory.PII_DATA.retention_days == 2555
        assert RetentionCategory.PII_DATA.category_name == "pii_data"

    def test_transaction_detail_retention_7_years(self):
        """Transaction details have 7-year retention."""
        from retention_policy import RetentionCategory

        assert RetentionCategory.TRANSACTION_DETAIL.retention_days == 2555

    def test_communication_retention_3_years(self):
        """Communications have 3-year retention."""
        from retention_policy import RetentionCategory

        assert RetentionCategory.COMMUNICATION.retention_days == 1095

    def test_all_categories_have_positive_retention(self):
        """All categories have positive retention period."""
        from retention_policy import RetentionCategory

        for category in RetentionCategory:
            assert category.retention_days > 0


class TestRetentionPolicyEnforcer:
    """Tests for RetentionPolicyEnforcer class."""

    @patch("retention_policy.Session")
    def test_get_retention_status_returns_all_categories(self, mock_session):
        """get_retention_status returns status for every category."""
        from retention_policy import RetentionCategory, RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_result = MagicMock()
        mock_result.fetchone.return_value = (100, 5)
        mock_sess_instance.execute.return_value = mock_result

        enforcer = RetentionPolicyEnforcer()
        status = enforcer.get_retention_status()

        assert len(status) == len(RetentionCategory)
        for category in RetentionCategory:
            assert category.category_name in status

    @patch("retention_policy.Session")
    def test_status_includes_total_and_expired(self, mock_session):
        """Each category status includes total_records and expired_records."""
        from retention_policy import RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_result = MagicMock()
        mock_result.fetchone.return_value = (200, 10)
        mock_sess_instance.execute.return_value = mock_result

        enforcer = RetentionPolicyEnforcer()
        status = enforcer.get_retention_status()

        for name, info in status.items():
            assert "total_records" in info
            assert "expired_records" in info
            assert "retention_days" in info
            assert "cutoff_date" in info

    @patch("retention_policy.Session")
    def test_status_handles_no_records(self, mock_session):
        """Status handles categories with no records gracefully."""
        from retention_policy import RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_result = MagicMock()
        mock_result.fetchone.return_value = (0, 0)
        mock_sess_instance.execute.return_value = mock_result

        enforcer = RetentionPolicyEnforcer()
        status = enforcer.get_retention_status()

        for name, info in status.items():
            assert info["total_records"] == 0
            assert info["expired_records"] == 0


class TestIdentifyExpiredRecords:
    """Tests for expired record identification."""

    @patch("retention_policy.Session")
    def test_identifies_expired_records_dry_run(self, mock_session):
        """Dry run identifies expired records without deleting."""
        from retention_policy import RetentionCategory, RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance

        mock_row = MagicMock()
        mock_row._mapping = {
            "event_id": "evt-001",
            "event_type": "FINANCIAL_RECORD",
            "created_at": datetime(2015, 1, 1),
        }
        mock_sess_instance.execute.return_value = iter([mock_row])

        enforcer = RetentionPolicyEnforcer()
        expired = enforcer.identify_expired_records(
            RetentionCategory.FINANCIAL_RECORD, dry_run=True
        )

        assert len(expired) == 1
        assert expired[0]["event_id"] == "evt-001"

    @patch("retention_policy.Session")
    def test_dry_run_does_not_purge(self, mock_session):
        """Dry run mode does not call archive or purge."""
        from retention_policy import RetentionCategory, RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        enforcer = RetentionPolicyEnforcer()
        enforcer._archive_and_purge = MagicMock()

        enforcer.identify_expired_records(
            RetentionCategory.FINANCIAL_RECORD, dry_run=True
        )

        enforcer._archive_and_purge.assert_not_called()

    @patch("retention_policy.Session")
    def test_returns_empty_list_when_no_expired(self, mock_session):
        """Returns empty list when no records are expired."""
        from retention_policy import RetentionCategory, RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        enforcer = RetentionPolicyEnforcer()
        expired = enforcer.identify_expired_records(RetentionCategory.ACCESS_LOG)

        assert expired == []


class TestArchiveAndPurge:
    """Tests for archive safety mechanisms."""

    @patch("retention_policy.Session")
    def test_verify_archive_defaults_to_safe(self, mock_session):
        """_verify_archive defaults to False (safe — no purge)."""
        from retention_policy import RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance

        enforcer = RetentionPolicyEnforcer()
        result = enforcer._verify_archive("s3://some-key", 100)

        assert result is False

    @patch("retention_policy.Session")
    def test_archive_to_glacier_returns_s3_key(self, mock_session):
        """_archive_to_glacier generates correct S3 path."""
        from retention_policy import RetentionCategory, RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance

        enforcer = RetentionPolicyEnforcer()
        key = enforcer._archive_to_glacier(
            [{"event_id": "1"}], RetentionCategory.FINANCIAL_RECORD
        )

        assert key.startswith("s3://bofa-audit-archive/financial_record/")
        assert key.endswith(".json.gz.enc")

    @patch("retention_policy.Session")
    def test_archive_and_purge_aborts_on_verification_failure(self, mock_session):
        """Purge is aborted if archive verification fails (data safety)."""
        from retention_policy import RetentionCategory, RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance

        enforcer = RetentionPolicyEnforcer()
        records = [{"event_id": "evt-001"}, {"event_id": "evt-002"}]

        with pytest.raises(RuntimeError, match="Archive verification failed"):
            enforcer._archive_and_purge(records, RetentionCategory.FINANCIAL_RECORD)


class TestGenerateRetentionReport:
    """Tests for the retention compliance report."""

    @patch("retention_policy.Session")
    def test_report_includes_all_fields(self, mock_session):
        """Retention report has required compliance fields."""
        from retention_policy import RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_result = MagicMock()
        mock_result.fetchone.return_value = (100, 0)
        mock_sess_instance.execute.return_value = mock_result

        enforcer = RetentionPolicyEnforcer()
        report = enforcer.generate_retention_report()

        assert "report_date" in report
        assert "policy_version" in report
        assert "categories" in report
        assert "compliant" in report
        assert "next_review" in report

    @patch("retention_policy.Session")
    def test_compliant_when_no_expired_records(self, mock_session):
        """Report shows compliant=True when no records are expired."""
        from retention_policy import RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_result = MagicMock()
        mock_result.fetchone.return_value = (100, 0)
        mock_sess_instance.execute.return_value = mock_result

        enforcer = RetentionPolicyEnforcer()
        report = enforcer.generate_retention_report()

        assert report["compliant"] is True

    @patch("retention_policy.Session")
    def test_non_compliant_when_expired_records_exist(self, mock_session):
        """Report shows compliant=False when expired records exist."""
        from retention_policy import RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_result = MagicMock()
        mock_result.fetchone.return_value = (100, 5)
        mock_sess_instance.execute.return_value = mock_result

        enforcer = RetentionPolicyEnforcer()
        report = enforcer.generate_retention_report()

        assert report["compliant"] is False

    @patch("retention_policy.Session")
    def test_next_review_is_90_days_from_now(self, mock_session):
        """Next review date is approximately 90 days in the future."""
        from retention_policy import RetentionPolicyEnforcer

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_result = MagicMock()
        mock_result.fetchone.return_value = (0, 0)
        mock_sess_instance.execute.return_value = mock_result

        enforcer = RetentionPolicyEnforcer()
        report = enforcer.generate_retention_report()

        next_review = datetime.fromisoformat(report["next_review"])
        now = datetime.now(timezone.utc)
        diff = (next_review - now).days
        assert 89 <= diff <= 91
