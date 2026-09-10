#!/usr/bin/env python3
"""Exercise APK and AAB signing with distinct disposable RSA-4096 keys."""

import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import zipfile

from release_signing import sign_artifacts, unsigned_artifacts, verify_artifacts, verify_bundle


def expect_rejection(action, error):
    try:
        action()
    except error:
        return
    raise AssertionError("Verification accepted an invalid signing identity or altered artifact")


def main():
    with tempfile.TemporaryDirectory() as temporary:
        directory = Path(temporary)
        keystore = directory / "test.p12"
        app_certificate = directory / "app.pem"
        upload_certificate = directory / "upload.pem"
        password_env = "TEST_SIGNING_PASSWORD"
        os.environ[password_env] = secrets.token_hex(32)
        try:
            for alias, certificate in (("app", app_certificate), ("upload", upload_certificate)):
                subprocess.run([
                    "keytool", "-genkeypair", "-noprompt", "-keystore", str(keystore),
                    "-storetype", "PKCS12", "-storepass:env", password_env, "-keypass:env", password_env,
                    "-alias", alias, "-keyalg", "RSA", "-keysize", "4096", "-validity", "30",
                    "-dname", f"CN=Agentknock disposable {alias} signing test",
                ], check=True)
                subprocess.run([
                    "keytool", "-exportcert", "-rfc", "-keystore", str(keystore),
                    "-storepass:env", password_env, "-alias", alias, "-file", str(certificate),
                ], check=True)
            outputs = [directory / name for name in ("foss.apk", "play.apk", "play.aab")]
            inputs = unsigned_artifacts(Path("unsigned-release"))
            sign_artifacts(inputs[:2], outputs[:2], app_certificate,
                           "PKCS12", str(keystore), "app", password_env)
            sign_artifacts(inputs[2:], outputs[2:], upload_certificate,
                           "PKCS12", str(keystore), "upload", password_env)
        finally:
            del os.environ[password_env]
        verify_artifacts(outputs, app_certificate, upload_certificate, directory)
        expect_rejection(lambda: verify_artifacts(outputs, upload_certificate, upload_certificate, directory),
                         ValueError)
        with tempfile.TemporaryDirectory(dir=directory) as rejected:
            expect_rejection(lambda: verify_bundle(outputs[2], app_certificate, Path(rejected)),
                             subprocess.CalledProcessError)
        # Adding an unsigned entry must fail both APK and JAR verification.
        with zipfile.ZipFile(outputs[0], "a") as archive:
            archive.writestr("unsigned-entry.txt", "modified after signing")
        expect_rejection(lambda: verify_artifacts(outputs, app_certificate, upload_certificate, directory),
                         subprocess.CalledProcessError)
        with zipfile.ZipFile(outputs[2], "a") as archive:
            archive.writestr("unsigned-entry.txt", "modified after signing")
        with tempfile.TemporaryDirectory(dir=directory) as rejected:
            expect_rejection(lambda: verify_bundle(outputs[2], upload_certificate, Path(rejected)),
                             subprocess.CalledProcessError)
        print("Distinct APK and AAB signing keys verified; crossed certificates and altered artifacts rejected")


if __name__ == "__main__":
    main()
