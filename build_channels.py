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

# Arabic-language feeds that slip in through the Turkey list. Only Turkish
# broadcasts should remain, so these are dropped by name.
ARABIC = {
    "trt arabi", "al-rafidain tv", "al-zahra tv turkic", "almahriah tv",
    "elsharrq tv", "elshark tv", "imam hussein tv 5", "mekameleen tv",
    "qaf tv", "al-rafidain", "al-zahra", "karbala tv", "ahlulbayt tv",
    "al kawthar tv", "alalam tv", "alalam news", "al mayadeen",
}


def is_arabic(name: str) -> bool:
    """True for Arabic-language feeds: Arabic script, or a known name."""
    if any("\u0600" <= c <= "\u06ff" for c in name):
        return True
    return name.casefold().strip() in ARABIC


# TKGS-style ordering: channels are grouped the way a Turkish TV operator
# lays them out, so the list reads national → news → sport → documentary →
# kids → cinema/music → local/tematic → religious → radio.
TKGS_BLOCK = {
    "Haber": 20, "Spor": 50,
    "Belgesel": 70, "Kültür": 70, "Doğa": 70, "Yaşam": 70, "Gezi": 70,
    "Çocuk": 90, "Animasyon": 90, "Eğitim": 90, "Aile": 90,
    "Film": 110, "Dizi": 110, "Müzik": 110, "Eğlence": 110, "Komedi": 110,
    "Dini": 150, "Radyo": 400,
}
TKGS_DEFAULT = 150  # local, thematic and everything unclassified

# The national main channels that open the list (TKGS 1–20).
TKGS_NATIONAL = [
    "trt 1", "atv", "show tv", "kanal d", "star tv", "now tv", "tv8",
    "kanal 7", "beyaz tv", "trt 2", "cnbc e", "tv 100", "360 tv",
]


def clean_name(name: str) -> str:
    """Lowercase a channel name and drop the resolution / source markers, so
    "ATV", "ATV HD" and "ATV (1080p)" all match the same entry."""
    s = name.casefold()
    s = re.sub(r"\((?:1080p|720p|576p|1440p|480p|hd|sd|fhd|uhd)\)", " ", s)
    s = re.sub(r"\[[^\]]*\]", " ", s)
    s = re.sub(r"\b(?:hd|sd|fhd|uhd)\b", " ", s)
    return re.sub(r"\s+", " ", s).strip()


NATIONAL_GROUP = "Ulusal"
NATIONAL_TOP = 24


def mark_national(tv: list, ranks_by_name: dict) -> list:
    """Give the 24 most-watched channels the "Ulusal" group.

    The ranking comes from canlitv view counts; only channels already present
    in the list are marked, so no station is invented. Marked channels are
    returned in ranking order so the caller can also put them up front."""
    ranked = sorted(ranks_by_name.items(), key=lambda kv: kv[1])
    picked = []
    for raw, _rank in ranked:
        for ch in tv:
            if ch.get("national"):
                continue
            if name_key(ch["name"]) == name_key(raw):
                ch["group"] = NATIONAL_GROUP
                ch["national"] = len(picked) + 1
                picked.append(ch)
                break
        if len(picked) >= NATIONAL_TOP:
            break
    return picked


def tkgs_key(ch: dict) -> tuple:
    """Sort key implementing the TKGS block order."""
    name = ch.get("name", "")
    clean = clean_name(name)
    if clean in TKGS_NATIONAL:
        return (1, TKGS_NATIONAL.index(clean), ch.get("rank", 0), name)
    group = (ch.get("group") or "Genel").split(";")[0].strip()
    return (TKGS_BLOCK.get(group, TKGS_DEFAULT), 999,
            ch.get("rank", 0), name)



