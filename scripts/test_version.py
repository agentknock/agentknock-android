import unittest
from unittest.mock import patch

from version import SUBJECT, bumped, check, is_version_release, version_code


class VersionTests(unittest.TestCase):
    @patch("version.Path.read_text", return_value="0.3.0\n")
    @patch("version.git")
    def test_semantic_release_compares_version_with_first_parent(self, git, read):
        git.return_value = "0.2.0"
        self.assertTrue(is_version_release())
        git.assert_called_with("show", "HEAD^1:version.txt")
        git.return_value = "0.3.0"
        self.assertFalse(is_version_release())

    def test_bump_is_idempotent_and_preserves_unrelated_content(self):
        source = 'plugins {}\nval agentknockVersionCode = 47\nval other = "keep"\n'
        updated = bumped(source, 47)
        self.assertEqual(updated, source.replace("= 47", "= 48"))
        self.assertEqual(bumped(updated, 47), updated)
        self.assertEqual(version_code(bumped(updated, 49)), 50)

    def test_preserves_deliberately_larger_code(self):
        self.assertEqual(version_code(bumped("val agentknockVersionCode = 99", 47)), 99)

    def test_rejects_missing_duplicate_and_exhausted_codes(self):
        for source in ("", "val agentknockVersionCode = 0", "val agentknockVersionCode = 2100000001",
                       "val agentknockVersionCode = 1\nval agentknockVersionCode = 2"):
            with self.subTest(source=source), self.assertRaises(ValueError):
                version_code(source)
        with self.assertRaises(ValueError):
            bumped("val agentknockVersionCode = 2100000000", 2100000000)

    def test_conventional_subjects_include_release_please_and_breaking_changes(self):
        for subject in ("feat: add pairing", "fix(push)!: change delivery", "chore(master): release 1.2.3",
                        "ci: attest releases", "revert: remove broken migration"):
            self.assertIsNotNone(SUBJECT.fullmatch(subject), subject)
        for subject in ("Update version", "Merge branch master", "feat:no space", "fix: ", "misc: change"):
            self.assertIsNone(SUBJECT.fullmatch(subject), subject)

    @patch("version.subprocess.run")
    @patch("version.git")
    def test_check_rejects_counter_reused_by_a_concurrent_pr(self, git, run):
        git.side_effect = ["", "abc", "fix: correct delivery", "val agentknockVersionCode = 48",
                           "val agentknockVersionCode = 48"]
        with self.assertRaisesRegex(ValueError, "must exceed master"):
            check("master", "head")

    @patch("version.subprocess.run")
    @patch("version.git")
    def test_check_rejects_feature_branch_merges(self, git, run):
        git.return_value = "merge-sha"
        with self.assertRaisesRegex(ValueError, "Rebase"):
            check("master", "head")

    @patch("version.subprocess.run")
    @patch("version.git")
    def test_check_rejects_disagreement_with_release_manifest(self, git, run):
        git.side_effect = ["", "abc", "chore: release", "val agentknockVersionCode = 47",
                           "val agentknockVersionCode = 48", "0.3.0", '{".": "0.2.0"}']
        with self.assertRaisesRegex(ValueError, "disagree"):
            check("master", "head")


if __name__ == "__main__":
    unittest.main()
