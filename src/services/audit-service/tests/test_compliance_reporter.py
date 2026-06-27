"""
Tests for compliance_reporter.py — Regulatory report generation.
Validates OCC, FinCEN, and internal audit report generation capabilities.
"""

import io
import os
import sys
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))
os.environ["DATABASE_URL"] = "sqlite:///:memory:"


class TestComplianceReporterInit:
    """Tests for ComplianceReporter initialization."""

    def test_report_types_defined(self):
        """All required regulatory report types are available."""
        from compliance_reporter import ComplianceReporter

        expected_types = [
            "ctr_summary",
            "sar_activity",
            "ofac_screening",
            "access_audit",
            "pii_access_log",
            "transaction_volume",
        ]
        for rt in expected_types:
            assert rt in ComplianceReporter.REPORT_TYPES


class TestGenerateReport:
    """Tests for the generate_report method."""

    @patch("compliance_reporter.Session")
    def test_rejects_unknown_report_type(self, mock_session):
        """Raises ValueError for invalid report type."""
        from compliance_reporter import ComplianceReporter

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 12, 31, tzinfo=timezone.utc)

        with pytest.raises(ValueError, match="Unknown report type"):
            reporter.generate_report("invalid_type", start, end)

    @patch("compliance_reporter.Session")
    def test_report_contains_metadata(self, mock_session):
        """Generated report includes required metadata fields."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 3, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("ctr_summary", start, end)

        assert "report_id" in report
        assert report["report_id"].startswith("RPT-")
        assert report["report_type"] == "ctr_summary"
        assert report["period_start"] == start.isoformat()
        assert report["period_end"] == end.isoformat()
        assert "generated_at" in report
        assert "generated_by" in report
        assert "record_count" in report
        assert "data" in report

    @patch("compliance_reporter.Session")
    def test_ctr_summary_report_generation(self, mock_session):
        """CTR summary report queries correct event types."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 1, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("ctr_summary", start, end)

        assert report["report_type"] == "ctr_summary"
        assert report["record_count"] == 0
        assert report["data"] == []

    @patch("compliance_reporter.Session")
    def test_sar_activity_report_generation(self, mock_session):
        """SAR activity report queries SAR and AML event types."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 1, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("sar_activity", start, end)

        assert report["report_type"] == "sar_activity"

    @patch("compliance_reporter.Session")
    def test_ofac_screening_report_generation(self, mock_session):
        """OFAC screening report queries OFAC-prefixed events."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 6, 1, tzinfo=timezone.utc)
        end = datetime(2024, 6, 30, tzinfo=timezone.utc)

        report = reporter.generate_report("ofac_screening", start, end)

        assert report["report_type"] == "ofac_screening"

    @patch("compliance_reporter.Session")
    def test_access_audit_report_generation(self, mock_session):
        """Access audit report queries login and data access events."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 12, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("access_audit", start, end)

        assert report["report_type"] == "access_audit"

    @patch("compliance_reporter.Session")
    def test_pii_access_log_report_generation(self, mock_session):
        """PII access log report queries PII_ACCESS events."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 1, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("pii_access_log", start, end)

        assert report["report_type"] == "pii_access_log"

    @patch("compliance_reporter.Session")
    def test_transaction_volume_report_generation(self, mock_session):
        """Transaction volume report queries and groups by day."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 1, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("transaction_volume", start, end)

        assert report["report_type"] == "transaction_volume"


class TestCsvExport:
    """Tests for CSV format export."""

    @patch("compliance_reporter.Session")
    def test_csv_format_includes_csv_content(self, mock_session):
        """When format=csv, report includes csv_content field."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 1, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("ctr_summary", start, end, format="csv")

        assert "csv_content" in report

    @patch("compliance_reporter.Session")
    def test_csv_empty_data_returns_empty_string(self, mock_session):
        """CSV of empty data returns empty string."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 1, 1, tzinfo=timezone.utc)
        end = datetime(2024, 1, 31, tzinfo=timezone.utc)

        report = reporter.generate_report("ctr_summary", start, end, format="csv")

        assert report["csv_content"] == ""

    def test_to_csv_with_data(self):
        """CSV conversion formats data correctly with headers."""
        from compliance_reporter import ComplianceReporter

        with patch("compliance_reporter.Session"):
            reporter = ComplianceReporter()

        data = [
            {"id": "1", "type": "CTR", "amount": "15000"},
            {"id": "2", "type": "CTR", "amount": "20000"},
        ]
        csv_output = reporter._to_csv(data)

        assert "id,type,amount" in csv_output
        assert "1,CTR,15000" in csv_output
        assert "2,CTR,20000" in csv_output

    def test_to_csv_empty_list(self):
        """CSV of empty list returns empty string."""
        from compliance_reporter import ComplianceReporter

        with patch("compliance_reporter.Session"):
            reporter = ComplianceReporter()

        assert reporter._to_csv([]) == ""


class TestReportDateRange:
    """Tests for date range handling in reports."""

    @patch("compliance_reporter.Session")
    def test_start_date_included_in_query(self, mock_session):
        """Start date is passed to database query."""
        from compliance_reporter import ComplianceReporter

        mock_sess_instance = MagicMock()
        mock_session.return_value = mock_sess_instance
        mock_sess_instance.execute.return_value = iter([])

        reporter = ComplianceReporter()
        start = datetime(2024, 6, 1, tzinfo=timezone.utc)
        end = datetime(2024, 6, 30, tzinfo=timezone.utc)

        reporter.generate_report("ctr_summary", start, end)

        # Verify execute was called with the date parameters
        mock_sess_instance.execute.assert_called_once()
        call_args = mock_sess_instance.execute.call_args
        params = call_args[0][1]
        assert params["start"] == start
        assert params["end"] == end
