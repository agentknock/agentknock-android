#!/usr/bin/env python3
"""Attach this CI run's attested artifacts to its matching draft, then publish it."""

import json
import os
from pathlib import Path
import subprocess

from version import BUILD_FILE, version_code
from release_signing import CERTIFICATE, artifact_names, certificate_fingerprint


def api(endpoint):
    return json.loads(subprocess.check_output(["gh", "api", endpoint], text=True))


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    commit = os.environ["GITHUB_SHA"]
    version = Path("version.txt").read_text().strip()
    tag = f"v{version}"
    version_changed = bool(subprocess.check_output([
        "git", "diff", "--name-only", "--diff-filter=M", "HEAD^1", "HEAD", "--", "version.txt"
    ], text=True).strip())
    pages = json.loads(subprocess.check_output(
        ["gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"], text=True
    ))
    release = next((item for page in pages for item in page if item["tag_name"] == tag), None)
    if release is None:
        if version_changed:
            raise ValueError(f"The semantic version changed but Release Please did not create {tag}")
        print(f"No release for {tag}; this ordinary merge's artifacts remain in Actions")
        return
    if api(f"repos/{repo}/commits/{tag}")["sha"] != commit:
        if version_changed:
            raise ValueError(f"Release tag {tag} does not identify this version's merge commit")
        print(f"{tag} belongs to another commit; retaining this merge's Actions artifacts")
        return
    if not release["draft"]:
        print(f"{tag} is already published; leaving its immutable assets intact")
        return
    if not api(f"repos/{repo}/immutable-releases")["enabled"]:
        raise ValueError("Enable immutable releases before publishing")
    assets = Path("release-assets")
    metadata = json.loads((assets / "version.json").read_text())
    if (metadata["commit"] != commit or metadata["versionName"] != version
            or metadata["versionCode"] != version_code(Path(BUILD_FILE).read_text())
            or metadata["signing"] != "google-cloud-hsm"
            or metadata["signingCertificateSha256"] != certificate_fingerprint(CERTIFICATE)):
        raise ValueError("Release assets do not match this commit and version")
    subprocess.run(["sha256sum", "--check", "SHA256SUMS"], cwd=assets, check=True)
    apks, bundles = list(assets.glob("*.apk")), list(assets.glob("*.aab"))
    expected = set(artifact_names(version, metadata["versionCode"]))
    if {path.name for path in apks + bundles} != expected:
        raise ValueError("Expected signed FOSS and Play APKs and a signed Play bundle")
    for artifact in apks + bundles:
        subprocess.run([
            "gh", "attestation", "verify", str(artifact), "--repo", repo,
            "--signer-workflow", f"{repo}/.github/workflows/sign-release.yml",
            "--source-digest", commit, "--deny-self-hosted-runners",
        ], check=True)
    subprocess.run(["gh", "release", "upload", tag, "--repo", repo, "--clobber",
                    *map(str, sorted(assets.iterdir()))], check=True)
    notes = assets / "release-notes.md"
    notes.write_text("Both APKs and the Play AAB are signed with the official app-signing key. "
                     "Choose the FOSS APK or the Play APK with Google integrations. "
                     "The AAB is for Play publishing and cannot be installed directly.\n\n"
                     + release["body"])
    subprocess.run(["gh", "release", "edit", tag, "--repo", repo, "--draft=false",
                    "--prerelease", "--latest=false", "--verify-tag", "--notes-file", str(notes)], check=True)


if __name__ == "__main__":
    main()
