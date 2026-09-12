import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "publish_listing", Path(__file__).with_name("publish-play-listing.py")
)
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


class ListingPublicationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        previous = Path.cwd()
        os.chdir(temporary.name)
        self.addCleanup(os.chdir, previous)
        publish.EDIT_FILE.parent.mkdir(parents=True)
        environment = patch.dict(os.environ, {
            "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/master",
            "PUSH_BASE": "base", "GITHUB_OUTPUT": "outputs", "GCP_ACCESS_TOKEN": "test-token",
        })
        environment.start()
        self.addCleanup(environment.stop)

    def command(self, command):
        with patch("sys.argv", ["publish-play-listing.py", command]):
            publish.main()

    def upload(self, command, check):
        self.assertEqual(command, ["./gradlew", ":app:publishPlayReleaseListing", "--no-commit", "--rerun-tasks"])
        self.assertTrue(check)
        self.assertNotIn("GCP_ACCESS_TOKEN", os.environ)
        publish.EDIT_FILE.write_text("test-edit")
        # This name is GPP 4.1.1's File.marked("skipped") contract.
        Path("app/build/gpp/dev.agentknock.skipped").touch()

    @patch.object(publish, "current_listing", return_value=True)
    @patch.object(publish, "play_request")
    def test_uses_gpp_upload_and_review_safe_commit(self, request, current):
        with patch.object(publish.subprocess, "run", side_effect=self.upload):
            self.command("publish")
        request.assert_called_once_with("POST", f"{publish.APPLICATION}/edits/test-edit:commit"
                                        "?changesInReviewBehavior=ERROR_IF_IN_REVIEW", "test-token")
        self.assertFalse(publish.EDIT_FILE.exists())
        self.assertFalse(publish.SKIPPED_FILE.exists())

    @patch.object(publish, "current_listing", return_value=True)
    @patch.object(publish, "play_request")
    def test_active_review_failure_is_not_retried_with_unsafe_defaults(self, request, current):
        request.side_effect = [RuntimeError("CHANGES_ALREADY_IN_REVIEW"), None]
        with patch.object(publish.subprocess, "run", side_effect=self.upload), \
                self.assertRaisesRegex(RuntimeError, "CHANGES_ALREADY_IN_REVIEW"):
            self.command("publish")
        self.assertEqual([call.args[0] for call in request.call_args_list], ["POST", "DELETE"])
        self.assertFalse(publish.EDIT_FILE.exists())

    @patch.object(publish, "play_request")
    def test_failed_upload_discards_saved_edit_without_committing(self, request):
        def failed_upload(command, check):
            publish.EDIT_FILE.write_text("partial-edit")
            raise subprocess.CalledProcessError(1, command)
        with patch.object(publish.subprocess, "run", side_effect=failed_upload), \
                self.assertRaises(subprocess.CalledProcessError):
            self.command("publish")
        request.assert_called_once_with("DELETE", f"{publish.APPLICATION}/edits/partial-edit", "test-token")

    @patch.object(publish, "play_request")
    def test_missing_no_commit_marker_is_rejected(self, request):
        def missing_marker(command, check):
            publish.EDIT_FILE.write_text("test-edit")
        with patch.object(publish.subprocess, "run", side_effect=missing_marker), \
                self.assertRaisesRegex(ValueError, "uncommitted edit"):
            self.command("publish")
        request.assert_called_once_with("DELETE", f"{publish.APPLICATION}/edits/test-edit", "test-token")

    @patch.object(publish, "play_request")
    @patch.object(publish.subprocess, "run")
    def test_saved_edit_is_never_reused(self, run, request):
        publish.EDIT_FILE.write_text("unrelated-edit")
        with self.assertRaisesRegex(ValueError, "Unexpected saved GPP edit"):
            self.command("publish")
        run.assert_not_called()
        request.assert_not_called()
        self.assertEqual(publish.EDIT_FILE.read_text(), "unrelated-edit")

    @patch.object(publish, "current_listing", return_value=False)
    @patch.object(publish, "play_request")
    def test_newer_listing_during_upload_prevents_rollback(self, request, current):
        with patch.object(publish.subprocess, "run", side_effect=self.upload):
            self.command("publish")
        request.assert_called_once_with("DELETE", f"{publish.APPLICATION}/edits/test-edit", "test-token")

    @patch.object(publish, "current_listing")
    @patch.object(publish, "listing_changed")
    def test_only_changed_and_current_listings_request_publication(self, changed, current):
        for modified, latest, expected in ((False, True, False), (True, True, True), (True, False, False)):
            with self.subTest(modified=modified, latest=latest):
                Path("outputs").unlink(missing_ok=True)
                changed.return_value = modified
                current.return_value = latest
                self.command("check")
                self.assertEqual(Path("outputs").read_text(), f"changed={str(expected).lower()}\n")
                changed.assert_called_with("base", "HEAD")

    @patch.object(publish, "git")
    def test_change_detection_compares_listing_trees_only(self, git):
        git.side_effect = ["same-tree", "same-tree", "old-tree", "new-tree"]
        self.assertFalse(publish.listing_changed("before-push", "HEAD"))
        self.assertTrue(publish.listing_changed("before-push", "HEAD"))
        self.assertEqual(git.call_args_list[0].args,
                         ("rev-parse", "before-push:app/src/main/play/listings"))

    @patch.object(publish, "git")
    def test_stale_check_refreshes_master_after_acquiring_lock(self, git):
        git.side_effect = ["", "new-listing", "old-listing"]
        self.assertFalse(publish.current_listing())
        self.assertEqual(git.call_args_list[0].args, ("fetch", "--no-tags", "origin", "master"))

    def test_only_master_pushes_can_publish(self):
        for event, ref in (("pull_request", "refs/heads/master"), ("push", "refs/heads/feature")):
            with self.subTest(event=event, ref=ref), \
                    patch.dict(os.environ, {"GITHUB_EVENT_NAME": event, "GITHUB_REF": ref}), \
                    self.assertRaisesRegex(ValueError, "requires a push to master"):
                self.command("publish")


if __name__ == "__main__":
    unittest.main()
