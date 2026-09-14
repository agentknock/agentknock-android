import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from dependency_licenses import archive_notices, generate, verify_archive


class DependencyLicenseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "licenses").mkdir()
        (self.root / "licenses/standard-texts.json").write_text("{}")
        self.catalogue = {
            "libraries": [],
            "licenses": {"MIT": {"name": "MIT License", "spdxId": "MIT"}},
        }

    def artifact(self, coordinate, entries):
        path = self.root / (coordinate.replace(":", "-") + ".aar")
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        identifier, version = coordinate.rsplit(":", 1)
        self.catalogue["libraries"].append({
            "uniqueId": identifier, "artifactVersion": version,
            "name": identifier, "licenses": ["MIT"],
        })
        return {"coordinate": coordinate, "path": str(path)}

    def test_filters_compile_only_dependencies_and_keeps_nested_original_notices(self):
        # AARs commonly put META-INF notices inside classes.jar. Losing them
        # when Android merges resources would discard the author's attribution.
        copyright_text = "Copyright 2026 Alice Example\nPermission is hereby granted.\n"
        notice_text = "This component includes work by Bob Example.\n"
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("META-INF/LICENSE.md", copyright_text)
            archive.writestr("META-INF/NOTICE", notice_text)
        runtime = self.artifact("example:runtime:2.0", {"classes.jar": nested.getvalue()})
        self.artifact("example:compile-only:1.0", {})
        self.catalogue["libraries"][0]["artifactVersion"] = "1.0"
        result = generate(self.catalogue, [runtime], [], self.root)
        self.assertEqual([library["uniqueId"] for library in result["libraries"]], ["example:runtime"])
        self.assertEqual(result["libraries"][0]["artifactVersion"], "2.0")
        contents = {license["content"] for license in result["licenses"].values()}
        self.assertIn(copyright_text, contents)
        self.assertIn(notice_text, contents)

    def test_google_offsets_use_bytes_and_duplicates_retain_all_parent_associations(self):
        # This is the metadata layout shipped by play-services-basement: a
        # component-name map with start/length byte ranges into the paired txt.
        # A multibyte prefix catches accidental decoding before slicing.
        prefix = "préface\n".encode()
        original = "Copyright © 2026 Example authors\nAll rights reserved.\n"
        body = original.encode()
        entries = {
            "LICENSE": "Example parent license\n",
            "third_party_licenses.json": json.dumps({
                "Example component": {"start": len(prefix), "length": len(body)},
            }),
            "third_party_licenses.txt": prefix + body + b"trailing data",
        }
        first = self.artifact("example:first:1", entries)
        second = self.artifact("example:second:2", entries)
        result = generate(self.catalogue, [first, second], [], self.root)
        bundled = [library for library in result["libraries"] if library["uniqueId"].startswith("bundled:")]
        self.assertEqual(len(bundled), 1)
        self.assertIn(first["coordinate"], bundled[0]["description"])
        self.assertIn(second["coordinate"], bundled[0]["description"])
        self.assertEqual(result["licenses"][bundled[0]["licenses"][0]]["content"], original)

    def test_all_artifacts_of_one_runtime_coordinate_retain_their_notices(self):
        # A resolved Gradle module can contribute more than one archive. Keeping
        # only the last path loses the others' original redistribution notices.
        first_text = "Copyright 2026 First component\nPermission is hereby granted.\n"
        second_text = "Includes Second component, copyright 2025 Example.\n"
        first = self.artifact("example:multi-artifact:1", {"LICENSE": first_text})
        second_path = self.root / "secondary.jar"
        with zipfile.ZipFile(second_path, "w") as archive:
            archive.writestr("META-INF/NOTICE", second_text)
        second = {"coordinate": first["coordinate"], "path": str(second_path)}
        result = generate(self.catalogue, [first, second], [], self.root)
        self.assertEqual(len(result["libraries"]), 1)
        contents = {license["content"] for license in result["licenses"].values()}
        self.assertIn(first_text, contents)
        self.assertIn(second_text, contents)

    def test_incomplete_google_bundles_fail_instead_of_silently_losing_notices(self):
        for entries in (
            {"third_party_licenses.json": "{}"},
            {"third_party_licenses.txt": "notice"},
            {"third_party_licenses.json": '{"component":{"start":2,"length":99}}',
             "third_party_licenses.txt": "notice"},
        ):
            with self.subTest(entries=entries):
                artifact = self.artifact("example:broken:1", entries)
                with self.assertRaises(ValueError):
                    archive_notices(artifact["path"])

    def test_missing_runtime_metadata_or_full_license_text_blocks_generation(self):
        artifact = self.artifact("example:missing:1", {})
        with self.assertRaisesRegex(ValueError, "Missing full license text"):
            generate(self.catalogue, [artifact], [], self.root)
        self.catalogue["libraries"] = []
        with self.assertRaisesRegex(ValueError, "missing from catalogue"):
            generate(self.catalogue, [artifact], [], self.root)

    def test_notice_only_does_not_stand_in_for_an_unknown_license(self):
        artifact = self.artifact("example:notice-only:1", {"NOTICE": "Copyright 2026 Example\n"})
        self.catalogue["libraries"][0]["licenses"] = []
        with self.assertRaisesRegex(ValueError, "No licenses"):
            generate(self.catalogue, [artifact], [], self.root)

    def test_supplement_is_version_bound_and_cannot_leak_into_another_flavor(self):
        artifact = self.artifact("example:play:1", {})
        original = "Copyright 2026 Example authors\nPermission is hereby granted.\n"
        (self.root / "license.txt").write_text(original)
        supplement = {
            "id": "embedded-component", "name": "Embedded component",
            "artifacts": ["example:play"], "auditedVersions": {"example:play": "1"},
            "license": "MIT License", "licenseIds": ["MIT"], "licenseFile": "license.txt",
        }
        result = generate(self.catalogue, [artifact], [supplement], self.root)
        for license in result["licenses"].values():
            self.assertEqual(license["content"], original)
        self.assertEqual(generate(self.catalogue, [], [supplement], self.root)["libraries"], [])
        artifact["coordinate"] = "example:play:2"
        with self.assertRaisesRegex(ValueError, "Re-audit"):
            generate(self.catalogue, [artifact], [supplement], self.root)

    def test_release_verification_checks_exact_apk_and_bundle_resource_bytes(self):
        # Android APK and App Bundle layouts place the same raw resource in
        # different directories; checking only a generated build file misses
        # shrinker or packaging regressions in the actual release payload.
        catalogue = self.root / "aboutlibraries.json"
        catalogue.write_bytes(b'{"libraries":[],"licenses":{}}\n')
        archive_path = self.root / "release.zip"
        # The optimized FOSS release uses an AAPT2-shortened path such as res/M7.json.
        for resource in ("res/raw/aboutlibraries.json", "base/res/raw/aboutlibraries.json",
                         "res/M7.json", "base/res/M7.json"):
            with self.subTest(resource=resource):
                with zipfile.ZipFile(archive_path, "w") as archive:
                    archive.writestr(resource, catalogue.read_bytes())
                verify_archive(catalogue, archive_path)
                with zipfile.ZipFile(archive_path, "w") as archive:
                    archive.writestr(resource, "stale notices")
                with self.assertRaisesRegex(ValueError, "differ"):
                    verify_archive(catalogue, archive_path)
        with zipfile.ZipFile(archive_path, "w"):
            pass
        with self.assertRaisesRegex(ValueError, "missing"):
            verify_archive(catalogue, archive_path)


if __name__ == "__main__":
    unittest.main()
