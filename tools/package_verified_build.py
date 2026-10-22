#!/usr/bin/env python3
"""Package an Android CI-built NetKit APK only after explicit verification.

This does not validate Android usability. The CI workflow must separately run
assembleDebug, lintDebug, apksigner and aapt before invoking this script.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
OUT = ROOT / "dist"
APK_NAME = "NetKit_v0.6.0-alpha-debug.apk"
SRC_NAME = "NetKit_Android_Source_v0.6-build1.zip"
EXCLUDE = {".git", ".gradle", ".idea", "__pycache__", "build", "dist"}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()


def main():
    if not APK.is_file() or APK.stat().st_size < 100_000:
        raise SystemExit(f"No real debug APK found: {APK}")
    if not (ROOT / "gradle/wrapper/gradle-wrapper.jar").is_file():
        raise SystemExit("Gradle wrapper JAR not installed: refusing to package incomplete source")
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir()
    shutil.copy2(APK, OUT / APK_NAME)
    with zipfile.ZipFile(OUT / SRC_NAME, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(ROOT.rglob("*")):
            if not p.is_file() or any(part in EXCLUDE for part in p.relative_to(ROOT).parts):
                continue
            if p.suffix in {".apk", ".aab", ".keystore", ".jks"}:
                continue
            z.write(p, arcname=str(Path("NetKit_Android_v0.6") / p.relative_to(ROOT)))
    checksums = "".join(f"{digest(OUT / name)}  {name}\n" for name in (APK_NAME, SRC_NAME))
    (OUT / "SHA256SUMS.txt").write_text(checksums, encoding="utf8")
    print("Verified-build packaging inputs present. SHA-256:\n" + checksums)
    print("Packaging by itself does not attest to device testing.")


if __name__ == "__main__":
    main()
