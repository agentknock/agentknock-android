"""Verify the signed artifacts shared by GitHub and Google Play publishing."""

import json
from pathlib import Path
import subprocess

from release_signing import (APP_SIGNING_CERTIFICATE, PLAY_UPLOAD_CERTIFICATE,
                             artifact_names, certificate_fingerprint)
from version import BUILD_FILE, version_code


def release_asset_names(version, code):
    return [*artifact_names(version, code), "app-signing-certificate.pem",
            "play-upload-certificate.pem", "version.json", "SHA256SUMS", "provenance.jsonl"]


def verify_release_assets(directory, repo, commit):
    version = Path("version.txt").read_text().strip()
    metadata = json.loads((directory / "version.json").read_text())
    if (metadata["commit"] != commit or metadata["versionName"] != version
            or metadata["versionCode"] != version_code(Path(BUILD_FILE).read_text())
            or metadata["signing"] != "google-cloud-hsm"
            or metadata["appSigningCertificateSha256"] != certificate_fingerprint(APP_SIGNING_CERTIFICATE)
            or metadata["playUploadCertificateSha256"] != certificate_fingerprint(PLAY_UPLOAD_CERTIFICATE)):
        raise ValueError("Release assets do not match this commit and version")
    if {path.name for path in directory.iterdir()} != set(release_asset_names(version, metadata["versionCode"])):
        raise ValueError("Expected exactly the signed APKs, bundle, certificates, metadata, checksums and provenance")
    subprocess.run(["sha256sum", "--check", "SHA256SUMS"], cwd=directory, check=True)
    artifacts = [*directory.glob("*.apk"), *directory.glob("*.aab")]
    for artifact in artifacts:
        subprocess.run([
            "gh", "attestation", "verify", str(artifact), "--repo", repo,
            "--signer-workflow", f"{repo}/.github/workflows/sign-release.yml",
            "--source-digest", commit, "--deny-self-hosted-runners",
        ], check=True)
    return metadata
