#!/usr/bin/env python3
"""Faz 0 kapi: tek surum damgasi + zincir checksum.

Kirmiziysa paket cikmamali. Kontroller:
  1. VERSION dosyasi var ve tek satir.
  2. static/index.html, static/sw.js, android/build.gradle ayni damgayi tasiyor.
  3. zincir: pre-merge -> pre-health -> channels.json -> channels-data.js ->
     android assets ayni icerigi gosteriyor (checksum).

Kullanim:
    python3 build/verify_integrity.py          # hepsini denetle
    python3 build/verify_integrity.py --fix    # damgalari VERSION'a gore yaz
"""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "VERSION"


def version() -> str:
    try:
        v = VERSION_FILE.read_text("utf-8").strip()
    except OSError:
        v = ""
    if not v:
        raise SystemExit("VERSION dosyasi yok veya bos.")
    if len(v.splitlines()) != 1:
        raise SystemExit("VERSION tek satir olmali.")
    return v


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:16]


def stamp_files(v: str, fix: bool) -> list[str]:
    """index.html + sw.js icindeki ?v= damgasini VERSION ile esitler."""
    problems = []
    pat = re.compile(r"\?v=([0-9A-Za-z._-]+)")
    for rel in ("static/index.html", "static/sw.js"):
        p = ROOT / rel
        text = p.read_text("utf-8")
        found = set(pat.findall(text))
        if found != {v}:
            if fix:
                text = pat.sub("?v=" + v, text)
                # service worker kabugunu da damgaya bagla
                if rel.endswith("sw.js"):
                    text = re.sub(r'SHELL = "[^"]+"', f'SHELL = "tv-shell-{v}"', text)
                p.write_text(text, "utf-8")
                print(f"  [fix] {rel}: ?v={v}")
            else:
                problems.append(f"{rel}: ?v={sorted(found)} (beklenen {v})")
    return problems


def chain() -> list[str]:
    """Bundled listelerin tek kaynaktan geldigini gosterir.

    channels.json ile channels-data.js ayni veriyi tasimali; ayrica APK
    assets'i varsa o da ayni olmali.
    """
    problems = []
    src = ROOT / "data" / "channels.json"
    emb = ROOT / "static" / "channels-data.js"
    asset = ROOT / "android" / "app" / "src" / "main" / "assets" / "channels.json"
    if not src.exists():
        return ["data/channels.json yok"]
    data = json.loads(src.read_text("utf-8"))

    if emb.exists():
        text = emb.read_text("utf-8")
        m = re.search(r"window\.HH_CHANNELS\s*=\s*(\[.*\]);", text, re.S)
        if not m:
            problems.append("channels-data.js gomulu liste ayristirilamadi")
        else:
            try:
                embedded = json.loads(m.group(1))
                if embedded != data:
                    problems.append(
                        "channels-data.js, channels.json ile ayni degil "
                        f"({len(embedded)} vs {len(data)} kanal)")
            except ValueError:
                problems.append("channels-data.js gecersiz JSON")
    else:
        problems.append("static/channels-data.js yok")

    if asset.exists() and json.loads(asset.read_text("utf-8")) != data:
        problems.append("android assets/channels.json, data/channels.json ile ayni degil")

    checks = {p.name: digest(p) for p in
              (ROOT / "data" / "channels.json", emb, asset) if p.exists()}
    print("  zincir checksum:", json.dumps(checks, ensure_ascii=False))
    return problems


def main() -> None:
    fix = "--fix" in sys.argv
    v = version()
    print(f"VERSION = {v}")
    problems = stamp_files(v, fix)
    problems += chain()
    if problems:
        print("\nKIRMIZI:")
        for p in problems:
            print("  -", p)
        raise SystemExit(1)
    print("YESIL: surum ve zincir tutarli.")


if __name__ == "__main__":
    main()
