import logging
import os
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, os.path.dirname(__file__))
import worker


class WorkerLogRedactionTests(unittest.TestCase):
    """Keep public CI logs free of searched identifiers and profile URLs."""
    @patch.object(worker, "patch_job")
    @patch.object(worker, "sherlock")
    @patch.object(worker, "SitesInformation")
    def test_success_logs_do_not_include_personal_identifiers(
        self, sites_mock, sherlock_mock, patch_job_mock
    ):
        username = "private_handle_987"
        job_id = "private-job-uuid"
        profile_url = "https://social.example/private_handle_987"
        site = type("Site", (), {"information": {}})()
        sites = type("Sites", (), {
            "sites": {"Example": site},
            "remove_nsfw_sites": lambda self: None,
        })()
        sites_mock.return_value = sites
        status = type("Status", (), {
            "status": worker.QueryStatus.CLAIMED,
            "query_time": 0.1,
            "context": None,
        })()
        sherlock_mock.return_value = {
            "Example": {"status": status, "url_user": profile_url}
        }

        with self.assertLogs("sherlock-worker", level="INFO") as captured:
            worker.process({"id": job_id, "username": username})

        output = "\\n".join(captured.output)
        self.assertNotIn(username, output)
        self.assertNotIn(job_id, output)
        self.assertNotIn(profile_url, output)
        self.assertIn("Finished Sherlock lookup", output)

    @patch.object(worker, "patch_job")
    @patch.object(
        worker,
        "sherlock",
        side_effect=RuntimeError("failed at https://social.example/private_handle_987"),
    )
    @patch.object(worker, "SitesInformation")
    def test_failure_logs_only_exception_class(
        self, sites_mock, sherlock_mock, patch_job_mock
    ):
        username = "private_handle_987"
        job_id = "private-job-uuid"
        site = type("Site", (), {"information": {}})()
        sites = type("Sites", (), {
            "sites": {"Example": site},
            "remove_nsfw_sites": lambda self: None,
        })()
        sites_mock.return_value = sites

        with self.assertLogs("sherlock-worker", level="ERROR") as captured:
            worker.process({"id": job_id, "username": username})

        output = "\\n".join(captured.output)
        self.assertNotIn(username, output)
        self.assertNotIn(job_id, output)
        self.assertNotIn("social.example", output)
        self.assertIn("RuntimeError", output)


if __name__ == "__main__":
    unittest.main()