# Human-readable tag for the origin of each stream alternative.
LABELS = {"list": "Liste (iptv-org)", "site": "Site (canlitv)",
          "radio": "Radyo (radio-browser)"}


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
            # Turkish-only: skip Arabic feeds, known foreign names and the
            # radio entries (radios are bundled separately with popularity).
            if ch.get("kind") == "radio":
                continue
            if is_arabic(ch.get("name", "")):
                continue
            if ch.get("name", "").casefold().strip() in NON_TURKISH:
                continue
            ch["group"] = localize_group(ch.get("group", ""))
            ch["source"] = "Türkçe" if "Türkçe" in (ch.get("source") or "") else source
            # If canlitv ranks this channel, borrow the rank so popular
            # channels still surface at the top.
            rank = ranks_by_name.get(name_key(ch["name"]), 200000)
            collected.append((ch, rank, "list"))
        print(f"+ {len(parsed)} channels from {source}")

    # Turkish radio stations from the open radio-browser database, ordered by
    # community votes. They carry kind="radio" so the UI can show them under
    # their own "Radyo" heading.
    radios = ROOT / "data" / "radios.json"
    if radios.exists():
        try:
            items = json.loads(radios.read_text("utf-8"))
            for ch in items:
                if is_arabic(ch.get("name", "")):
                    continue
                collected.append((normalize_channel(ch), ch.get("votes", 100000), "radio"))
            print(f"+ {len(items)} radio stations")
        except (OSError, ValueError) as exc:
            print(f"! could not read {radios}: {exc}")

    # Group the same channel (by normalized name). Every distinct stream is kept
    # as an alternative; the iptv-org ("list") stream is primary, the canlitv
    # ("site") streams follow. The first channel to appear names the entry.
    grouped: dict[str, dict] = {}
    order: list[str] = []
    for ch, rank, kind in collected:
        url = ch.get("url")
        if not url:
            continue
        # Radios are their own kind, so they must never merge with a TV
        # channel of the same name; keep them in a separate namespace.
        key = ("radio:" if kind == "radio" else "tv:") + name_key(ch.get("name", ""))
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
        # Keep the kind: "radio" tells the UI to list it under its own heading
        # and to play it as audio.
        if g.pop("kind") == "radio":
            g["kind"] = "radio"
            # Radios keep the Turkish category assigned in verify_radios.py
            # (Pop, Haber, Oyun Havası, …) instead of collapsing to "Radyo".
            if g.get("group") in ("Radyo", "", None):
                g["group"] = "Diğer"
        if not g.get("headers"):
            g.pop("headers", None)
        items.append(g)

    # TV first in TKGS order, then the radio block by popularity.
    tv = [c for c in items if c.get("kind") != "radio"]
    radio = [c for c in items if c.get("kind") == "radio"]
    tv.sort(key=tkgs_key)
    national = mark_national(tv, ranks_by_name)
    # The most-watched channels form the "Ulusal" group and lead the list.
    tv.sort(key=lambda c: (0, c["national"]) if c.get("national")
            else (1,) + tkgs_key(c))
    print(f"+ {len(national)} channels marked as Ulusal (en cok izlenen)")
    radio.sort(key=lambda c: (-c.get("rank", 0), c["name"].casefold()))
    items = tv + radio
    fill_logos(items)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(items, ensure_ascii=False, indent=2), "utf-8")
    multi = sum(1 for c in items if len(c["urls"]) > 1)
    print(f"wrote {len(items)} entries ({len(tv)} TV, {len(radio)} radio, "
          f"{multi} with alternatives) -> {OUT}")


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
        # Radios carry their own favicon; don't overwrite it with a TV logo.
        if ch.get("logo") or ch.get("kind") == "radio":
            continue
        key = logo_slug(ch["name"])
        fname = LOGO_ALIAS.get(key) or index.get(key)
        if fname:
            ch["logo"] = LOGO_BASE + urllib.parse.quote(fname)
            filled += 1
    print(f"+ {filled} logos filled from tv-logos")


if __name__ == "__main__":
    main()
