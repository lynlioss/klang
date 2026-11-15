#!/usr/bin/env python3
"""Check release counter aggregation and the anonymous server-side report payload."""

import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("report_downloads", Path(__file__).with_name("report-downloads.py"))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class DownloadReportTest(unittest.TestCase):
    def test_counts_only_public_apk_assets(self):
        releases = [
            {"assets": [{"name": "klang.apk", "download_count": 12},
                        {"name": "klang.apk.sha256", "download_count": 99}]},
            {"assets": [{"name": "klang.apk", "download_count": 4}]},
            {"draft": True, "assets": [{"name": "klang.apk", "download_count": 8}]},
        ]
        self.assertEqual({"total_downloads": 16, "apk_count": 2}, report.download_snapshot(releases))
        self.assertEqual({"total_downloads": 0, "apk_count": 0}, report.download_snapshot([]))

    def test_payload_has_no_device_identity_and_retry_uses_same_uuid(self):
        snapshot = {"total_downloads": 16, "apk_count": 2}
        first = report.payload(snapshot, "test-token", "2026-11-15", "2026-11-15T08:00:00+00:00")
        retry = report.payload(snapshot, "test-token", "2026-11-15", "2026-11-15T08:01:00+00:00")
        next_day = report.payload(snapshot, "test-token", "2026-11-16", "2026-11-16T08:00:00+00:00")
        event = first["batch"][0]
        self.assertEqual(event["uuid"], retry["batch"][0]["uuid"])
        self.assertNotEqual(event["uuid"], next_day["batch"][0]["uuid"])
        self.assertEqual("github-release-statistics", event["distinct_id"])
        self.assertFalse(event["properties"]["$process_person_profile"])
        self.assertTrue(event["properties"]["$geoip_disable"])
        self.assertEqual(16, event["properties"]["total_downloads"])


if __name__ == "__main__":
    unittest.main()
