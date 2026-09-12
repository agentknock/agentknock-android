import unittest

from release_notes import play_release_notes


CHANGELOG = """# Changelog

## [0.2.1](https://github.com/agentknock/agentknock-android/compare/v0.2.0...v0.2.1) (2026-09-11)

### Bug Fixes

* retry AI review network failures ([ed5815b](https://github.com/agentknock/agentknock-android/commit/ed5815b7751cad9e177b59887b341a40ebdadea1))
* retry temporary AI review failures within 100 seconds ([642f870](https://github.com/agentknock/agentknock-android/commit/642f87044252e37bdf8a79fa34892634137c252e))
* retry temporary AI review failures within 100 seconds ([#6](https://github.com/agentknock/agentknock-android/issues/6)) ([f19fc46](https://github.com/agentknock/agentknock-android/commit/f19fc46be5e3693d18dfafda4a0b31923cc8e05a))

## [0.2.0](https://github.com/agentknock/agentknock-android/releases/tag/v0.2.0) (2026-09-02)

### Features

* Initial release.
"""


class ReleaseNotesTests(unittest.TestCase):
    def notes(self, changelog=CHANGELOG, version="0.2.1"):
        return play_release_notes(changelog, version, "agentknock/agentknock-android")

    def test_real_release_please_notes_drop_references_and_duplicate_merge_entry(self):
        self.assertEqual(self.notes(),
                         "• retry AI review network failures\n"
                         "• retry temporary AI review failures within 100 seconds")

    def test_selects_only_the_exact_version(self):
        changelog = "## 0.2.10\n* Later change.\n\n" + CHANGELOG
        self.assertEqual(self.notes(changelog), self.notes())
        self.assertEqual(self.notes(changelog, "0.2.0"), "• Initial release.")

    def test_missing_duplicate_and_empty_version_sections_fail(self):
        for changelog in ("", "## 0.2.0\n* Older change.\n", "## 0.2.1\n",
                          "## 0.2.1\n* First.\n## 0.2.1\n* Second.\n"):
            with self.subTest(changelog=changelog), self.assertRaises(ValueError):
                self.notes(changelog)

    def test_keeps_breaking_changes_and_plain_text_from_formatted_entries(self):
        changelog = """## 0.2.1
### ⚠ BREAKING CHANGES
* **Pairing:** rotate `client_keys` before reconnecting.
### Bug Fixes
* Support [long requests](https://example.com/details) &amp; recovery.
  Keep the request open while retrying.
"""
        self.assertEqual(self.notes(changelog),
                         "• Breaking change: Pairing: rotate client_keys before reconnecting.\n"
                         "• Support long requests & recovery. Keep the request open while retrying.")

    def test_unexpected_changelog_format_fails_instead_of_silently_losing_notes(self):
        with self.assertRaisesRegex(ValueError, "Unexpected changelog"):
            self.notes("## 0.2.1\nUnstructured release notes.\n")

    def test_exactly_500_unicode_characters_are_retained(self):
        text = "é🚀" * 249
        notes = self.notes(f"## 0.2.1\n* {text}\n")
        self.assertEqual(notes, f"• {text}")
        self.assertEqual(len(notes), 500)

    def test_long_notes_keep_whole_bullets_and_link_to_the_matching_release(self):
        first, second, third = "First " + "a" * 170, "Second " + "b" * 170, "Third " + "c" * 170
        notes = self.notes(f"## 0.2.1\n* {first}\n* {second}\n* {third}\n")
        self.assertEqual(notes,
                         f"• {first}\n• {second}\n\nFull release notes: "
                         "https://github.com/agentknock/agentknock-android/releases/tag/v0.2.1")
        self.assertLessEqual(len(notes), 500)

    def test_single_oversized_entry_links_to_full_notes_without_cutting_a_sentence(self):
        notes = self.notes("## 0.2.1\n* " + "a" * 499 + "\n")
        self.assertEqual(notes, "Full release notes: "
                         "https://github.com/agentknock/agentknock-android/releases/tag/v0.2.1")

if __name__ == "__main__":
    unittest.main()
