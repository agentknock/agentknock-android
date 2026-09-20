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


# GPP 4.1.1 writes these files for this application ID with --no-commit.
# Keep the tool fixture independent of the publisher's configured paths.
GPP_EDIT_FILE = Path("app/build/gpp/dev.agentknock.txt")
GPP_SKIPPED_FILE = Path("app/build/gpp/dev.agentknock.skipped")


class ListingPublicationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        previous = Path.cwd()
        os.chdir(temporary.name)
        self.addCleanup(os.chdir, previous)
        GPP_EDIT_FILE.parent.mkdir(parents=True)
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
        self.assertEqual(command[0], "./gradlew")
        self.assertTrue({":app:publishPlayReleaseListing", "--no-commit", "--rerun-tasks"}.issubset(command))
        self.assertTrue(check)
        self.assertNotIn("GCP_ACCESS_TOKEN", os.environ)
        GPP_EDIT_FILE.write_text("test-edit")
        # This name is GPP 4.1.1's File.marked("skipped") contract.
        Path("app/build/gpp/dev.agentknock.skipped").touch()

    @patch.object(publish, "current_listing", return_value=True)
    @patch.object(publish, "play_request")
    def test_uses_gpp_upload_and_review_safe_commit(self, request, current):
        with patch.object(publish.subprocess, "run", side_effect=self.upload):
            self.command("publish")
        request.assert_called_once_with("POST", "androidpublisher/v3/applications/dev.agentknock/edits/test-edit:commit"
                                        "?changesInReviewBehavior=ERROR_IF_IN_REVIEW", "test-token")
        self.assertFalse(GPP_EDIT_FILE.exists())
        self.assertFalse(GPP_SKIPPED_FILE.exists())

    @patch.object(publish, "current_listing", return_value=True)
    @patch.object(publish, "play_request")
    def test_active_review_failure_is_not_retried_with_unsafe_defaults(self, request, current):
        request.side_effect = [RuntimeError("CHANGES_ALREADY_IN_REVIEW"), None]
        with patch.object(publish.subprocess, "run", side_effect=self.upload), \
                self.assertRaisesRegex(RuntimeError, "CHANGES_ALREADY_IN_REVIEW"):
            self.command("publish")
        self.assertEqual([call.args[0] for call in request.call_args_list], ["POST", "DELETE"])
        self.assertFalse(GPP_EDIT_FILE.exists())

    @patch.object(publish, "play_request")
    def test_failed_upload_discards_saved_edit_without_committing(self, request):
        def failed_upload(command, check):
            GPP_EDIT_FILE.write_text("partial-edit")
            raise subprocess.CalledProcessError(1, command)
        with patch.object(publish.subprocess, "run", side_effect=failed_upload), \
                self.assertRaises(subprocess.CalledProcessError):
            self.command("publish")
        request.assert_called_once_with("DELETE", "androidpublisher/v3/applications/dev.agentknock/edits/partial-edit", "test-token")

    @patch.object(publish, "play_request")
    def test_missing_no_commit_marker_is_rejected(self, request):
        def missing_marker(command, check):
            GPP_EDIT_FILE.write_text("test-edit")
        with patch.object(publish.subprocess, "run", side_effect=missing_marker), \
                self.assertRaisesRegex(ValueError, "uncommitted edit"):
            self.command("publish")
        request.assert_called_once_with("DELETE", "androidpublisher/v3/applications/dev.agentknock/edits/test-edit", "test-token")

    @patch.object(publish, "play_request")
    @patch.object(publish.subprocess, "run")
    def test_saved_edit_is_never_reused(self, run, request):
        GPP_EDIT_FILE.write_text("unrelated-edit")
        with self.assertRaisesRegex(ValueError, "Unexpected saved GPP edit"):
            self.command("publish")
        run.assert_not_called()
        request.assert_not_called()
        self.assertEqual(GPP_EDIT_FILE.read_text(), "unrelated-edit")

    @patch.object(publish, "current_listing", return_value=False)
    @patch.object(publish, "play_request")
    def test_newer_listing_during_upload_prevents_rollback(self, request, current):
        with patch.object(publish.subprocess, "run", side_effect=self.upload):
            self.command("publish")
        request.assert_called_once_with("DELETE", "androidpublisher/v3/applications/dev.agentknock/edits/test-edit", "test-token")

    def test_check_publishes_only_changed_listings_that_are_still_current(self):
        def assert_publication(expected):
            Path("outputs").unlink(missing_ok=True)
            self.command("check")
            self.assertEqual(Path("outputs").read_text(), f"changed={str(expected).lower()}\n")

        with tempfile.TemporaryDirectory() as remote, patch("version.ROOT", Path.cwd()):
            publish.git("init", "-q", "-b", "master")
            publish.git("config", "user.name", "Listing tests")
            publish.git("config", "user.email", "tests@example.invalid")
            publish.git("config", "commit.gpgsign", "false")
            publish.git("config", "gc.autoDetach", "false")
            publish.git("config", "maintenance.autoDetach", "false")
            listing = Path("app/src/main/play/listings/en-GB/title.txt")
            listing.parent.mkdir(parents=True)
            listing.write_text("Original listing\n")
            publish.git("add", str(listing))
            publish.git("commit", "-qm", "Initial listing")
            os.environ["PUSH_BASE"] = publish.git("rev-parse", "HEAD")
            publish.git("init", "--bare", "-q", remote)
            publish.git("remote", "add", "origin", remote)
            publish.git("push", "-q", "origin", "master")
            Path("README.md").write_text("Unrelated documentation\n")
            publish.git("add", "README.md")
            publish.git("commit", "-qm", "Update documentation")
            publish.git("push", "-q", "origin", "master")
            assert_publication(False)

            listing.write_text("Updated listing\n")
            publish.git("add", str(listing))
            publish.git("commit", "-qm", "Update listing")
            publish.git("push", "-q", "origin", "master")
            checkout = publish.git("rev-parse", "HEAD")
            assert_publication(True)

            Path("README.md").write_text("Newer unrelated documentation\n")
            publish.git("add", "README.md")
            publish.git("commit", "-qm", "Update documentation again")
            publish.git("push", "-q", "origin", "master")
            publish.git("checkout", "-q", "--detach", checkout)
            assert_publication(True)

            publish.git("checkout", "-q", "master")
            listing.write_text("Newer reviewed listing\n")
            publish.git("add", str(listing))
            publish.git("commit", "-qm", "Supersede listing")
            publish.git("push", "-q", "origin", "master")
            publish.git("checkout", "-q", "--detach", checkout)
            assert_publication(False)

    def test_contact_language_and_video_changes_trigger_publication(self):
        def git(*args):
            return subprocess.check_output(["git", *args], text=True).strip()

        git("init", "-q")
        git("config", "user.name", "Listing tests")
        git("config", "user.email", "tests@example.invalid")
        git("config", "commit.gpgsign", "false")
        root = Path("app/src/main/play")
        listing = root / "listings/en-GB/title.txt"
        listing.parent.mkdir(parents=True)
        listing.write_text("AgentKnock\n")
        git("add", ".")
        git("commit", "-qm", "Initial listing")
        with patch.object(publish, "git", side_effect=git):
            for name, value in (
                ("contact-email.txt", "support@example.invalid\n"),
                ("contact-website.txt", "https://example.invalid/\n"),
                ("contact-phone.txt", "+12025550123\n"),
                ("default-language.txt", "en-GB\n"),
                ("listings/en-GB/video-url.txt", "https://www.youtube.com/watch?v=example\n"),
            ):
                with self.subTest(file=name):
                    (root / name).write_text(value)
                    git("add", ".")
                    git("commit", "-qm", f"Add {name}")
                    self.assertTrue(publish.listing_changed("HEAD^", "HEAD"))
                    (root / name).unlink()
                    git("add", "-u")
                    git("commit", "-qm", f"Remove {name}")
                    self.assertTrue(publish.listing_changed("HEAD^", "HEAD"))
            Path("README.md").write_text("Unrelated documentation\n")
            git("add", ".")
            git("commit", "-qm", "Update documentation")
            self.assertFalse(publish.listing_changed("HEAD^", "HEAD"))


    def test_only_master_pushes_can_publish(self):
        for event, ref in (("pull_request", "refs/heads/master"), ("push", "refs/heads/feature")):
            with self.subTest(event=event, ref=ref), \
                    patch.dict(os.environ, {"GITHUB_EVENT_NAME": event, "GITHUB_REF": ref}), \
                    self.assertRaisesRegex(ValueError, "requires a push to master"):
                self.command("publish")


if __name__ == "__main__":
    unittest.main()
