#!/usr/bin/env python3
"""Sign the tested FOSS APK, Play APK, and Play AAB with the existing HSM key."""

import json
import os
from pathlib import Path
import shutil
import tempfile

from release_signing import (CERTIFICATE, artifact_names, certificate_fingerprint, check_cloud_key,
                             sign_artifacts, unsigned_artifacts, verify_artifacts, write_checksums)
from version import BUILD_FILE, version_code


def main():
    key_version = os.environ["GCP_SIGNING_KEY_VERSION"]
    token = os.environ["GCP_ACCESS_TOKEN"]
    check_cloud_key(key_version, token, CERTIFICATE)
    version = Path("version.txt").read_text().strip()
    code = version_code(Path(BUILD_FILE).read_text())
    output = Path("release-assets")
    output.mkdir()  # Never package stale files from a previous attempt.
    artifacts = [output / name for name in artifact_names(version, code)]
    with tempfile.TemporaryDirectory() as temporary:
        sign_artifacts(unsigned_artifacts(Path("unsigned-release")), artifacts, CERTIFICATE,
                       "GOOGLECLOUD", key_version.split("/cryptoKeys/")[0],
                       key_version, "GCP_ACCESS_TOKEN")
        del os.environ["GCP_ACCESS_TOKEN"]
        verify_artifacts(artifacts, CERTIFICATE, Path(temporary))
    shutil.copyfile(CERTIFICATE, output / "app-signing-certificate.pem")
    (output / "version.json").write_text(json.dumps({
        "versionName": version,
        "versionCode": code,
        "commit": os.environ["GITHUB_SHA"],
        "signing": "google-cloud-hsm",
        "signingCertificateSha256": certificate_fingerprint(CERTIFICATE),
    }, indent=2) + "\n")
    (output / "README.txt").write_text(
        "Both APKs and the Play AAB use the official Agentknock app-signing certificate.\n"
        "Install an APK directly; the AAB is an input for Google Play publishing.\n"
        "The FOSS APK has no Firebase or Play Billing dependencies.\n"
        "The Play APK includes Firebase messaging and Play Billing.\n"
        "Both variants use dev.agentknock and share app data; they cannot be installed side by side.\n"
        "Android version rules apply when updating or switching variants.\n"
        "This workflow does not upload to Google Play.\n"
    )
    write_checksums(output)


if __name__ == "__main__":
    main()
