from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from version import (SUBJECT, affects_app, app_changes, bumped, check, check_version,
                     is_version_release, main, version_code)


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
                           "val agentknockVersionCode = 48", "app/src/main/App.kt\0"]
        with self.assertRaisesRegex(ValueError, "must exceed base"):
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
                           "val agentknockVersionCode = 48", "version.txt\0", "0.3.0", '{".": "0.2.0"}']
        with self.assertRaisesRegex(ValueError, "disagree"):
            check("master", "head")

    def test_paths_outside_the_shipped_app_do_not_require_a_bump(self):
        for path in ("AGENTS.md", "README.md", "docs/releases.md", ".gitignore",
                     "scripts/publish-play.py", "scripts/version.py", "scripts/test_version.py",
                     "app/src/test/AppTest.kt", "app/src/testPlay/BillingTest.kt",
                     "app/src/androidTest/StartupTest.kt", "app/src/androidTestFoss/PushTest.kt",
                     "app/src/screenshotTest/Preview.kt", "app/schemas/database/3.json",
                     "app/src/main/play/listings/en-GB/title.txt",
                     "app/src/main/play/listings/en-GB/graphics/phone-screenshots/1.png",
                     "app/src/main/play/subscriptions/product.json", "publish-internal",
                     ".github/workflows/ci.yml", ".github/workflows/publish-play.yml",
                     ".github/actions/setup-device/action.yml", "release-please-config.json"):
            with self.subTest(path=path):
                self.assertFalse(affects_app(path))

    def test_app_inputs_and_unfamiliar_paths_require_a_bump(self):
        for path in ("app/src/main/App.kt", "app/src/play/Billing.kt", "app/src/foss/Push.kt",
                     "app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml",
                     "app/src/main/assets/README.md", "app/src/play/google-services.json",
                     "app/src/release/AndroidManifest.xml", "app/src/newFlavor/App.kt",
                     "app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                     "gradle.properties", "gradle/libs.versions.toml", "gradlew", "gradlew.bat",
                     "gradle/wrapper/gradle-wrapper.jar", "app/proguard-rules.pro",
                     "version.txt", "flake.nix", "flake.lock", ".gitattributes",
                     ".github/actions/setup-build/action.yml", ".github/workflows/ci-distribution.yml",
                     "signing/app-signing-certificate.pem", "signing/play-upload-certificate.pem",
                     "buildSrc/src/main/Generator.kt", "new-module/build.gradle.kts", "unknown-file"):
            with self.subTest(path=path):
                self.assertTrue(affects_app(path))


