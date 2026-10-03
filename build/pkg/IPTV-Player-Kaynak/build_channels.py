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
import urllib.parse
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


# Human-readable tag for the origin of each stream alternative.
LABELS = {"list": "Liste (iptv-org)", "site": "Site (canlitv)"}


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

    # Collect (channel, rank, kind). `kind` is "site" for the canlitv.you streams
    # and "list" for the bundled iptv-org ones, so the UI can show both under
    # one channel: the iptv-org stream first, the site stream right below.
    collected: list[tuple[dict, int, str]] = [
        (normalize_channel(dict(d, source="Demo")), 300000, "list") for d in DEMO
    ]

    canlitv = ROOT / "data" / "canlitv.json"
    if canlitv.exists():
        try:
            added = 0
            for ch in json.loads(canlitv.read_text("utf-8")):
                if ch.get("name", "").casefold().strip() in NON_TURKISH:
                    continue
                collected.append((normalize_channel(ch), ch.get("rank", 100000), "site"))
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
            # If canlitv ranks this channel, borrow the rank so popular
            # channels still surface at the top.
            rank = ranks_by_name.get(name_key(ch["name"]), 200000)
            collected.append((ch, rank, "list"))
        print(f"+ {len(parsed)} channels from {source}")

    # Group the same channel (by normalized name). Every distinct stream is kept
    # as an alternative; the iptv-org ("list") stream is primary, the canlitv
    # ("site") streams follow. The first channel to appear names the entry.
    grouped: dict[str, dict] = {}
    order: list[str] = []
    for ch, rank, kind in collected:
        url = ch.get("url")
        if not url:
            continue
        key = name_key(ch.get("name", ""))
        g = grouped.get(key)
        if g is None:
            g = {"name": ch["name"], "url": url, "logo": ch.get("logo", ""),
                 "group": ch.get("group", "Genel"), "tvgId": ch.get("tvgId", ""),
                 "source": ch.get("source", ""), "headers": ch.get("headers"),
                 "rank": rank, "kind": kind, "alts": {}}
            grouped[key] = g
            order.append(key)
        else:
            # Keep a logo if this source has one and the first did not.
            if not g["logo"] and ch.get("logo"):
                g["logo"] = ch["logo"]

        label = LABELS[kind]
        for i, u in enumerate(ch.get("urls") or [url]):
            if u not in g["alts"]:
                # Primary stream (index 0 of the first source) comes first.
                g["alts"][u] = label
                if not g["url"] or (kind == "list" and g["kind"] == "site" and i == 0):
                    g["url"] = u
                    g["kind"] = kind
                    g["source"] = ch.get("source", g["source"])
        if rank < g["rank"]:
            g["rank"] = rank
            if g["name"].endswith(("(1440p)", "(1080p)", "(720p)", "(576p)")):
                g["name"] = ch["name"]

    items = []
    for k in order:
        g = grouped[k]
        # Primary first, then the remaining streams, each with a source label.
        g["urls"] = [{"url": u, "label": g["alts"][u]}
                     for u in sorted(g["alts"], key=lambda u: (u != g["url"],))]
        g.pop("alts")
        g.pop("kind")
        if not g.get("headers"):
            g.pop("headers", None)
        items.append(g)

    items.sort(key=lambda c: (c["rank"], c["name"].casefold()))
    fill_logos(items)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(items, ensure_ascii=False, indent=2), "utf-8")
    multi = sum(1 for c in items if len(c["urls"]) > 1)
    print(f"wrote {len(items)} channels ({multi} with alternatives) -> {OUT}")


# Curated aliases for channels whose names differ from the tv-logos filename.
LOGO_ALIAS = {
    "show-tv": "show-tr.png", "show-max": "show-max-tr.png",
    "trt-spor-yildiz": "trt-spor-yildiz-tr.png", "trt-cocuk": "trt-cocuk-tr.png",
    "trt-turk": "trt-turk-tr.png", "trt-kurdi": "trt-kurdi-tr.png",
    "trt-muzik": "trt-muzik-tr.png", "a-para": "a-para-tr.png",
    "power-turk": "powerturk-tr.png", "power-turk-slow": "powerturk-tr.png",
    "yaban-tv": "yaban-tr.png", "ekoturk": "ekoturk-tr.png",
    "flash-haber-tv": "flash-haber-tr.png", "kent-turk-tv": "kent-turk-tr.png",
    "sat7-turk": "sat7-turk-tr.png", "mavi-karadeniz-tv": "mavi-karadeniz-tr.png",
    "cem-tv": "cem-tv-tr.png", "tarim-tv": "tarim-tv-tr.png",
    "koy-tv": "koy-tv-tr.png", "ciftci-tv": "ciftci-tv-tr.png",
    "toprak-tv": "toprak-tv-tr.png", "kanal-avrupa": "kanal-avrupa-tr.png",
    "tivibu-spor": "tivibu-spor-tr.png",
}
LOGO_BASE = ("https://raw.githubusercontent.com/tv-logo/tv-logos/"
             "main/countries/turkey/")


def logo_slug(name: str) -> str:
    s = name.lower()
    for a, b in (("ı", "i"), ("ş", "s"), ("ğ", "g"), ("ü", "u"),
                 ("ö", "o"), ("ç", "c"), ("â", "a"), ("î", "i")):
        s = s.replace(a, b)
    s = re.sub(r"\((?:1080p|720p|576p|1440p|480p|hd|sd)\)", " ", s)
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


def fetch_logo_index() -> dict[str, str]:
    """Map slug -> filename for the tv-logos Turkey set. Best-effort: an empty
    index just means we keep the placeholder initials in the UI."""
    try:
        raw = fetch("https://api.github.com/repos/tv-logo/tv-logos/"
                    "contents/countries/turkey?per_page=200")
        files = [f["name"] for f in json.loads(raw) if f["name"].endswith(".png")]
    except Exception as exc:
        print(f"! logo list unavailable ({exc}); using aliases only")
        return {}
    index: dict[str, str] = {}
    for f in files:
        base = f[:-4]
        if base.endswith("-tr"):
            base = base[:-3]
        index.setdefault(logo_slug(base), f)
    return index


def fill_logos(items: list[dict]) -> None:
    """Give channels that came without a logo one from the tv-logos set, so
    popular Turkish channels no longer show a bare initial."""
    index = fetch_logo_index()
    filled = 0
    for ch in items:
        if ch.get("logo"):
            continue
        key = logo_slug(ch["name"])
        fname = LOGO_ALIAS.get(key) or index.get(key)
        if fname:
            ch["logo"] = LOGO_BASE + urllib.parse.quote(fname)
            filled += 1
    print(f"+ {filled} logos filled from tv-logos")


if __name__ == "__main__":
    main()
