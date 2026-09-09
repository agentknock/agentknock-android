#!/usr/bin/env python3
"""Render and export the eight phone screenshots used by the Play listing."""
from pathlib import Path
import subprocess

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
IMAGES = ROOT / "app/build/reports/ui-previews/images"
DESTINATION = ROOT / "app/src/main/play/listings/en-GB/graphics/phone-screenshots"
SCREENS = (
    "invocation",
    "secrets",
    "git-sign",
    "ssh-authentication",
    "secret-detail",
    "client-detail",
    "pairing",
    "audit",
)

subprocess.run([str(ROOT / "preview-ui"), *SCREENS, "--variant", "play-store"], cwd=ROOT, check=True)
exports = {}
for number, screen in enumerate(SCREENS, 1):
    with Image.open(IMAGES / f"{screen}-play-store.png") as image:
        if image.size != (1080, 1920) or image.mode != "RGBA" or image.getextrema()[3] != (255, 255):
            raise ValueError(f"Expected an opaque 1080 × 1920 RGBA render: {screen}")
        # Play requires 24-bit PNGs. Remove the unused alpha channel; preserve every RGB pixel.
        exports[f"{number:02}-{screen}.png"] = image.convert("RGB")

for name, image in exports.items():
    image.save(DESTINATION / name, optimize=True)
for previous in DESTINATION.glob("*.png"):
    if previous.name not in exports:
        previous.unlink()
print(f"Exported {len(exports)} screenshots to {DESTINATION.relative_to(ROOT)}")
