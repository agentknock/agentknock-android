"""Verify the published GitHub release before promoting its exact Play bundle."""

import hashlib
import json
from pathlib import Path
import subprocess

from release_artifacts import release_asset_names, verify_release_assets
from version import BUILD_FILE, version_code


def api(endpoint):
    return json.loads(subprocess.check_output(["gh", "api", endpoint], text=True))


def verify_github_assets(release, directory):
    version = Path("version.txt").read_text().strip()
    expected = set(release_asset_names(version, version_code(Path(BUILD_FILE).read_text())))
    assets = release["assets"]
    if len(assets) != len(expected) or {asset["name"] for asset in assets} != expected:
        raise ValueError("GitHub release does not contain the expected release assets")
    for asset in assets:
        content = (directory / asset["name"]).read_bytes()
        if (asset["state"] != "uploaded" or asset["size"] != len(content)
                or asset["digest"] != f"sha256:{hashlib.sha256(content).hexdigest()}"):
            raise ValueError(f"GitHub release asset does not match the verified bytes: {asset['name']}")


def published_release(repo, commit, tag):
    if tag != f"v{Path('version.txt').read_text().strip()}":
        raise ValueError("Release tag does not match the checked-out version")
    release = api(f"repos/{repo}/releases/tags/{tag}")
    if release["tag_name"] != tag or release["draft"] or not release["immutable"]:
        raise ValueError("Expected a published immutable GitHub release")
    if api(f"repos/{repo}/commits/{tag}")["sha"] != commit:
        raise ValueError("Published release tag does not identify this commit")
    return release


def download_published_release(directory, repo, commit, tag):
    release = published_release(repo, commit, tag)
    directory.mkdir()  # Download into an empty directory, never over another build's artifacts.
    subprocess.run(["gh", "release", "download", tag, "--repo", repo,
                    "--dir", str(directory)], check=True)
    metadata = verify_release_assets(directory, repo, commit)
    verify_github_assets(release, directory)
    return metadata
