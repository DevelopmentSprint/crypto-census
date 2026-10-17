#!/usr/bin/env python3
"""Exercise upload identity, failure, retry and publication boundaries without network access."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error

import upload


ID = "11111111-2222-3333-4444-555555555555"


class UploadTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.bundle = Path(self.temp.name) / "bundle.zip"
        self.bundle.write_bytes(b"already inspected fixture bytes")
        self.evidence = Path(self.temp.name) / "evidence.json"
        self.record = {"state": "INSPECTED", "published": False, "sourceSha": "a" * 40,
                       "version": "0.1.0", "bundleSha256": hashlib.sha256(self.bundle.read_bytes()).hexdigest()}
        self.evidence.write_text(json.dumps(self.record))
        self.env = patch.dict(upload.os.environ, {"GITHUB_STEP_SUMMARY": ""})
        self.env.start()
        self.addCleanup(self.env.stop)

    def status(self, state, deployment=ID):
        return json.dumps({"deploymentId": deployment, "deploymentState": state}).encode()

    def test_uploads_once_as_user_managed_and_stops_at_validated(self):
        send = Mock(side_effect=[ID.encode(), self.status("PENDING"), self.status("VALIDATING"), self.status("VALIDATED")])
        sleep = Mock()
        upload.stage(self.bundle, self.evidence, "test-token", send=send, sleep=sleep)
        calls = send.call_args_list
        self.assertIn("publishingType=USER_MANAGED", calls[0].args[0])
        self.assertIn(self.bundle.read_bytes(), calls[0].args[1])
        self.assertTrue(all(c.args[0].startswith("status?") for c in calls[1:]))
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual(json.loads(self.evidence.read_text())["state"], "VALIDATED")
        self.assertFalse(json.loads(self.evidence.read_text())["published"])

    def test_changed_bundle_or_previously_uploaded_evidence_never_sends(self):
        send = Mock()
        self.bundle.write_bytes(b"changed after inspection")
        with self.assertRaises(ValueError):
            upload.stage(self.bundle, self.evidence, "test-token", send=send)
        self.record["state"] = "UPLOADED"
        self.evidence.write_text(json.dumps(self.record))
        with self.assertRaises(ValueError):
            upload.stage(self.bundle, self.evidence, "test-token", send=send)
        send.assert_not_called()

    def test_upload_error_is_never_retried(self):
        send = Mock(side_effect=ValueError("connection lost"))
        with self.assertRaises(ValueError):
            upload.stage(self.bundle, self.evidence, "test-token", send=send)
        self.assertEqual(send.call_count, 1)

    def test_invalid_deployment_id_not_written_or_polled(self):
        send = Mock(return_value=b"unexpected credential-bearing response")
        with self.assertRaises(ValueError) as raised:
            upload.stage(self.bundle, self.evidence, "test-token", send=send)
        self.assertNotIn("credential-bearing", str(raised.exception))
        self.assertEqual(send.call_count, 1)
        self.assertNotIn("deploymentId", json.loads(self.evidence.read_text()))

    def test_failed_unknown_or_published_states_fail_closed(self):
        for state in ("FAILED", "PUBLISHING", "PUBLISHED", "unknown", None):
            self.evidence.write_text(json.dumps(self.record))
            with self.subTest(state=state), self.assertRaises(ValueError):
                upload.stage(self.bundle, self.evidence, "test-token",
                             send=Mock(side_effect=[ID.encode(), self.status(state)]))
            self.assertEqual(json.loads(self.evidence.read_text())["deploymentId"], ID)

    def test_wrong_deployment_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            upload.stage(self.bundle, self.evidence, "test-token",
                         send=Mock(side_effect=[ID.encode(), self.status("VALIDATED", "other")]))

    def test_timeout_preserves_deployment_for_recovery(self):
        with self.assertRaisesRegex(ValueError, "timed out"):
            upload.stage(self.bundle, self.evidence, "test-token", send=Mock(return_value=ID.encode()),
                         clock=Mock(side_effect=[0, 1801]))
        self.assertEqual(json.loads(self.evidence.read_text())["deploymentId"], ID)

    def test_errors_and_redirects_do_not_disclose_credentials(self):
        with patch.object(upload.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = urllib.error.URLError("private response and test-token")
            with self.assertRaises(ValueError) as raised:
                upload.request("status?id=" + ID, b"", "application/json", "test-token")
            self.assertNotIn("test-token", str(raised.exception))
        with self.assertRaises(ValueError):
            upload.NoRedirect().redirect_request(None, None, 302, "Found", {}, "https://other.invalid")
        with self.assertRaises(ValueError):
            upload.request("deployment/" + ID, b"", "application/json", "test-token")


if __name__ == "__main__":
    unittest.main()
