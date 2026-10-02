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
    channels = [normalize_channel(dict(d, source="Demo")) for d in DEMO]

    # Merge the higher-quality canlitv streams first so their URLs win when
    # de-duplicating against the iptv-org entries of the same channel.
    canlitv = ROOT / "data" / "canlitv.json"
    if canlitv.exists():
        try:
            for ch in json.loads(canlitv.read_text("utf-8")):
                if ch.get("name", "").casefold().strip() in NON_TURKISH:
                    continue
                channels.append(normalize_channel(ch))
            print(f"+ {len(channels) - len(DEMO)} channels from Canlitv")
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
            # Single-language entries: prefer the Turkish language label.
            ch["source"] = "Türkçe" if "Türkçe" in (ch.get("source") or "") else source
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
