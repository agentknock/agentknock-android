#!/usr/bin/env python3
"""Sign tested APKs with the app key and the Play AAB with the upload key."""

import json
import os
from pathlib import Path
import shutil
import tempfile

from release_signing import (APP_SIGNING_CERTIFICATE, PLAY_UPLOAD_CERTIFICATE, artifact_names,
                             certificate_fingerprint, check_cloud_key, sign_artifacts,
                             unsigned_artifacts, verify_artifacts, write_checksums)
from version import BUILD_FILE, version_code


def main():
    app_key_version = os.environ["GCP_SIGNING_KEY_VERSION"]
    upload_key_version = os.environ["GCP_PLAY_UPLOAD_KEY_VERSION"]
    token = os.environ["GCP_ACCESS_TOKEN"]
    check_cloud_key(app_key_version, token, APP_SIGNING_CERTIFICATE)
    check_cloud_key(upload_key_version, token, PLAY_UPLOAD_CERTIFICATE)
    version = Path("version.txt").read_text().strip()
    code = version_code(Path(BUILD_FILE).read_text())
    output = Path("release-assets")
    output.mkdir()  # Never package stale files from a previous attempt.
    artifacts = [output / name for name in artifact_names(version, code)]
    inputs = unsigned_artifacts(Path("unsigned-release"))
    with tempfile.TemporaryDirectory() as temporary:
        sign_artifacts(inputs[:2], artifacts[:2], APP_SIGNING_CERTIFICATE,
                       "GOOGLECLOUD", app_key_version.split("/cryptoKeys/")[0],
                       app_key_version, "GCP_ACCESS_TOKEN")
        sign_artifacts(inputs[2:], artifacts[2:], PLAY_UPLOAD_CERTIFICATE,
                       "GOOGLECLOUD", upload_key_version.split("/cryptoKeys/")[0],
                       upload_key_version, "GCP_ACCESS_TOKEN")
        del os.environ["GCP_ACCESS_TOKEN"]
        verify_artifacts(artifacts, APP_SIGNING_CERTIFICATE, PLAY_UPLOAD_CERTIFICATE, Path(temporary))
    shutil.copyfile(APP_SIGNING_CERTIFICATE, output / "app-signing-certificate.pem")
    shutil.copyfile(PLAY_UPLOAD_CERTIFICATE, output / "play-upload-certificate.pem")
    (output / "version.json").write_text(json.dumps({
        "versionName": version,
        "versionCode": code,
        "commit": os.environ["GITHUB_SHA"],
        "signing": "google-cloud-hsm",
        "appSigningCertificateSha256": certificate_fingerprint(APP_SIGNING_CERTIFICATE),
        "playUploadCertificateSha256": certificate_fingerprint(PLAY_UPLOAD_CERTIFICATE),
    }, indent=2) + "\n")
    write_checksums(output)


if __name__ == "__main__":
    main()
