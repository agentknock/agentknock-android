import unittest
from unittest.mock import patch

import release_signing as signing


KEY = "projects/test-project/locations/europe-north1/keyRings/android/cryptoKeys/app/cryptoKeyVersions/1"


class CloudKeyTests(unittest.TestCase):
    def setUp(self):
        self.metadata = {"name": KEY, "state": "ENABLED", "protectionLevel": "HSM",
                         "algorithm": "RSA_SIGN_PKCS1_4096_SHA512"}

    def test_requires_an_explicit_key_version(self):
        with patch.object(signing, "kms_get") as get:
            for key in (KEY.rsplit("/", 1)[0], KEY + ":RSA", "https://example.com/key"):
                with self.subTest(key=key), self.assertRaises(ValueError):
                    signing.check_cloud_key(key, "test-token", signing.CERTIFICATE)
            get.assert_not_called()

    def test_rejects_disabled_software_and_incompatible_keys(self):
        for field, value in (("state", "DISABLED"), ("protectionLevel", "SOFTWARE"),
                             ("algorithm", "RSA_SIGN_PKCS1_4096_SHA256"), ("name", KEY + "0")):
            with self.subTest(field=field), patch.object(signing, "kms_get", return_value={
                **self.metadata, field: value,
            }), patch.object(signing.subprocess, "check_output") as output:
                with self.assertRaises(ValueError):
                    signing.check_cloud_key(KEY, "test-token", signing.CERTIFICATE)
                output.assert_not_called()

    def test_public_key_must_match_the_existing_certificate(self):
        with patch.object(signing, "kms_get", side_effect=[self.metadata, {"pem": "wrong key"}]), \
                patch.object(signing.subprocess, "check_output", side_effect=[b"certificate", b"expected", b"different"]):
            with self.assertRaisesRegex(ValueError, "does not match"):
                signing.check_cloud_key(KEY, "test-token", signing.CERTIFICATE)

    def test_accepts_the_existing_key_in_an_enabled_hsm_version(self):
        with patch.object(signing, "kms_get", side_effect=[self.metadata, {"pem": "key"}]), \
                patch.object(signing.subprocess, "check_output", side_effect=[b"certificate", b"same", b"same"]):
            signing.check_cloud_key(KEY, "test-token", signing.CERTIFICATE)


if __name__ == "__main__":
    unittest.main()
