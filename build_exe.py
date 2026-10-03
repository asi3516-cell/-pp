#!/usr/bin/env python3
"""Turn this player into a single, self-contained executable.

Just run it on the machine you want the program for:

    python build_exe.py

It produces one file in `dist/` with everything bundled inside (the server,
the web UI and the channel list) — no installation needed:

    Windows -> dist/hh.exe
    macOS   -> dist/hh          (and hh.app on request)
    Linux   -> dist/hh

PyInstaller cannot cross-compile, so a Windows .exe must be built on Windows
and a macOS build must be made on macOS. Run this script on each platform (or
use the GitHub Actions workflow in .github/workflows/build.yml for all three
at once). Requires Python 3.9+ and internet access the first time.
"""

from __future__ import annotations

import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DIST = ROOT / "dist"


def run(cmd: list[str]) -> None:
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd=ROOT)


def ensure_pyinstaller() -> None:
    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print("PyInstaller bulunamadı, kuruluyor...")
        run([sys.executable, "-m", "pip", "install", "--upgrade", "pyinstaller"])


def main() -> int:
    system = platform.system()
    print(f"Platform: {system} / Python {platform.python_version()}")

    ensure_pyinstaller()

    # Make sure the bundled channel list is current before packaging.
    if (ROOT / "build_channels.py").exists():
        try:
            run([sys.executable, "build_channels.py"])
        except subprocess.CalledProcessError:
            print("! kanal listesi güncellenemedi, mevcut data/channels.json kullanılacak")

    DIST.mkdir(exist_ok=True)
    spec = ROOT / "build" / "tv_player.spec"
    cmd = [sys.executable, "-m", "PyInstaller", str(spec), "--noconfirm", "--clean",
           "--distpath", str(DIST), "--workpath", str(ROOT / "build" / "work")]
    run(cmd)

    # Windows: make sure the extension is .exe; macOS: offer a double-clickable app.
    if system == "Windows":
        src = DIST / "hh"
        exe = DIST / "hh.exe"
        if src.exists() and not exe.exists():
            src.replace(exe)
        print(f"\nHazır: {exe}\nÇift tıklayarak çalıştırabilirsin.")
    elif system == "Darwin":
        app = DIST / "hh.app"
        note = f" (uygulama paketi: {app})" if app.exists() else ""
        print(f"\nHazır: {DIST / 'hh'}{note}\nÇift tıklayarak çalıştırabilirsin.")
    else:
        binary = DIST / "hh"
        if binary.exists():
            os.chmod(binary, 0o755)
        print(f"\nHazır: {binary}\nÇalıştır: chmod +x 'hh' && ./hh")

    # Optional: copy the result next to the user's Desktop for convenience.
    desktop = Path.home() / "Desktop"
    if desktop.is_dir() and DIST.is_dir():
        target_name = "hh.exe" if system == "Windows" else "hh"
        try:
            shutil.copy2(DIST / target_name, desktop / target_name)
            print(f"Masaüstüne kopyalandı: {desktop / target_name}")
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
