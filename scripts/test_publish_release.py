import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from release_signing import CERTIFICATE, certificate_fingerprint


REAL_CERTIFICATE = CERTIFICATE.read_bytes()

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
        CERTIFICATE.write_bytes(REAL_CERTIFICATE)
        for name in (*publish.artifact_names("0.3.0", 48), "SHA256SUMS", "provenance.jsonl"):
            (assets / name).write_text("test fixture")
        (assets / "version.json").write_text(json.dumps({
            "commit": "source-commit", "versionName": "0.3.0", "versionCode": 48,
            "signing": "google-cloud-hsm",
            "signingCertificateSha256": certificate_fingerprint(CERTIFICATE),
        }))
        self.release = {"tag_name": "v0.3.0", "draft": True, "body": "Release notes"}
        self.tag_commit = "source-commit"
        self.version_changed = True
        self.environment = patch.dict(os.environ, {
            "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "source-commit",
        })
        self.environment.start()
        self.addCleanup(self.environment.stop)
        output = patch.object(publish.subprocess, "check_output", side_effect=self.output)
        output.start()
        self.addCleanup(output.stop)
        api = patch.object(publish, "api", side_effect=self.api)
        api.start()
        self.addCleanup(api.stop)
        run = patch.object(publish.subprocess, "run")
        self.run = run.start()
        self.addCleanup(run.stop)

    def output(self, command, **kwargs):
        if command[0] == "git":
            return "version.txt\n" if self.version_changed else ""
        return json.dumps([[self.release] if self.release else []])

    def api(self, endpoint):
        if endpoint.endswith("immutable-releases"):
            return {"enabled": True}
        return {"sha": self.tag_commit}

    def mutations(self):
        return [call.args[0][2] for call in self.run.call_args_list
                if call.args[0][:2] == ["gh", "release"]]

    def test_upload_precedes_publication(self):
        publish.main()
        self.assertEqual(self.mutations(), ["upload", "edit"])

    def test_failed_attestation_cannot_upload_or_publish(self):
        def fail_verification(command, **kwargs):
            if command[:2] == ["gh", "attestation"]:
                raise subprocess.CalledProcessError(1, command)
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
        for field, value in (("signing", "temporary-test-key"), ("signingCertificateSha256", "wrong")):
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
        self.release["draft"] = False
        publish.main()
        self.run.assert_not_called()

    def test_ordinary_merge_does_not_republish_an_earlier_release(self):
        self.version_changed = False
        self.tag_commit = "earlier-commit"
        publish.main()
        self.run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
