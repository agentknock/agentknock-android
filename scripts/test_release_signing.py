import importlib.util
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

import release_signing as signing


KEY = "projects/test-project/locations/europe-north1/keyRings/android/cryptoKeys/app/cryptoKeyVersions/1"
UPLOAD_KEY = KEY.replace("/cryptoKeys/app/", "/cryptoKeys/upload/")

spec = importlib.util.spec_from_file_location("sign_release", Path(__file__).with_name("sign-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class CloudKeyTests(unittest.TestCase):
    def setUp(self):
        self.metadata = {"name": KEY, "state": "ENABLED", "protectionLevel": "HSM",
                         "algorithm": "RSA_SIGN_PKCS1_4096_SHA512"}

    def test_requires_an_explicit_key_version(self):
        with patch.object(signing, "kms_get") as get:
            for key in (KEY.rsplit("/", 1)[0], KEY + ":RSA", "https://example.com/key"):
                with self.subTest(key=key), self.assertRaises(ValueError):
                    signing.check_cloud_key(key, "test-token", signing.APP_SIGNING_CERTIFICATE)
            get.assert_not_called()

    def test_rejects_disabled_software_and_incompatible_keys(self):
        for field, value in (("state", "DISABLED"), ("protectionLevel", "SOFTWARE"),
                             ("algorithm", "RSA_SIGN_PKCS1_4096_SHA256"), ("name", KEY + "0")):
            with self.subTest(field=field), patch.dict(self.metadata, {field: value}), \
                    patch.object(signing, "kms_get", side_effect=self.key_response(signing.APP_SIGNING_CERTIFICATE)):
                with self.assertRaises(ValueError):
                    signing.check_cloud_key(KEY, "test-token", signing.APP_SIGNING_CERTIFICATE)

    def key_response(self, certificate):
        public_key = subprocess.check_output([
            "openssl", "x509", "-in", str(certificate), "-pubkey", "-noout",
        ]).decode()

        def get(resource, token):
            self.assertEqual(token, "test-token")
            if resource == KEY:
                return self.metadata
            if resource == f"{KEY}/publicKey":
                return {"pem": public_key}
            self.fail(f"Unexpected KMS resource: {resource}")
        return get

    def test_public_key_must_match_the_existing_certificate(self):
        with patch.object(signing, "kms_get", side_effect=self.key_response(signing.PLAY_UPLOAD_CERTIFICATE)):
            with self.assertRaisesRegex(ValueError, "does not match"):
                signing.check_cloud_key(KEY, "test-token", signing.APP_SIGNING_CERTIFICATE)

    def test_accepts_the_existing_key_in_an_enabled_hsm_version(self):
        with patch.object(signing, "kms_get", side_effect=self.key_response(signing.APP_SIGNING_CERTIFICATE)):
            signing.check_cloud_key(KEY, "test-token", signing.APP_SIGNING_CERTIFICATE)


class ReleaseSigningTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {
            "GCP_SIGNING_KEY_VERSION": KEY, "GCP_PLAY_UPLOAD_KEY_VERSION": UPLOAD_KEY,
            "GCP_ACCESS_TOKEN": "test-token", "GITHUB_SHA": "source-commit",
        })
        environment.start()
        self.addCleanup(environment.stop)

    def test_upload_key_mismatch_prevents_all_signing(self):
        def check(key, token, certificate):
            if key == UPLOAD_KEY:
                raise ValueError("wrong upload key")

        with patch.object(release, "check_cloud_key", side_effect=check), \
                patch.object(release, "sign_artifacts") as sign:
            with self.assertRaisesRegex(ValueError, "wrong upload key"):
                release.main()
            sign.assert_not_called()

    def test_uses_separate_keys_and_records_both_certificates(self):
        certificates = {path: path.read_bytes() for path in (
            signing.APP_SIGNING_CERTIFICATE, signing.PLAY_UPLOAD_CERTIFICATE,
        )}
        previous = Path.cwd()
        with tempfile.TemporaryDirectory() as temporary:
            os.chdir(temporary)
            try:
                Path("version.txt").write_text("0.3.0\n")
                Path("app").mkdir()
                Path("app/build.gradle.kts").write_text("val agentknockVersionCode = 51\n")
                Path("signing").mkdir()
                for path, content in certificates.items():
                    path.write_bytes(content)

                def sign(inputs, outputs, certificate, storetype, keystore, alias, password_env):
                    self.assertEqual(storetype, "GOOGLECLOUD")
                    self.assertEqual(keystore, alias.split("/cryptoKeys/")[0])
                    self.assertEqual(os.environ[password_env], "test-token")
                    for source, destination in zip(inputs, outputs, strict=True):
                        expected_key = UPLOAD_KEY if destination.suffix == ".aab" else KEY
                        expected_certificate = (signing.PLAY_UPLOAD_CERTIFICATE if destination.suffix == ".aab"
                                                else signing.APP_SIGNING_CERTIFICATE)
                        self.assertEqual(source.suffix, destination.suffix)
                        self.assertEqual((alias, certificate), (expected_key, expected_certificate))
                        destination.write_text(f"signed by {alias}")

                with patch.object(release, "check_cloud_key") as check, \
                        patch.object(release, "sign_artifacts", side_effect=sign), \
                        patch.object(release, "verify_artifacts") as verify:
                    release.main()
                check.assert_has_calls([
                    unittest.mock.call(KEY, "test-token", signing.APP_SIGNING_CERTIFICATE),
                    unittest.mock.call(UPLOAD_KEY, "test-token", signing.PLAY_UPLOAD_CERTIFICATE),
                ], any_order=True)
                self.assertEqual(verify.call_args.args[1:3],
                                 (signing.APP_SIGNING_CERTIFICATE, signing.PLAY_UPLOAD_CERTIFICATE))
                self.assertNotIn("GCP_ACCESS_TOKEN", os.environ)
                assets = Path("release-assets")
                metadata = json.loads((assets / "version.json").read_text())
                for field, certificate in (("appSigningCertificateSha256", signing.APP_SIGNING_CERTIFICATE),
                                           ("playUploadCertificateSha256", signing.PLAY_UPLOAD_CERTIFICATE)):
                    self.assertEqual(metadata[field], signing.certificate_fingerprint(certificate))
                    self.assertEqual((assets / certificate.name).read_bytes(), certificates[certificate])
                self.assertNotEqual(metadata["appSigningCertificateSha256"], metadata["playUploadCertificateSha256"])
            finally:
                os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
