#!/usr/bin/env python3
"""Export an editable Android v1.5.7 source snapshot without touching tracked files."""
from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = (
    Path(sys.argv[1]).expanduser().resolve()
    if len(sys.argv) > 1
    else ROOT / "dist" / "ierikhon-v1.5.7-source"
)
JAVA = "app/src/main/java/com/sdrx/ierikhon/MainActivityV152.java"
GRADLE = "app/build.gradle"
ANDROID = [
    JAVA,
    GRADLE,
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/values/styles.xml",
    "build.gradle",
    "settings.gradle",
]
PATCHES = ["tools/v154_patch.py", "tools/soft_ui_patch.py", "tools/v157_patch.py"]
OTHER = [
    "pc-flasher/ierikhon_flasher.py",
    "pc-flasher/ierikhon_flasher_v104.py",
    "firmware/IERIKHON_v1.5.4/IERIKHON_v1.5.4.ino",
]


def copy_file(source: Path, target: Path, relative: str) -> None:
    file = source / relative
    if not file.is_file():
        raise FileNotFoundError("File missing: " + str(file))
    output = target / relative
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(file, output)


def main() -> None:
    if OUTPUT == ROOT or (OUTPUT.exists() and (not OUTPUT.is_dir() or any(OUTPUT.iterdir()))):
        raise ValueError("Choose an empty output directory, not the repository root")

    with tempfile.TemporaryDirectory(prefix="ierikhon-v157-") as name:
        work = Path(name)
        for relative in ANDROID + PATCHES + OTHER:
            copy_file(ROOT, work, relative)

        # v157_patch invokes soft_ui_patch, which itself invokes v154_patch.
        subprocess.run([sys.executable, "tools/v157_patch.py"], cwd=work, check=True)

        java = (work / JAVA).read_text(encoding="utf-8")
        if java.count('APP_VERSION = "1.5.7"') != 1:
            raise RuntimeError("Export failed: wrong Android application version")
        if java.count('FW_VERSION = "1.5.4"') != 1:
            raise RuntimeError("Export failed: wrong paired ESP32 version")

        gradle = (work / GRADLE).read_text(encoding="utf-8")
        original_version = "versionName '1.5.8'"
        if gradle.count(original_version) != 1:
            raise RuntimeError("Unknown Gradle version: refusing blind changes")
        (work / GRADLE).write_text(
            gradle.replace(original_version, "versionName '1.5.7'", 1),
            encoding="utf-8",
        )

        # Only the requested output directory is written; originals stay unchanged.
        for relative in ANDROID:
            copy_file(work, OUTPUT / "android", relative)
        for relative in OTHER:
            copy_file(work, OUTPUT, relative)

        digest = hashlib.sha256((work / JAVA).read_bytes()).hexdigest()

    (OUTPUT / "README.md").write_text(
        "# IERIKHON v1.5.7 editable sources\n\n"
        "Exported from the original Java base and v154/soft_ui/v157 patch chain.\n"
        "This is NOT proof of byte-identical rebuild of the archived APK.\n\n"
        "Java SHA-256: " + digest + "\n\n"
        "Android build: cd android && gradle :app:assembleDebug\n"
        "Do not use the legacy firmware on a real starter without new safety controls.\n",
        encoding="utf-8",
    )
    print("Exported source to:", OUTPUT)
    print("Final Java SHA-256:", digest)


if __name__ == "__main__":
    main()
