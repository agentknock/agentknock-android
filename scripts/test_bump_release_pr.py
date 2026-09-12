import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "bump_release_pr", Path(__file__).with_name("bump-release-pr.py")
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleasePrBumpTests(unittest.TestCase):
    def setUp(self):
        self.repo = "owner/repo"
        self.branch = "release-please--branches--master"
        self.ref_head = "updated-release-head"
        self.pr = {
            "state": "open", "base": {"ref": "master"},
            "head": {"ref": self.branch, "sha": "stale-pr-head", "repo": {"full_name": self.repo}},
        }
        self.original = "val agentknockVersionCode = 59\n// latest branch contents\n"
        self.move_branch_after_read = False
        self.commits = []
        environment = patch.dict(os.environ, {
            "GITHUB_REPOSITORY": self.repo, "RELEASE_PR": json.dumps({"number": 13}),
        })
        environment.start()
        self.addCleanup(environment.stop)
        api = patch.object(release, "api", side_effect=self.api)
        self.requests = api.start()
        self.addCleanup(api.stop)
        dispatch = patch.object(release.subprocess, "run")
        self.dispatch = dispatch.start()
        self.addCleanup(dispatch.stop)

    def api(self, endpoint, payload=None):
        if endpoint == f"repos/{self.repo}/pulls/13":
            return self.pr
        if endpoint == f"repos/{self.repo}/git/ref/heads/{self.branch}":
            return {"object": {"type": "commit", "sha": self.ref_head}}
        if endpoint == f"repos/{self.repo}/contents/{release.BUILD_FILE}?ref=stale-pr-head":
            return {"content": base64.b64encode(b"val agentknockVersionCode = 58\n// old contents\n").decode()}
        if endpoint == f"repos/{self.repo}/contents/{release.BUILD_FILE}?ref=updated-release-head":
            if self.move_branch_after_read:
                self.ref_head = "concurrent-edit"
            return {"content": base64.b64encode(self.original.encode()).decode()}
        if endpoint == f"repos/{self.repo}/contents/{release.BUILD_FILE}?ref=master":
            return {"content": base64.b64encode(b"val agentknockVersionCode = 59\n").decode()}
        if endpoint == "graphql":
            mutation = payload["variables"]["input"]
            self.commits.append(mutation)
            if mutation["expectedHeadOid"] != self.ref_head:
                raise subprocess.CalledProcessError(1, ["gh", "api", "graphql"])
            return {"data": {"createCommitOnBranch": {"commit": {"oid": "bumped-head"}}}}
        raise AssertionError(f"Unexpected API request: {endpoint}")

    def test_stale_pr_head_does_not_supply_contents_or_commit_precondition(self):
        release.main()
        self.assertEqual(len(self.commits), 1)
        mutation = self.commits[0]
        self.assertEqual(mutation["expectedHeadOid"], "updated-release-head")
        self.assertEqual(mutation["branch"], {"repositoryNameWithOwner": self.repo, "branchName": self.branch})
        additions = mutation["fileChanges"]["additions"]
        self.assertEqual(len(additions), 1)
        self.assertEqual(additions[0]["path"], release.BUILD_FILE)
        self.assertEqual(base64.b64decode(additions[0]["contents"]).decode(),
                         self.original.replace("= 59", "= 60"))
        self.dispatch.assert_called_once_with(
            ["gh", "workflow", "run", "ci.yml", "--repo", self.repo, "--ref", self.branch], check=True)

    def test_already_bumped_branch_dispatches_ci_without_another_commit(self):
        self.original = self.original.replace("= 59", "= 60")
        release.main()
        self.assertEqual(self.commits, [])
        self.dispatch.assert_called_once()

    def test_concurrent_branch_change_still_rejects_the_write(self):
        self.move_branch_after_read = True
        with self.assertRaises(subprocess.CalledProcessError):
            release.main()
        self.assertEqual(len(self.commits), 1)
        self.assertEqual(self.commits[0]["expectedHeadOid"], "updated-release-head")
        self.dispatch.assert_not_called()

    def test_unexpected_pr_is_rejected_before_reading_or_writing_branch(self):
        self.pr["head"]["repo"]["full_name"] = "someone/fork"
        with self.assertRaisesRegex(ValueError, "Expected the open Release Please PR"):
            release.main()
        self.requests.assert_called_once_with(f"repos/{self.repo}/pulls/13")
        self.dispatch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