class VersionRepositoryTests(unittest.TestCase):
    """Exercise diffs and local bumping against real Git trees, including moves."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        root_patch = patch("version.ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.git("init", "-q", "-b", "master")
        self.git("config", "user.name", "Version tests")
        self.git("config", "user.email", "tests@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        # Do not leave background Git maintenance racing temporary-directory cleanup.
        self.git("config", "gc.autoDetach", "false")
        self.git("config", "maintenance.autoDetach", "false")
        self.write("app/build.gradle.kts", "val agentknockVersionCode = 48\n")
        self.write("version.txt", "0.3.0\n")
        self.write(".release-please-manifest.json", '{".": "0.3.0"}\n')
        self.write("app/src/main/App.kt", "// initial app\n")
        self.write("README.md", "initial documentation\n")
        self.write(".gitignore", "/agentknock-*.json\n")
        self.commit("chore: initial tree")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True).strip()

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def commit(self, subject):
        self.git("add", ".")
        self.git("commit", "-qm", subject)

    def command(self, command, **environment):
        with patch("sys.argv", ["version.py", command, "--base", self.base]), \
                patch.dict(os.environ, environment), redirect_stdout(io.StringIO()):
            main()

    def test_non_app_merge_passes_checks_without_requesting_publication(self):
        self.write("README.md", "updated documentation\n")
        self.write("app/src/main/play/listings/en-GB/title.txt", "New listing title\n")
        self.write("scripts/publish-play.py", "# publishing change\n")
        self.commit("ci: update listing tooling")
        self.command("check")
        output = self.root / "outputs"
        self.command("release", GITHUB_OUTPUT=str(output))
        self.assertEqual(output.read_text(), "publish-release=false\n")

    def test_app_changes_require_bump_and_then_publish(self):
        self.write("app/src/main/App.kt", "// changed app\n")
        self.commit("fix: change app")
        with self.assertRaisesRegex(ValueError, "must exceed base"):
            self.command("check")
        output = self.root / "outputs"
        with self.assertRaisesRegex(ValueError, "must exceed base"):
            self.command("release", GITHUB_OUTPUT=str(output))
        self.assertFalse(output.exists())
        self.command("bump")
        self.commit("chore: bump version code")
        self.command("check")
        self.command("release", GITHUB_OUTPUT=str(output))
        self.assertEqual(output.read_text(), "publish-release=true\n")

    def test_code_cannot_decrease(self):
        self.write("app/build.gradle.kts", "val agentknockVersionCode = 47\n")
        self.commit("chore: decrease code")
        with self.assertRaisesRegex(ValueError, "must not decrease"):
            self.command("check")

    def test_semantic_release_requires_new_code(self):
        self.write("version.txt", "0.3.1\n")
        self.write(".release-please-manifest.json", '{".": "0.3.1"}\n')
        self.commit("chore: release 0.3.1")
        with self.assertRaisesRegex(ValueError, "must exceed base"):
            self.command("check")
        self.command("bump")
        self.commit("chore: bump version code")
        self.command("check")
        self.assertTrue(check_version(self.base, "HEAD"))

    def test_deliberate_code_bump_requests_publication(self):
        self.write("app/build.gradle.kts", "val agentknockVersionCode = 49\n")
        self.commit("chore: request fresh app build")
        self.assertTrue(check_version(self.base, "HEAD"))

    def test_moving_production_source_to_tests_still_requires_bump(self):
        self.git("mv", "app/src/main/App.kt", "app/src/main/renamed.kt")
        (self.root / "app/src/test").mkdir()
        self.git("mv", "app/src/main/renamed.kt", "app/src/test/App.kt")
        self.commit("test: move source to tests")
        self.assertEqual(app_changes(self.base, "HEAD"), ["app/src/main/App.kt"])
        with self.assertRaisesRegex(ValueError, "must exceed base"):
            self.command("check")

    def test_bump_leaves_non_app_edits_and_ignored_offer_alone(self):
        self.write("README.md", "new docs\n")
        self.write("app/src/test/NewTest.kt", "// untracked test\n")
        self.write("agentknock-free-trial-14-days.json", "preserved offer\n")
        self.command("bump")
        self.assertEqual(version_code((self.root / "app/build.gradle.kts").read_text()), 48)
        self.assertEqual((self.root / "agentknock-free-trial-14-days.json").read_text(), "preserved offer\n")

    def test_bump_includes_unstaged_staged_and_untracked_app_inputs(self):
        for stage in ("unstaged", "staged", "untracked"):
            with self.subTest(stage=stage):
                self.git("reset", "--hard", self.base)
                path = "app/src/main/New.kt" if stage == "untracked" else "app/src/main/App.kt"
                self.write(path, "// changed input\n")
                if stage == "staged":
                    self.git("add", path)
                self.command("bump")
                self.command("bump")
                self.assertEqual(version_code((self.root / "app/build.gradle.kts").read_text()), 49)

    def test_publish_decision_uses_whole_push_not_only_last_commit(self):
        self.write("app/src/main/App.kt", "// changed app\n")
        self.command("bump")
        self.commit("fix: change app")
        self.write("README.md", "later documentation\n")
        self.commit("docs: describe app")
        self.assertFalse(check_version("HEAD^", "HEAD"))
        self.assertTrue(check_version(self.base, "HEAD"))


if __name__ == "__main__":
    unittest.main()
