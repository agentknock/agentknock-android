import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location("check_release", Path(__file__).with_name("check-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PackagedLicenseTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

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
                release.verify_dependency_licenses(catalogue, archive_path)
                with zipfile.ZipFile(archive_path, "w") as archive:
                    archive.writestr(resource, "stale notices")
                with self.assertRaisesRegex(ValueError, "differ"):
                    release.verify_dependency_licenses(catalogue, archive_path)
        with zipfile.ZipFile(archive_path, "w"):
            pass
        with self.assertRaisesRegex(ValueError, "missing"):
            release.verify_dependency_licenses(catalogue, archive_path)


if __name__ == "__main__":
    unittest.main()
