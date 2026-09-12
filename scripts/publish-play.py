#!/usr/bin/env python3
"""Publish master builds internally and promote published GitHub releases to Alpha."""

import hashlib
import os
from pathlib import Path
import sys

from github_release import download_published_release
from play_api import APPLICATION, play_request
from release_artifacts import verify_release_assets
from release_notes import play_release_notes
from release_signing import artifact_names
from version import is_version_release


def publish_bundle(bundle, version, code, token, track_name, release_notes=None):
    if track_name not in {"internal", "alpha"}:
        raise ValueError("Expected the internal or Alpha testing track")
    content = bundle.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    edit = play_request("POST", f"{APPLICATION}/edits", token, {})["id"]
    path = f"{APPLICATION}/edits/{edit}"
    committed = False
    try:
        track = play_request("GET", f"{path}/tracks/{track_name}", token)
        releases = track.get("releases", [])
        codes = [int(value) for release in releases for value in release.get("versionCodes", [])]
        if codes and max(codes) > code:
            print(f"{track_name} already has newer version {max(codes)}; skipping {code}")
            return

        bundles = play_request("GET", f"{path}/bundles", token).get("bundles", [])
        existing = next((item for item in bundles if item["versionCode"] == code), None)
        if existing is not None and existing["sha256"] != digest:
            raise ValueError(f"Google Play version {code} contains a different bundle")
        completed = next((release for release in releases
                          if release["status"] == "completed" and str(code) in release["versionCodes"]), None)
        if completed:
            if existing is None:
                raise ValueError(f"{track_name} version {code} has no matching bundle")
            if not release_notes or all(note in completed.get("releaseNotes", []) for note in release_notes):
                print(f"Identical bundle {code} is already published to {track_name}")
                return

        if existing is None:
            if track_name == "alpha":
                raise ValueError(f"Release bundle {code} must already be uploaded before promotion to Alpha")
            uploaded = play_request("POST", f"{path}/bundles?uploadType=media", token,
                                    content, upload=True)
            if uploaded["versionCode"] != code or uploaded["sha256"] != digest:
                raise ValueError("Uploaded bundle does not match the attested version and checksum")
        name = f"{version}-internal.{code}" if track_name == "internal" else version
        release = dict(completed) if completed else {
            "name": name, "versionCodes": [str(code)], "status": "completed",
        }
        if release_notes:
            notes = {note["language"]: note for note in release.get("releaseNotes", [])}
            notes.update({note["language"]: note for note in release_notes})
            release["releaseNotes"] = list(notes.values())
        play_request("PUT", f"{path}/tracks/{track_name}", token, {
            "track": track_name,
            "releases": [release if item is completed else item for item in releases] if completed else [release],
        })
        # Do not cancel another release's pending review when updating either track.
        play_request("POST", f"{path}:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW", token)
        committed = True
        print(f"Published {version} ({code}) to Google Play {track_name}")
    finally:
        if not committed:
            try:
                play_request("DELETE", path, token)
            except (RuntimeError, OSError) as error:
                print(f"Could not discard Play edit {edit}: {error}", file=sys.stderr)


def main():
    if os.environ["GITHUB_EVENT_NAME"] != "push" or os.environ["GITHUB_REF"] != "refs/heads/master":
        raise ValueError("Automatic Play publishing requires a push to master")
    # Keep the Play credential out of the verifier's subprocess environments.
    token = os.environ.pop("GCP_ACCESS_TOKEN")
    if not token:
        raise ValueError("Missing Google Play access token")
    assets = Path("release-assets")
    repo, commit = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"]
    tag = os.environ["RELEASE_TAG"]
    if tag:
        metadata = download_published_release(assets, repo, commit, tag)
        track = "alpha"
    else:
        metadata = verify_release_assets(assets, repo, commit)
        track = "internal"
    version, code = metadata["versionName"], metadata["versionCode"]
    notes = None
    if tag or is_version_release():
        text = play_release_notes(Path("CHANGELOG.md").read_text(), version, repo)
        notes = [{"language": "en-GB", "text": text}]
    bundle = assets / artifact_names(version, code)[2]
    publish_bundle(bundle, version, code, token, track, notes)


if __name__ == "__main__":
    main()
