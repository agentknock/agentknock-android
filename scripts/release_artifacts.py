"""Verify the signed artifacts shared by GitHub and Google Play publishing."""

import json
from pathlib import Path
import subprocess

from release_signing import (APP_SIGNING_CERTIFICATE, PLAY_UPLOAD_CERTIFICATE,
                             artifact_names, certificate_fingerprint)
from version import BUILD_FILE, version_code


def verify_release_assets(directory, repo, commit):
    version = Path("version.txt").read_text().strip()
    metadata = json.loads((directory / "version.json").read_text())
    if (metadata["commit"] != commit or metadata["versionName"] != version
            or metadata["versionCode"] != version_code(Path(BUILD_FILE).read_text())
            or metadata["signing"] != "google-cloud-hsm"
            or metadata["appSigningCertificateSha256"] != certificate_fingerprint(APP_SIGNING_CERTIFICATE)
            or metadata["playUploadCertificateSha256"] != certificate_fingerprint(PLAY_UPLOAD_CERTIFICATE)):
        raise ValueError("Release assets do not match this commit and version")
    subprocess.run(["sha256sum", "--check", "SHA256SUMS"], cwd=directory, check=True)
    artifacts = [*directory.glob("*.apk"), *directory.glob("*.aab")]
    if {path.name for path in artifacts} != set(artifact_names(version, metadata["versionCode"])):
        raise ValueError("Expected signed FOSS and Play APKs and a signed Play bundle")
    for artifact in artifacts:
        subprocess.run([
            "gh", "attestation", "verify", str(artifact), "--repo", repo,
            "--signer-workflow", f"{repo}/.github/workflows/sign-release.yml",
            "--source-digest", commit, "--deny-self-hosted-runners",
        ], check=True)
    return metadata
