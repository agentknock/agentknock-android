#!/usr/bin/env python3
"""Publish a tested master build to the Google Play internal track."""

import hashlib
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from release_artifacts import verify_release_assets
from release_signing import artifact_names


APPLICATION = "androidpublisher/v3/applications/dev.agentknock"
HOST = "https://androidpublisher.googleapis.com"


def play_request(method, path, token, data=None, upload=False):
    headers = {"Authorization": f"Bearer {token}"}
    if data is not None:
        headers["Content-Type"] = "application/octet-stream" if upload else "application/json"
        if not upload:
            data = json.dumps(data).encode()
    request = Request(f"{HOST}/{'upload/' if upload else ''}{path}",
                      method=method, headers=headers, data=data)
    try:
        with urlopen(request, timeout=180) as response:
            body = response.read()
            return json.loads(body) if body else None
    except HTTPError as error:
        with error:
            message = error.read().decode()
        raise RuntimeError(f"Google Play {method} {path} failed ({error.code}): {message}") from None


def publish_bundle(bundle, version, code, token):
    content = bundle.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    edit = play_request("POST", f"{APPLICATION}/edits", token, {})["id"]
    path = f"{APPLICATION}/edits/{edit}"
    committed = False
    try:
        track = play_request("GET", f"{path}/tracks/internal", token)
        releases = track.get("releases", [])
        codes = [int(value) for release in releases for value in release.get("versionCodes", [])]
        if codes and max(codes) > code:
            print(f"Internal track already has newer version {max(codes)}; skipping {code}")
            return

        bundles = play_request("GET", f"{path}/bundles", token).get("bundles", [])
        existing = next((item for item in bundles if item["versionCode"] == code), None)
        if existing is not None and existing["sha256"] != digest:
            raise ValueError(f"Google Play version {code} contains a different bundle")
        completed = any(release["status"] == "completed" and str(code) in release["versionCodes"]
                        for release in releases)
        if completed:
            if existing is None:
                raise ValueError(f"Internal track version {code} has no matching bundle")
            print(f"Identical bundle {code} is already published to the internal track")
            return

        if existing is None:
            uploaded = play_request("POST", f"{path}/bundles?uploadType=media", token,
                                    content, upload=True)
            if uploaded["versionCode"] != code or uploaded["sha256"] != digest:
                raise ValueError("Uploaded bundle does not match the attested version and checksum")
        play_request("PUT", f"{path}/tracks/internal", token, {
            "track": "internal",
            "releases": [{"name": f"{version}-internal.{code}", "versionCodes": [str(code)],
                          "status": "completed"}],
        })
        # Do not cancel another release's pending review to publish an internal build.
        play_request("POST", f"{path}:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW", token)
        committed = True
        print(f"Published {version} ({code}) to the Google Play internal track")
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
    metadata = verify_release_assets(assets, os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"])
    version, code = metadata["versionName"], metadata["versionCode"]
    bundle = assets / artifact_names(version, code)[2]
    publish_bundle(bundle, version, code, token)


if __name__ == "__main__":
    main()
