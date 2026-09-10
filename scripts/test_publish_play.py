import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import urlsplit


spec = importlib.util.spec_from_file_location(
    "publish_play", Path(__file__).with_name("publish-play.py")
)
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


class PlayPublicationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        previous = Path.cwd()
        os.chdir(temporary.name)
        self.addCleanup(os.chdir, previous)
        Path("release-assets").mkdir()
        self.bundle = Path("release-assets/agentknock-play-0.2.0-50.aab")
        self.bundle.write_bytes(b"the attested AAB payload")
        self.bundle_info = {"versionCode": 50, "sha256": hashlib.sha256(self.bundle.read_bytes()).hexdigest()}
        self.bundles = []
        self.track = {"track": "internal"}
        self.calls = []
        self.uploads = []
        self.commits = 0
        self.pending = None
        self.upload_response = self.bundle_info
        self.fail_upload = False
        self.fail_commit = False
        self.lose_commit_response = False
        request = patch.object(publish, "urlopen", side_effect=self.request)
        self.network = request.start()
        self.addCleanup(request.stop)
        environment = patch.dict(os.environ, {
            "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/master",
            "GITHUB_SHA": "source-commit", "GITHUB_REPOSITORY": "owner/repo",
            "GCP_ACCESS_TOKEN": "test-token",
        })
        environment.start()
        self.addCleanup(environment.stop)

    def request(self, request, timeout):
        self.assertEqual(request.get_header("Authorization"), "Bearer test-token")
        self.assertGreaterEqual(timeout, 120)
        url = urlsplit(request.full_url)
        self.assertEqual((url.scheme, url.netloc), ("https", "androidpublisher.googleapis.com"))
        method, path = request.get_method(), url.path
        self.calls.append((method, path))
        base = "/androidpublisher/v3/applications/dev.agentknock/edits"
        edit = f"{base}/test-edit"
        if (method, path) == ("POST", base):
            self.assertEqual(json.loads(request.data), {})
            self.pending = {"bundles": list(self.bundles), "track": self.track}
            result = {"id": "test-edit"}
        elif (method, path) == ("GET", f"{edit}/tracks/internal"):
            result = self.track
        elif (method, path) == ("GET", f"{edit}/bundles"):
            result = {"bundles": self.bundles} if self.bundles else {}
        elif (method, path) == ("POST", f"/upload{edit}/bundles"):
            self.assertEqual(url.query, "uploadType=media")
            self.assertEqual(request.get_header("Content-type"), "application/octet-stream")
            if self.fail_upload:
                raise HTTPError(request.full_url, 403, "Forbidden", {},
                                io.BytesIO(b'{"error":{"message":"permission denied"}}'))
            self.uploads.append(request.data)
            self.pending["bundles"].append(self.upload_response)
            result = self.upload_response
        elif (method, path) == ("PUT", f"{edit}/tracks/internal"):
            self.assertEqual(request.get_header("Content-type"), "application/json")
            self.pending["track"] = json.loads(request.data)
            result = self.pending["track"]
        elif (method, path) == ("POST", f"{edit}:commit"):
            self.assertEqual(url.query, "changesInReviewBehavior=ERROR_IF_IN_REVIEW")
            self.assertIsNone(request.data)
            if self.fail_commit:
                raise HTTPError(request.full_url, 400, "Bad Request", {},
                                io.BytesIO(b'{"error":{"message":"changes already in review"}}'))
            self.bundles, self.track = self.pending["bundles"], self.pending["track"]
            self.pending = None
            self.commits += 1
            if self.lose_commit_response:
                raise OSError("Connection lost after Google committed the edit")
            result = {"id": "test-edit"}
        elif (method, path) == ("DELETE", edit):
            self.pending = None
            return io.BytesIO(b"")
        else:
            self.fail(f"Unexpected Play API operation: {method} {path}")
        return io.BytesIO(json.dumps(result).encode())

    def publish(self):
        publish.publish_bundle(self.bundle, "0.2.0", 50, "test-token")

    def test_publishes_exact_bundle_as_completed_internal_release(self):
        self.publish()
        self.assertEqual(self.uploads, [self.bundle.read_bytes()])
        self.assertEqual(self.commits, 1)
        self.assertEqual(self.track, {
            "track": "internal",
            "releases": [{"name": "0.2.0-internal.50", "versionCodes": ["50"], "status": "completed"}],
        })

    def test_retry_does_not_upload_or_commit_again(self):
        self.publish()
        self.publish()
        self.assertEqual(len(self.uploads), 1)
        self.assertEqual(self.commits, 1)
        self.assertIsNone(self.pending)

    def test_retry_recovers_when_commit_succeeded_but_response_was_lost(self):
        self.lose_commit_response = True
        with self.assertRaises(OSError):
            self.publish()
        self.lose_commit_response = False
        self.publish()
        self.assertEqual(len(self.uploads), 1)
        self.assertEqual(self.commits, 1)

    def test_existing_identical_bundle_can_be_promoted_without_reupload(self):
        self.bundles = [self.bundle_info]
        self.track["releases"] = [{"versionCodes": ["50"], "status": "draft"}]
        self.publish()
        self.assertEqual(self.uploads, [])
        self.assertEqual(self.commits, 1)
        self.assertEqual(self.track["releases"][0]["status"], "completed")

    def test_reused_version_code_with_different_bytes_is_rejected(self):
        self.bundles = [{**self.bundle_info, "sha256": "different"}]
        with self.assertRaisesRegex(ValueError, "different bundle"):
            self.publish()
        self.assertEqual(self.uploads, [])
        self.assertEqual(self.commits, 0)
        self.assertIsNone(self.pending)

    def test_late_build_cannot_replace_a_newer_internal_release(self):
        self.track["releases"] = [{"versionCodes": ["51"], "status": "completed"}]
        self.publish()
        self.assertEqual(self.uploads, [])
        self.assertEqual(self.commits, 0)
        self.assertEqual(self.track["releases"][0]["versionCodes"], ["51"])
        self.assertIsNone(self.pending)

    def test_bad_upload_checksum_or_version_cannot_be_committed(self):
        for field, value in (("sha256", "different"), ("versionCode", 51)):
            with self.subTest(field=field):
                self.upload_response = {**self.bundle_info, field: value}
                with self.assertRaisesRegex(ValueError, "attested version and checksum"):
                    self.publish()
                self.assertEqual(self.commits, 0)
                self.assertIsNone(self.pending)

    def test_upload_permission_failure_is_reported_and_edit_discarded(self):
        self.fail_upload = True
        with self.assertRaisesRegex(RuntimeError, "403.*permission denied"):
            self.publish()
        self.assertEqual(self.commits, 0)
        self.assertIsNone(self.pending)

    def test_review_conflict_fails_without_committing_or_retrying_with_different_policy(self):
        self.fail_commit = True
        with self.assertRaisesRegex(RuntimeError, "changes already in review"):
            self.publish()
        self.assertEqual(self.commits, 0)
        self.assertIsNone(self.pending)
        self.assertEqual(sum(path.endswith(":commit") for _, path in self.calls), 1)

    def test_main_accepts_master_build_without_a_semantic_release(self):
        def verify(directory, repo, commit):
            self.assertEqual((directory, repo, commit), (Path("release-assets"), "owner/repo", "source-commit"))
            self.assertNotIn("GCP_ACCESS_TOKEN", os.environ)
            return {"versionName": "0.2.0", "versionCode": 50}
        with patch.object(publish, "verify_release_assets", side_effect=verify):
            publish.main()
        self.assertEqual(self.commits, 1)

    def test_failed_artifact_verification_prevents_any_play_api_access(self):
        with patch.object(publish, "verify_release_assets", side_effect=ValueError("bad provenance")):
            with self.assertRaisesRegex(ValueError, "bad provenance"):
                publish.main()
        self.network.assert_not_called()

    def test_pull_requests_dispatches_and_other_branches_cannot_publish(self):
        for event, ref in (("pull_request", "refs/heads/master"),
                           ("workflow_dispatch", "refs/heads/master"),
                           ("push", "refs/heads/feature")):
            with self.subTest(event=event, ref=ref), patch.dict(os.environ, {
                "GITHUB_EVENT_NAME": event, "GITHUB_REF": ref,
            }), self.assertRaisesRegex(ValueError, "push to master"):
                publish.main()
        self.network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
