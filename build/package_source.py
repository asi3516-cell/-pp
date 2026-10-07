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
PKG = ROOT / "build" / "pkg"
OUT = PKG / "hh-Kaynak.zip"

SINGLE_FILES = [
    "server.py", "build_channels.py", "build_canlitv.py", "build_exe.py",
    "build_apk.py", "build_exe.bat", "sign_apk.bat", "README.md", "build/make.py",
    "build/tv_player.spec", "build/requirements-build.txt", "build/package_source.py",
    "build/verify_integrity.py", "build/refresh_health.py", "build/check_vectors.py",
    "make_embedded_channels.py", "apply_health.py", "VERSION", "run.sh", "run.bat",
    ".github/workflows/build.yml", "YAPILACAKLAR.txt", "YAPILANLAR.md", "AGENTS.md",
]
ANDROID_FILES = [
    "build.gradle", "settings.gradle", "gradle.properties",
    "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle", "app/proguard-rules.pro",
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

    # Also produce the 7z / rar variants some users prefer, when the tools are
    # present. Both mirror the zip exactly by extracting it first, so the three
    # archives never drift apart. Missing tools are not fatal.
    import shutil
    import subprocess
    import tempfile
    seven = shutil.which("7z") or shutil.which("7za")
    rar = Path("/workspace/tools/rar/rar")
    if not (seven or rar.exists() or _has_py7zr()):
        return
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(OUT) as zf:
            zf.extractall(tmp)
        if seven:
            subprocess.run([seven, "a", "-y", str(PKG / "hh-Kaynak.7z"), "hh"],
                           cwd=tmp, check=False, stdout=subprocess.DEVNULL)
        elif _has_py7zr():
            import py7zr
            with py7zr.SevenZipFile(PKG / "hh-Kaynak.7z", "w") as z:
                z.writeall(tmp, "hh")
        if rar.exists():
            subprocess.run([str(rar), "a", "-y", "-r", str(PKG / "hh-Kaynak.rar"), "hh"],
                           cwd=tmp, check=False, stdout=subprocess.DEVNULL)


def _has_py7zr() -> bool:
    try:
        import py7zr  # noqa: F401
        return True
    except ImportError:
        return False


if __name__ == "__main__":
    main()
