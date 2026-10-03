#!/usr/bin/env python3
"""Build the source ZIP served at /download/source-zip.

    python build/package_source.py   ->  build/pkg/hh-Kaynak.zip

`data/store.json` is deliberately excluded: it holds the user's playlist
credentials (Xtream username/password). Never ship it.
"""

from __future__ import annotations

import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "build" / "pkg" / "hh-Kaynak.zip"

SINGLE_FILES = [
    "server.py", "build_channels.py", "build_canlitv.py", "build_exe.py",
    "build_apk.py", "build_exe.bat", "README.md", "build/make.py",
    "build/tv_player.spec", "build/requirements-build.txt", "build/package_source.py",
    ".github/workflows/build.yml", "YAPILACAKLAR.txt", "AGENTS.md",
]
ANDROID_FILES = [
    "build.gradle", "settings.gradle", "gradle.properties",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.properties",
]
TREE_DIRS = ["static", "data", "android/app/src/main"]
NEVER = {"store.json", "store.tmp"}


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zf:
        for name in SINGLE_FILES:
            f = ROOT / name
            if f.exists():
                zf.write(f, f"hh/{name}")
        for name in ANDROID_FILES:
            f = ROOT / "android" / name
            if f.exists():
                zf.write(f, f"hh/android/{name}")
        for folder in TREE_DIRS:
            for f in sorted((ROOT / folder).rglob("*")):
                if not f.is_file() or f.name.endswith((".class", ".dex")):
                    continue
                if folder.endswith("src/main") and "assets" in f.parts:
                    continue
                if f.name in NEVER or "store.tmp" in f.name:
                    continue
                zf.write(f, f"hh/{f.relative_to(ROOT)}")
        for jar in (ROOT / "android" / "libs").glob("*.jar"):
            zf.write(jar, f"hh/{jar.relative_to(ROOT)}")
    print(f"Hazır: {OUT}  ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
