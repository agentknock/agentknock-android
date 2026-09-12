#!/usr/bin/env python3
"""Check PR versions and Conventional Commits, or bump the committed Android counter."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parent.parent
BUILD_FILE = "app/build.gradle.kts"
CODE = re.compile(r"^val agentknockVersionCode = ([1-9][0-9]*)$", re.M)
SEMVER = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
SUBJECT = re.compile(r"(?:feat|fix|build|chore|ci|docs|style|refactor|perf|test|revert)(?:\([^()\n]+\))?!?: \S.*")

# These paths are not inputs to the shipped app. Everything else, including new
# source sets/modules and build configuration, conservatively requires a bump.
# Update the exclusions if the app build starts consuming any of these paths.
NON_APP_DIRECTORIES = (
    "docs/", "scripts/", "app/schemas/", "app/src/test/", "app/src/testFoss/",
    "app/src/testPlay/", "app/src/androidTest/", "app/src/androidTestFoss/",
    "app/src/androidTestPlay/", "app/src/screenshotTest/", "app/src/main/play/",
    ".github/ISSUE_TEMPLATE/", ".github/actions/setup-device/",
    ".github/actions/setup-signing/", ".github/actions/setup-bundletool/",
)
NON_APP_FILES = {
    ".gitignore", ".editorconfig", ".release-please-manifest.json",
    "release-please-config.json", "LICENSE-APACHE", "LICENSE-MIT",
    "preview-ui", "publish-internal", "publish-subscriptions",
    ".github/PULL_REQUEST_TEMPLATE.md", ".github/workflows/ci.yml",
    ".github/workflows/sign-release.yml", ".github/workflows/publish-play.yml",
}


def affects_app(path):
    return not (path in NON_APP_FILES or path.startswith(NON_APP_DIRECTORIES)
                or ("/" not in path and path.endswith(".md")))


def app_changes(base, head=None):
    # Disable rename detection so moving production code into an excluded path
    # still includes the deleted production path. NUL separators preserve names.
    paths = git("diff", "--name-only", "-z", "--no-renames", base,
                *([head] if head is not None else []), "--").split("\0")
    if head is None:
        paths += git("ls-files", "--others", "--exclude-standard", "-z").split("\0")
    return [path for path in paths if path and affects_app(path)]


def git(*args):
    output = subprocess.check_output(["git", *args], cwd=ROOT, text=True)
    return output if "-z" in args else output.strip()


def version_code(contents):
    matches = CODE.findall(contents)
    if len(matches) != 1 or int(matches[0]) > 2100000000:
        raise ValueError("Expected one Android version code between 1 and 2100000000")
    return int(matches[0])


def is_version_release():
    return git("show", "HEAD^1:version.txt") != Path("version.txt").read_text().strip()


def bumped(contents, base_code):
    # Preserve a larger deliberate bump. Repeated calls must not keep incrementing.
    code = max(version_code(contents), base_code + 1)
    if code > 2100000000:
        raise ValueError("Android version code is exhausted")
    return CODE.sub(f"val agentknockVersionCode = {code}", contents)


def check_version(base, head):
    previous = version_code(git("show", f"{base}:{BUILD_FILE}"))
    current = version_code(git("show", f"{head}:{BUILD_FILE}"))
    if current < previous:
        raise ValueError(f"versionCode {current} must not decrease from {previous}")
    changed = app_changes(base, head)
    if current == previous and changed:
        raise ValueError(f"versionCode {current} must exceed base ({previous}); "
                         f"run scripts/version.py bump. App inputs changed: {', '.join(changed)}")
    name = git("show", f"{head}:version.txt")
    if not SEMVER.fullmatch(name):
        raise ValueError("version.txt must contain a three-part semantic version")
    manifest = json.loads(git("show", f"{head}:.release-please-manifest.json"))
    if manifest["."] != name:
        raise ValueError("version.txt and the Release Please manifest disagree")
    print(f"Validated version {name} ({previous} -> {current}); "
          + ("publish new artifacts" if current > previous else "no new app release"))
    return current > previous


def check(base, head):
    subprocess.run(["git", "merge-base", "--is-ancestor", base, head], cwd=ROOT, check=True)
    if git("rev-list", "--merges", f"{base}..{head}"):
        raise ValueError("Rebase the feature branch; do not merge master into it")
    for commit in git("rev-list", f"{base}..{head}").splitlines():
        subject = git("show", "-s", "--format=%s", commit)
        if not SUBJECT.fullmatch(subject):
            raise ValueError(f"Use a Conventional Commit subject: {commit[:12]} {subject}")
    check_version(base, head)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["bump", "check", "release"])
    parser.add_argument("--base", default="origin/master")
    parser.add_argument("--head", default="HEAD")
    args = parser.parse_args()
    if args.command == "check":
        check(args.base, args.head)
    elif args.command == "release":
        publish = check_version(args.base, args.head)
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"publish-release={str(publish).lower()}\n")
    else:
        path = ROOT / BUILD_FILE
        previous = version_code(git("show", f"{args.base}:{BUILD_FILE}"))
        if app_changes(args.base):
            path.write_text(bumped(path.read_text(), previous))
        elif version_code(path.read_text()) < previous:
            raise ValueError(f"versionCode must not decrease from {previous}")
        print(f"versionCode = {version_code(path.read_text())}")


if __name__ == "__main__":
    main()
