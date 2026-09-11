#!/usr/bin/env python3
"""Attach this CI run's attested artifacts to its matching draft, then publish it."""

import json
import os
from pathlib import Path
import subprocess
import tempfile

from github_release import api, download_published_release, published_release, verify_github_assets
from release_artifacts import verify_release_assets
from version import is_version_release


def release_output(tag):
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"release-tag={tag}\n")


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    commit = os.environ["GITHUB_SHA"]
    version = Path("version.txt").read_text().strip()
    tag = f"v{version}"
    version_changed = is_version_release()
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
        with tempfile.TemporaryDirectory() as temporary:
            download_published_release(Path(temporary) / "assets", repo, commit, tag)
        print(f"Verified the already published {tag}; resuming downstream promotion")
        release_output(tag)
        return
    if not api(f"repos/{repo}/immutable-releases")["enabled"]:
        raise ValueError("Enable immutable releases before publishing")
    assets = Path("release-assets")
    verify_release_assets(assets, repo, commit)
    subprocess.run(["gh", "release", "upload", tag, "--repo", repo, "--clobber",
                    *map(str, sorted(assets.iterdir()))], check=True)
    verify_github_assets(api(f"repos/{repo}/releases/{release['id']}"), assets)
    with tempfile.TemporaryDirectory() as temporary:
        notes = Path(temporary) / "release-notes.md"
        notes.write_text("Both APKs are signed with the official app-signing key. "
                         "The Play AAB is signed with the separate Google Play upload key. "
                         "Choose the FOSS APK or the Play APK with Google integrations. "
                         "The AAB is for Play publishing and cannot be installed directly.\n\n"
                         + release["body"])
        subprocess.run(["gh", "release", "edit", tag, "--repo", repo, "--draft=false",
                        "--prerelease", "--latest=false", "--verify-tag", "--notes-file", str(notes)], check=True)
    verify_github_assets(published_release(repo, commit, tag), assets)
    release_output(tag)


if __name__ == "__main__":
    main()
