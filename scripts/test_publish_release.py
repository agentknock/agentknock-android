import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from github_release import download_published_release
from release_signing import (APP_SIGNING_CERTIFICATE, PLAY_UPLOAD_CERTIFICATE,
                             artifact_names, certificate_fingerprint)


REAL_APP_SIGNING_CERTIFICATE = APP_SIGNING_CERTIFICATE.read_bytes()
REAL_PLAY_UPLOAD_CERTIFICATE = PLAY_UPLOAD_CERTIFICATE.read_bytes()

spec = importlib.util.spec_from_file_location(
    "publish_release", Path(__file__).with_name("publish-github-release.py")
)
publish = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publish)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        previous = Path.cwd()
        os.chdir(temporary.name)
        self.addCleanup(os.chdir, previous)
        Path("version.txt").write_text("0.3.0\n")
        Path("app").mkdir()
        Path("app/build.gradle.kts").write_text("val agentknockVersionCode = 48\n")
        assets = Path("release-assets")
        assets.mkdir()
        Path("signing").mkdir()
        APP_SIGNING_CERTIFICATE.write_bytes(REAL_APP_SIGNING_CERTIFICATE)
        PLAY_UPLOAD_CERTIFICATE.write_bytes(REAL_PLAY_UPLOAD_CERTIFICATE)
        (assets / "app-signing-certificate.pem").write_bytes(REAL_APP_SIGNING_CERTIFICATE)
        (assets / "play-upload-certificate.pem").write_bytes(REAL_PLAY_UPLOAD_CERTIFICATE)
        for name in (*artifact_names("0.3.0", 48), "SHA256SUMS", "provenance.jsonl"):
            (assets / name).write_text("test fixture")
        (assets / "version.json").write_text(json.dumps({
            "commit": "source-commit", "versionName": "0.3.0", "versionCode": 48,
            "signing": "google-cloud-hsm",
            "appSigningCertificateSha256": certificate_fingerprint(APP_SIGNING_CERTIFICATE),
            "playUploadCertificateSha256": certificate_fingerprint(PLAY_UPLOAD_CERTIFICATE),
        }))
        self.release = {"id": 42, "tag_name": "v0.3.0", "draft": True, "immutable": False,
                        "body": "Release notes", "assets": []}
        self.remote_files = {}
        self.immutable_enabled = True
        self.tag_commit = "source-commit"
        self.version_changed = True
        self.output_file = Path("github-output").absolute()
        self.output_file.touch()
        self.environment = patch.dict(os.environ, {
            "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "source-commit",
            "GITHUB_OUTPUT": str(self.output_file),
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        output = patch.object(publish.subprocess, "check_output", side_effect=self.output)
        output.start()
        self.addCleanup(output.stop)
        run = patch.object(publish.subprocess, "run", side_effect=self.command)
        self.run = run.start()
        self.addCleanup(run.stop)

    def output(self, command, **kwargs):
        if command[0] == "git":
            self.assertEqual(command[1:], ["show", "HEAD^1:version.txt"])
            return "0.2.0\n" if self.version_changed else "0.3.0\n"
        endpoint = command[-1]
        # GITHUB_TOKEN cannot read Administration settings (the original release 403).
        if endpoint.endswith("/immutable-releases"):
            raise subprocess.CalledProcessError(1, command, stderr="HTTP 403")
        if "--paginate" in command:
            return json.dumps([[self.release] if self.release else []])
        if "/commits/" in endpoint:
            return json.dumps({"sha": self.tag_commit})
        if "/releases/" in endpoint:
            return json.dumps(self.release)
        self.fail(f"Unexpected command: {command}")

    def command(self, command, **kwargs):
        if command[:3] == ["gh", "release", "upload"]:
            self.remote_files = {path.name: path.read_bytes() for path in Path("release-assets").iterdir()}
            self.release["assets"] = [
                {"name": name, "size": len(content), "state": "uploaded",
                 "digest": f"sha256:{hashlib.sha256(content).hexdigest()}"}
                for name, content in self.remote_files.items()
            ]
        elif command[:3] == ["gh", "release", "edit"]:
            self.release.update(draft=False, immutable=self.immutable_enabled)
        elif command[:3] == ["gh", "release", "download"]:
            directory = Path(command[command.index("--dir") + 1])
            for name, content in self.remote_files.items():
                (directory / name).write_bytes(content)
        elif command[:2] not in (["sha256sum", "--check"], ["gh", "attestation"]):
            self.fail(f"Unexpected command: {command}")

    def mutations(self):
        return [call.args[0][2] for call in self.run.call_args_list
                if call.args[0][:3] in (["gh", "release", "upload"], ["gh", "release", "edit"])]

    def test_upload_precedes_publication(self):
        publish.main()
        self.assertEqual(self.mutations(), ["upload", "edit"])
        self.assertEqual(self.output_file.read_text(), "release-tag=v0.3.0\n")
        self.assertNotIn("release-notes.md", self.remote_files)
        self.assertFalse(Path("release-assets/release-notes.md").exists())

    def test_failed_attestation_cannot_upload_or_publish(self):
        def fail_verification(command, **kwargs):
            if command[:2] == ["gh", "attestation"]:
                raise subprocess.CalledProcessError(1, command)
            return self.command(command, **kwargs)
        self.run.side_effect = fail_verification
        with self.assertRaises(subprocess.CalledProcessError):
            publish.main()
        self.assertEqual(self.mutations(), [])

    def test_missing_play_apk_cannot_publish(self):
        Path("release-assets/agentknock-play-0.3.0-48.apk").unlink()
        with self.assertRaises(ValueError):
            publish.main()
        self.assertEqual(self.mutations(), [])

    def test_unexpected_signing_identity_cannot_publish(self):
        path = Path("release-assets/version.json")
        original = json.loads(path.read_text())
        for field, value in (("signing", "temporary-test-key"), ("appSigningCertificateSha256", "wrong"),
                             ("playUploadCertificateSha256", "wrong"),
                             ("playUploadCertificateSha256", certificate_fingerprint(APP_SIGNING_CERTIFICATE))):
            path.write_text(json.dumps({**original, field: value}))
            with self.subTest(field=field), self.assertRaises(ValueError):
                publish.main()
        self.assertEqual(self.mutations(), [])

    def test_asset_metadata_must_match_the_checkout(self):
        path = Path("release-assets/version.json")
        original = json.loads(path.read_text())
        for field, value in (("commit", "another-commit"), ("versionName", "0.4.0"), ("versionCode", 49)):
            path.write_text(json.dumps({**original, field: value}))
            with self.subTest(field=field), self.assertRaises(ValueError):
                publish.main()
        self.assertEqual(self.mutations(), [])

    def test_version_bump_requires_a_release_on_the_matching_commit(self):
        for release, commit in ((None, "source-commit"), (self.release, "another-commit")):
            self.release, self.tag_commit = release, commit
            with self.subTest(release=release), self.assertRaises(ValueError):
                publish.main()
        self.assertEqual(self.mutations(), [])

    def test_retry_leaves_published_assets_untouched(self):
        publish.main()
        self.run.reset_mock()
        self.output_file.write_text("")
        # A rerun may have newly signed Actions artifacts; promotion uses the
        # already published bytes, whose version code is already on Play.
        Path("release-assets/agentknock-play-0.3.0-48.aab").write_bytes(b"resigned bundle")
        publish.main()
        self.assertEqual(self.mutations(), [])
        self.assertTrue(any(call.args[0][:3] == ["gh", "release", "download"]
                            for call in self.run.call_args_list))
        self.assertEqual(self.output_file.read_text(), "release-tag=v0.3.0\n")

    def test_retry_recovers_when_publication_succeeded_but_response_was_lost(self):
        def lose_response(command, **kwargs):
            self.command(command, **kwargs)
            if command[:3] == ["gh", "release", "edit"]:
                raise subprocess.CalledProcessError(1, command)
        self.run.side_effect = lose_response
        with self.assertRaises(subprocess.CalledProcessError):
            publish.main()
        self.assertEqual(self.output_file.read_text(), "")
        self.run.side_effect = self.command
        self.run.reset_mock()
        publish.main()
        self.assertEqual(self.mutations(), [])
        self.assertEqual(self.output_file.read_text(), "release-tag=v0.3.0\n")

    def test_incomplete_or_corrupt_upload_cannot_be_published(self):
        for fault in ("missing", "extra", "digest", "size", "state"):
            with self.subTest(fault=fault):
                def corrupt_upload(command, **kwargs):
                    self.command(command, **kwargs)
                    if command[:3] == ["gh", "release", "upload"]:
                        assets = self.release["assets"]
                        if fault == "missing":
                            assets.pop()
                        elif fault == "extra":
                            assets.append({"name": "stale.apk"})
                        else:
                            assets[0][fault] = -1 if fault == "size" else "wrong"
                self.run.side_effect = corrupt_upload
                self.run.reset_mock()
                with self.assertRaisesRegex(ValueError, "GitHub release"):
                    publish.main()
                self.assertEqual(self.mutations(), ["upload"])
                self.assertEqual(self.output_file.read_text(), "")

    def test_mutable_publication_cannot_enable_promotion(self):
        self.immutable_enabled = False
        with self.assertRaisesRegex(ValueError, "published immutable"):
            publish.main()
        self.assertEqual(self.mutations(), ["upload", "edit"])
        self.assertEqual(self.output_file.read_text(), "")

    def test_promotion_download_rejects_drafts_mutable_releases_and_wrong_commits(self):
        publish.main()
        for field, value in (("draft", True), ("immutable", False), ("tag_name", "v0.4.0")):
            with self.subTest(field=field), patch.dict(self.release, {field: value}):
                self.run.reset_mock()
                with self.assertRaisesRegex(ValueError, "published immutable"):
                    download_published_release(Path("download"), "owner/repo", "source-commit", "v0.3.0")
                self.run.assert_not_called()
        self.tag_commit = "wrong-commit"
        with self.assertRaisesRegex(ValueError, "does not identify this commit"):
            download_published_release(Path("download"), "owner/repo", "source-commit", "v0.3.0")

    def test_promotion_download_rejects_a_tag_for_another_version(self):
        with self.assertRaisesRegex(ValueError, "checked-out version"):
            download_published_release(Path("download"), "owner/repo", "source-commit", "v0.4.0")
        self.run.assert_not_called()

    def test_promotion_download_checks_published_asset_bytes(self):
        publish.main()
        self.remote_files["agentknock-play-0.3.0-48.aab"] = b"wrong download"
        with self.assertRaisesRegex(ValueError, "verified bytes"):
            download_published_release(Path("download"), "owner/repo", "source-commit", "v0.3.0")

    def test_ordinary_merge_with_no_release_does_not_enable_promotion(self):
        self.version_changed = False
        self.release = None
        publish.main()
        self.assertEqual(self.output_file.read_text(), "")
        self.run.assert_not_called()

    def test_ordinary_merge_does_not_republish_an_earlier_release(self):
        self.version_changed = False
        self.tag_commit = "earlier-commit"
        publish.main()
        self.run.assert_not_called()
        self.assertEqual(self.output_file.read_text(), "")


if __name__ == "__main__":
    unittest.main()
