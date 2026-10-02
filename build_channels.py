#!/usr/bin/env python3
"""Build data/channels.json from public iptv-org playlists plus demo streams.

Run:  python build_channels.py
The generated file is what the server returns from /api/channels so the
player works out of the box. Users can still add their own M3U playlists
from the UI at runtime.
"""

from __future__ import annotations

import json
import urllib.request
from pathlib import Path

from server import normalize_channel, parse_m3u

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "channels.json"

# Country playlists pulled from the community-maintained iptv-org project.
SOURCES = [
    ("https://iptv-org.github.io/iptv/countries/tr.m3u", "Türkiye"),
]

# Stable, always-on public demo streams (used to verify the player itself).
DEMO = [
    {
        "name": "Demo · Big Buck Bunny",
        "url": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        "logo": "", "group": "Demo", "tvg-id": "", "source": "Demo",
    },
    {
        "name": "Demo · Apple BipBop",
        "url": "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        "logo": "", "group": "Demo", "tvg-id": "", "source": "Demo",
    },
    {
        "name": "Demo · Tears of Steel",
        "url": "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
        "logo": "", "group": "Demo", "tvg-id": "", "source": "Demo",
    },
    {
        "name": "Demo · Mux PTS Shift",
        "url": "https://test-streams.mux.dev/pts_shift/master.m3u8",
        "logo": "", "group": "Demo", "tvg-id": "", "source": "Demo",
    },
]

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", "replace")


def main() -> None:
    channels = [normalize_channel(dict(d, source="Demo")) for d in DEMO]
    for url, source in SOURCES:
        try:
            text = fetch(url)
        except Exception as exc:  # noqa: BLE001
            print(f"! could not fetch {url}: {exc}")
            continue
        parsed = parse_m3u(text, source)
        print(f"+ {len(parsed)} channels from {source}")
        channels.extend(parsed)

    # De-duplicate by name+url.
    seen = set()
    unique = []
    for ch in channels:
        key = (ch.get("name"), ch.get("url"))
        if key in seen:
            continue
        seen.add(key)
        unique.append(ch)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(unique, ensure_ascii=False, indent=2), "utf-8")
    print(f"wrote {len(unique)} channels -> {OUT}")


if __name__ == "__main__":
    main()
