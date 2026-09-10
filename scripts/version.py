#!/usr/bin/env python3
"""Check PR versions and Conventional Commits, or bump the committed Android counter."""

import argparse
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parent.parent
BUILD_FILE = "app/build.gradle.kts"
CODE = re.compile(r"^val agentknockVersionCode = ([1-9][0-9]*)$", re.M)
SEMVER = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
SUBJECT = re.compile(r"(?:feat|fix|build|chore|ci|docs|style|refactor|perf|test|revert)(?:\([^()\n]+\))?!?: \S.*")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def version_code(contents):
    matches = CODE.findall(contents)
    if len(matches) != 1 or int(matches[0]) > 2100000000:
        raise ValueError("Expected one Android version code between 1 and 2100000000")
    return int(matches[0])


def bumped(contents, base_code):
    # Preserve a larger deliberate bump. Repeated calls must not keep incrementing.
    code = max(version_code(contents), base_code + 1)
    if code > 2100000000:
        raise ValueError("Android version code is exhausted")
    return CODE.sub(f"val agentknockVersionCode = {code}", contents)


def check(base, head):
    subprocess.run(["git", "merge-base", "--is-ancestor", base, head], cwd=ROOT, check=True)
    if git("rev-list", "--merges", f"{base}..{head}"):
        raise ValueError("Rebase the feature branch; do not merge master into it")
    for commit in git("rev-list", f"{base}..{head}").splitlines():
        subject = git("show", "-s", "--format=%s", commit)
        if not SUBJECT.fullmatch(subject):
            raise ValueError(f"Use a Conventional Commit subject: {commit[:12]} {subject}")
    previous = version_code(git("show", f"{base}:{BUILD_FILE}"))
    current = version_code(git("show", f"{head}:{BUILD_FILE}"))
    if current <= previous:
        raise ValueError(f"versionCode {current} must exceed master ({previous}); run scripts/version.py bump")
    name = git("show", f"{head}:version.txt")
    if not SEMVER.fullmatch(name):
        raise ValueError("version.txt must contain a three-part semantic version")
    manifest = json.loads(git("show", f"{head}:.release-please-manifest.json"))
    if manifest["."] != name:
        raise ValueError("version.txt and the Release Please manifest disagree")
    print(f"Validated PR commits and version {name} ({current} > {previous})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["bump", "check"])
    parser.add_argument("--base", default="origin/master")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    if args.command == "check":
        check(args.base, args.head)
    else:
        path = ROOT / BUILD_FILE
        previous = version_code(git("show", f"{args.base}:{BUILD_FILE}"))
        path.write_text(bumped(path.read_text(), previous))
        print(f"versionCode = {version_code(path.read_text())}")


if __name__ == "__main__":
    main()
