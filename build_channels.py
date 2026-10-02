#!/usr/bin/env python3
"""Build data/channels.json from public iptv-org playlists plus demo streams.

Run:  python build_channels.py
The generated file is what the server returns from /api/channels so the
player works out of the box. Users can still add their own M3U playlists
from the UI at runtime.
"""

from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

from server import name_key, normalize_channel, parse_m3u

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "channels.json"

# Playlists pulled from the community-maintained iptv-org project.
# Only Turkish-language broadcasts are bundled: the `languages/tur` list holds
# every channel whose language is Turkish, and `countries/tr` adds the
# Turkey-licensed ones. Both are Turkish-only, so the player ships no foreign
# channels.
SOURCES = [
    ("https://iptv-org.github.io/iptv/languages/tur.m3u", "Türkçe"),
    ("https://iptv-org.github.io/iptv/countries/tr.m3u", "Türkiye"),
]

# iptv-org group titles are English; show friendly Turkish names in the UI.
GROUP_TR = {
    "General": "Genel",
    "Undefined": "Diğer",
    "News": "Haber",
    "Music": "Müzik",
    "Sports": "Spor",
    "Entertainment": "Eğlence",
    "Religious": "Dini",
    "Kids": "Çocuk",
    "Movies": "Film",
    "Series": "Dizi",
    "Documentary": "Belgesel",
    "Education": "Eğitim",
    "Animation": "Animasyon",
    "Outdoor": "Doğa",
    "Business": "İş",
    "Culture": "Kültür",
    "Lifestyle": "Yaşam",
    "Relax": "Rahatlama",
    "Travel": "Gezi",
    "Family": "Aile",
    "Comedy": "Komedi",
    "Science": "Bilim",
    "Auto": "Otomotiv",
    "Shop": "Alışveriş",
    "Weather": "Hava",
    "Legislative": "Meclis",
    "Classic": "Klasik",
}


# Channels from the canlitv listing that do not broadcast in Turkish. The
# list mixes in German local stations, English/Arabic news and Georgian /
# other-language feeds; those are dropped so the package stays Turkish-only.
NON_TURKISH = {
    "alvin channel", "cgtn documentary", "deutsche welle english",
    "dw tv europe", "franken tv", "huda tv", "india today", "imedi tv",
    "niederbayern tv", "noa4 hamburg", "oberpfalz tv", "offener kanal berlin",
    "press tv", "rfh", "rt (russia today)", "trt arapça", "trt world",
    "cbc tv", "az tv", "az star tv", "azad tv", "atv azad tv", "cbc sport",
}


def localize_group(raw: str) -> str:
    """Translate comma-separated iptv-org group titles to Turkish."""
    if not raw:
        return "Genel"
    parts = [p.strip() for p in raw.split(";") if p.strip()]
    seen = []
    for p in parts:
        seen.append(GROUP_TR.get(p, p))
    return ";".join(dict.fromkeys(seen))

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
    # Popularity ranks scraped from canlitv; used to put the most-watched
    # channels at the top even when only an iptv-org stream is available.
    ranks_file = ROOT / "data" / "canlitv_ranks.json"
    ranks_by_name = {}
    if ranks_file.exists():
        try:
            ranks_by_name = json.loads(ranks_file.read_text("utf-8"))
        except (OSError, ValueError):
            pass

    # Collect (channel, rank) so we can order by popularity at the end.
    # Demos sort last so real Turkish channels fill the top of the list.
    channels = [(normalize_channel(dict(d, source="Demo")), 300000) for d in DEMO]

    canlitv = ROOT / "data" / "canlitv.json"
    if canlitv.exists():
        try:
            added = 0
            for ch in json.loads(canlitv.read_text("utf-8")):
                if ch.get("name", "").casefold().strip() in NON_TURKISH:
                    continue
                channels.append((normalize_channel(ch), ch.get("rank", 100000)))
                added += 1
            print(f"+ {added} channels from Canlitv")
        except (OSError, ValueError) as exc:
            print(f"! could not read {canlitv}: {exc}")

    for url, source in SOURCES:
        try:
            text = fetch(url)
        except Exception as exc:  # noqa: BLE001
            print(f"! could not fetch {url}: {exc}")
            continue
        parsed = parse_m3u(text, source)
        for ch in parsed:
            ch["group"] = localize_group(ch.get("group", ""))
            ch["source"] = "Türkçe" if "Türkçe" in (ch.get("source") or "") else source
            # iptv-org streams are backups; if canlitv ranks this channel, use
            # that rank so popular channels still surface at the top.
            rank = ranks_by_name.get(name_key(ch["name"]), 200000)
            channels.append((ch, rank))
        print(f"+ {len(parsed)} channels from {source}")

    # Group the same channel (by normalized name) and keep every distinct URL as
    # a backup. The most-watched source comes first, so it is "url" and the rest
    # fill "urls" — the player falls back automatically if one stream fails.
    grouped: dict[str, dict] = {}
    order: list[str] = []
    for ch, rank in channels:
        url = ch.get("url")
        if not url:
            continue
        key = name_key(ch.get("name", ""))
        g = grouped.get(key)
        if g is None:
            g = {"name": ch["name"], "url": url, "urls": [url],
                 "logo": ch.get("logo", ""), "group": ch.get("group", "Genel"),
                 "tvgId": ch.get("tvgId", ""), "source": ch.get("source", ""),
                 "headers": ch.get("headers"), "rank": rank}
            grouped[key] = g
            order.append(key)
            continue
        if url not in g["urls"]:
            g["urls"].append(url)
        if rank < g["rank"]:
            # Newer, more-watched source becomes the primary entry.
            g["rank"] = rank
            g["name"] = ch["name"]
            g["source"] = ch.get("source", g["source"])
            g["urls"].remove(url)
            g["urls"].insert(0, url)
            g["url"] = url

    items = [grouped[k] for k in order]
    items.sort(key=lambda c: (c["rank"], c["name"].casefold()))
    for c in items:
        if not c.get("headers"):
            c.pop("headers", None)
        if len(c["urls"]) < 2:
            c.pop("urls")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(items, ensure_ascii=False, indent=2), "utf-8")
    multi = sum(1 for c in items if c.get("urls"))
    print(f"wrote {len(items)} channels ({multi} with backups) -> {OUT}")


if __name__ == "__main__":
    main()
