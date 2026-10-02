#!/usr/bin/env python3
"""Build a portable, single-file IPTV Player for the current platform.

    pip install -r build/requirements-build.txt
    python build/make.py

Result lands in dist/:
  * Windows  -> dist/IPTV-Player.exe
  * Linux    -> dist/IPTV-Player
  * macOS    -> dist/IPTV Player.app  and  dist/IPTV-Player

The produced file is self-contained: it embeds the web UI and the channel
list, and starts a local server, opening the browser automatically.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "build" / "iptv_player.spec"


def main() -> None:
    try:
        import PyInstaller  # noqa: F401
    except ImportError:
        print("PyInstaller is not installed. Run:")
        print("  pip install -r build/requirements-build.txt")
        raise SystemExit(1)

    cmd = [
        sys.executable, "-m", "PyInstaller",
        str(SPEC),
        "--noconfirm",
        "--clean",
        "--distpath", str(ROOT / "dist"),
        "--workpath", str(ROOT / "build" / "work"),
    ]
    print("Running:", " ".join(cmd))
    raise SystemExit(subprocess.call(cmd, cwd=str(ROOT)))


if __name__ == "__main__":
    main()
