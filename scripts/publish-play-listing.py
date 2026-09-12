#!/usr/bin/env python3
"""Publish changed, reviewed Play listing files with GPP and a review-safe commit."""

import argparse
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import quote

from play_api import APPLICATION, play_request
from version import git


PLAY_METADATA = "app/src/main/play"
# GPP 4.1.1 preserves this edit and marker when publishListing uses --no-commit.
# Recheck this contract when upgrading GPP; never use its default commit, which
# does not set changesInReviewBehavior and can cancel an existing Play review.
EDIT_FILE = Path("app/build/gpp/dev.agentknock.txt")
SKIPPED_FILE = EDIT_FILE.with_suffix(".skipped")


def listing_changed(base, head):
    return git("rev-parse", f"{base}:{PLAY_METADATA}") != git("rev-parse", f"{head}:{PLAY_METADATA}")


def current_listing():
    # Refresh after taking the shared publishing lock, including on reruns. An
    # older workflow must not restore a listing superseded by a newer merge.
    git("fetch", "--no-tags", "origin", "master")
    return not listing_changed("FETCH_HEAD", "HEAD")


def publish_listing(token):
    if EDIT_FILE.exists() or SKIPPED_FILE.exists():
        raise ValueError("Unexpected saved GPP edit; use a clean CI checkout")
    committed = False
    edit = None
    try:
        subprocess.run(["./gradlew", ":app:publishPlayReleaseListing", "--no-commit", "--rerun-tasks"],
                       check=True)
        edit = EDIT_FILE.read_text().strip()
        if not edit or not SKIPPED_FILE.is_file():
            raise ValueError("GPP did not leave the expected validated, uncommitted edit")
        if not current_listing():
            print("A newer listing reached master during upload; discarding this edit")
            return
        play_request("POST", f"{APPLICATION}/edits/{quote(edit, safe='')}:commit"
                     "?changesInReviewBehavior=ERROR_IF_IN_REVIEW",
                     token)
        committed = True
        print("Published the reviewed Google Play listing")
    finally:
        if not committed:
            # An upload failure can leave an edit before GPP writes its marker.
            pending = EDIT_FILE.read_text().strip() if EDIT_FILE.is_file() else edit
            if pending:
                try:
                    play_request("DELETE", f"{APPLICATION}/edits/{quote(pending, safe='')}", token)
                except (RuntimeError, OSError) as error:
                    print(f"Could not discard Play edit {pending}: {error}", file=sys.stderr)
        EDIT_FILE.unlink(missing_ok=True)
        SKIPPED_FILE.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["check", "publish"])
    args = parser.parse_args()
    if os.environ["GITHUB_EVENT_NAME"] != "push" or os.environ["GITHUB_REF"] != "refs/heads/master":
        raise ValueError("Automatic listing publication requires a push to master")
    if args.command == "check":
        changed = listing_changed(os.environ["PUSH_BASE"], "HEAD") and current_listing()
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"changed={str(changed).lower()}\n")
        print("Listing upload required" if changed else "Listing unchanged or superseded; skipping")
    else:
        token = os.environ.pop("GCP_ACCESS_TOKEN")
        if not token:
            raise ValueError("Missing Google Play access token")
        publish_listing(token)


if __name__ == "__main__":
    main()
