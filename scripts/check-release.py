#!/usr/bin/env python3
"""Verify the unsigned, minified release bundle without Play or signing credentials."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    revision = os.environ["AGENTKNOCK_SOURCE_REVISION"]
    require(re.fullmatch(r"[0-9a-f]{12,40}", revision), "Expected a Git source revision")
    bundle = ROOT / "app/build/outputs/bundle/release/app-release.aab"
    report = ROOT / "app/build/reports/release-check"
    report.mkdir(parents=True, exist_ok=True)

    subprocess.run(["bundletool", "validate", f"--bundle={bundle}"], check=True)
    manifest_xml = subprocess.check_output(
        ["bundletool", "dump", "manifest", f"--bundle={bundle}", "--module=base"]
    )
    (report / "AndroidManifest.xml").write_bytes(manifest_xml)
    manifest = ET.fromstring(manifest_xml)
    application = manifest.find("application")
    require(application is not None, "Release bundle has no application")
    require(manifest.get("package") == "dev.agentknock", "Unexpected release application ID")
    for flag in ("debuggable", "testOnly"):
        require(application.get(ANDROID + flag) not in ("true", "1"), f"Release is {flag}")

    configuration = (ROOT / "app/build.gradle.kts").read_text()
    expected_code = re.search(r"^val agentknockVersionCode = (\d+)$", configuration, re.M)
    expected_name = re.search(r'^val agentknockVersionName = "([^"]+)"$', configuration, re.M)
    require(expected_code and expected_name, "Cannot read the declared release version")
    require(manifest.get(ANDROID + "versionCode") == expected_code[1], "Version code mismatch")
    require(manifest.get(ANDROID + "versionName") == expected_name[1], "Version name mismatch")

    mapping = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        require(
            not any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I) for name in names),
            "CI expects an unsigned release bundle",
        )
        embedded_mapping = archive.read("BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map")
        require(embedded_mapping and embedded_mapping == mapping.read_bytes(), "Missing or mismatched R8 mapping")
        dex_files = [name for name in names if re.fullmatch(r"base/dex/classes\d*\.dex", name)]
        require(dex_files, "Release bundle has no DEX files")
        require(
            any(revision.encode() in archive.read(name) for name in dex_files),
            "Release bundle is missing the source revision",
        )

    notes = (ROOT / "app/src/main/play/release-notes/en-US/internal.txt").read_text()
    require(notes.strip(), "Internal release notes are empty")
    for path in (ROOT / "app/src/main/play/subscriptions").glob("*.json"):
        json.loads(path.read_text())

    metadata = {
        "application_id": manifest.get("package"),
        "version_code": int(expected_code[1]),
        "version_name": expected_name[1],
        "source_revision": revision,
        "bundle_sha256": hashlib.sha256(bundle.read_bytes()).hexdigest(),
    }
    (report / "release.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(f"Verified unsigned release {metadata['version_name']} ({metadata['version_code']}) at {revision}")


if __name__ == "__main__":
    main()
