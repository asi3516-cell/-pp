#!/usr/bin/env python3
"""Sync the radio block of data/channels.json with data/radios.json.

data/channels.json is the file the server serves from /api/channels. It is
normally produced by build_channels.py, which re-fetches every TV playlist
from the network. When only the radios have changed, that is wasteful, so this
replaces just the kind="radio" entries (dropping the stations left in
data/radios-dead.json) and keeps the TV block untouched.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CHANNELS = ROOT / "data" / "channels.json"
RADIOS = ROOT / "data" / "radios.json"


def main() -> int:
    channels = json.loads(CHANNELS.read_text("utf-8"))
    radios = json.loads(RADIOS.read_text("utf-8"))

    tv = [c for c in channels if c.get("kind") != "radio"]
    block = []
    for s in radios:
        block.append({
            "name": s["name"],
            "url": s["url"],
            "logo": s.get("logo", ""),
            "group": s.get("group") or "Diğer",
            "tvgId": "",
            "source": "Radyo",
            "rank": s.get("votes", 0) or 0,
            "urls": [{"url": s["url"], "label": "Radyo (radio-browser)"}],
            "kind": "radio",
        })
    block.sort(key=lambda c: (-c["rank"], c["name"].casefold()))

    out = tv + block
    CHANNELS.write_text(json.dumps(out, ensure_ascii=False, indent=2), "utf-8")
    print(f"wrote {len(out)} entries ({len(tv)} TV, {len(block)} radio) -> {CHANNELS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
