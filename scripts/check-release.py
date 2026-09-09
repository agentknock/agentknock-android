#!/usr/bin/env python3
"""Verify unsigned, minified FOSS APKs and Play bundles without publishing credentials."""

import argparse
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("distribution", choices=["foss", "play"])
    distribution = parser.parse_args().distribution
    # Verify a revision when explicitly supplied. CI uses the build's unverified
    # default so unrelated commits can reuse Kotlin compilation and R8 outputs.
    revision = os.environ.get("AGENTKNOCK_SOURCE_REVISION")
    if revision is not None:
        require(re.fullmatch(r"[0-9a-f]{12,40}", revision), "Expected a Git source revision")
    outputs = ROOT / "app/build/outputs"

    if distribution == "play":
        artifact = outputs / "bundle/playRelease/app-play-release.aab"
        subprocess.run(["bundletool", "validate", f"--bundle={artifact}"], check=True)
        manifest_xml = subprocess.check_output(
            ["bundletool", "dump", "manifest", f"--bundle={artifact}", "--module=base"]
        )
    else:
        artifact = outputs / "apk/foss/release/app-foss-release-unsigned.apk"
        manifest_xml = subprocess.check_output(["apkanalyzer", "manifest", "print", str(artifact)])
        apksigner = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/apksigner"
        signature = subprocess.run(
            [str(apksigner), "verify", str(artifact)], capture_output=True, text=True
        )
        require(signature.returncode == 1, "CI expects an unsigned release APK")
    manifest = ET.fromstring(manifest_xml)
    application = manifest.find("application")
    require(application is not None, "Release artifact has no application")
    require(manifest.get("package") == "dev.agentknock", "Unexpected release application ID")
    for flag in ("debuggable", "testOnly"):
        require(application.get(ANDROID + flag) not in ("true", "1"), f"Release is {flag}")

    configuration = (ROOT / "app/build.gradle.kts").read_text()
    expected_code = re.search(r"^val agentknockVersionCode = (\d+)$", configuration, re.M)
    expected_name = re.search(r'^val agentknockVersionName = "([^"]+)"$', configuration, re.M)
    require(expected_code and expected_name, "Cannot read the declared release version")
    require(manifest.get(ANDROID + "versionCode") == expected_code[1], "Version code mismatch")
    require(manifest.get(ANDROID + "versionName") == expected_name[1], "Version name mismatch")

    mapping = outputs / "mapping" / f"{distribution}Release" / "mapping.txt"
    require(mapping.read_bytes(), "Missing R8 mapping")
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        require(
            not any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I) for name in names),
            "CI expects an unsigned release artifact",
        )
        if distribution == "play":
            embedded_mapping = archive.read("BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map")
            require(embedded_mapping == mapping.read_bytes(), "Mismatched embedded R8 mapping")
        dex_pattern = r"base/dex/classes\d*\.dex" if distribution == "play" else r"classes\d*\.dex"
        dex_files = [name for name in names if re.fullmatch(dex_pattern, name)]
        require(dex_files, "Release artifact has no DEX files")
        if revision is not None:
            require(
                any(revision.encode() in archive.read(name) for name in dex_files),
                "Release artifact is missing the source revision",
            )

    if distribution == "play":
        notes = (ROOT / "app/src/main/play/release-notes/en-US/internal.txt").read_text()
        require(notes.strip(), "Internal release notes are empty")
        for path in (ROOT / "app/src/main/play/subscriptions").glob("*.json"):
            json.loads(path.read_text())

    print(f"Verified unsigned {distribution} release {expected_name[1]} ({expected_code[1]})")


if __name__ == "__main__":
    main()
