#!/usr/bin/env python3
"""Bump the Release Please PR through GitHub's signed commit API, then dispatch CI."""

import base64
import json
import os
import subprocess

from version import BUILD_FILE, bumped, version_code


def api(endpoint, payload=None):
    command = ["gh", "api", endpoint]
    if payload is not None:
        command += ["--input", "-"]
    return json.loads(subprocess.check_output(
        command, input=json.dumps(payload) if payload is not None else None, text=True
    ))


def contents(repo, ref):
    response = api(f"repos/{repo}/contents/{BUILD_FILE}?ref={ref}")
    return base64.b64decode(response["content"]).decode()


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    number = int(json.loads(os.environ["RELEASE_PR"])["number"])
    pr = api(f"repos/{repo}/pulls/{number}")
    branch = pr["head"]["ref"]
    if (pr["state"] != "open" or pr["base"]["ref"] != "master"
            or pr["head"]["repo"]["full_name"] != repo
            or branch != "release-please--branches--master"):
        raise ValueError("Expected the open Release Please PR in this repository")
    head = pr["head"]["sha"]
    original = contents(repo, head)
    updated = bumped(original, version_code(contents(repo, "master")))
    if updated != original:
        response = api("graphql", {
            "query": """mutation($input: CreateCommitOnBranchInput!) {
              createCommitOnBranch(input: $input) { commit { oid } }
            }""",
            "variables": {"input": {
                "branch": {"repositoryNameWithOwner": repo, "branchName": branch},
                "expectedHeadOid": head,
                "message": {"headline": "chore: bump Android version code"},
                "fileChanges": {"additions": [{"path": BUILD_FILE,
                    "contents": base64.b64encode(updated.encode()).decode()}]},
            }},
        })
        if response.get("errors"):
            raise ValueError(response["errors"])
        print(f"Updated release PR #{number}: {response['data']['createCommitOnBranch']['commit']['oid']}")
    # GITHUB_TOKEN cannot start unattended pull_request runs. Explicit dispatch is
    # supported and validates the branch's commits and version just like a PR run.
    subprocess.run(["gh", "workflow", "run", "ci.yml", "--repo", repo, "--ref", branch], check=True)


if __name__ == "__main__":
    main()
