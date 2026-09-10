#!/usr/bin/env python3
"""Exercise the release signing tools on real artifacts with a disposable RSA-4096 key."""

import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import zipfile

from release_signing import CERTIFICATE, sign_artifacts, unsigned_artifacts, verify_artifacts, verify_bundle


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
        certificate = directory / "test.pem"
        password_env = "TEST_SIGNING_PASSWORD"
        os.environ[password_env] = secrets.token_hex(32)
        try:
            subprocess.run([
                "keytool", "-genkeypair", "-noprompt", "-keystore", str(keystore),
                "-storetype", "PKCS12", "-storepass:env", password_env, "-keypass:env", password_env,
                "-alias", "test", "-keyalg", "RSA", "-keysize", "4096", "-validity", "30",
                "-dname", "CN=Agentknock disposable signing test",
            ], check=True)
            subprocess.run([
                "keytool", "-exportcert", "-rfc", "-keystore", str(keystore),
                "-storepass:env", password_env, "-alias", "test", "-file", str(certificate),
            ], check=True)
            outputs = [directory / name for name in ("foss.apk", "play.apk", "play.aab")]
            sign_artifacts(unsigned_artifacts(Path("unsigned-release")), outputs, certificate,
                           "PKCS12", str(keystore), "test", password_env)
        finally:
            del os.environ[password_env]
        verify_artifacts(outputs, certificate, directory)
        expect_rejection(lambda: verify_artifacts(outputs, CERTIFICATE, directory), ValueError)
        # Adding an unsigned entry must fail both APK and JAR verification.
        with zipfile.ZipFile(outputs[0], "a") as archive:
            archive.writestr("unsigned-entry.txt", "modified after signing")
        expect_rejection(lambda: verify_artifacts(outputs, certificate, directory), subprocess.CalledProcessError)
        with zipfile.ZipFile(outputs[2], "a") as archive:
            archive.writestr("unsigned-entry.txt", "modified after signing")
        with tempfile.TemporaryDirectory(dir=directory) as rejected:
            expect_rejection(lambda: verify_bundle(outputs[2], certificate, Path(rejected)),
                             subprocess.CalledProcessError)
        print("Signing passed for both APKs and the AAB; wrong certificates and altered artifacts were rejected")


if __name__ == "__main__":
    main()
