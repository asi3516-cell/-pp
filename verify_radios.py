#!/usr/bin/env python3
"""Radyo listesini dogrular ve sirali hale getirir.

Her istasyonun yayin adresi gercekten ses donduruyor mu diye kontrol eder,
calismayanlari ayirir ve kalanlari oy (popularity) sayisina gore buyukten
kucuge siralayip data/radios.json dosyasini yeniden yazar. Calismayanlarin
listesi data/radios-dead.json dosyasina yazilir ki adresleri elle duzeltilebilsin.
"""
from __future__ import annotations

import json
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "data" / "radios.json"
DEAD = ROOT / "data" / "radios-dead.json"

# Stations that play oyun havası / wedding-dance music. Their names rarely say
# so, so they are listed explicitly and pinned to the "Oyun Havası" category.
OYUN_HAVASI_NAMES = {
    "radyo seymen", "radyo banko", "park fm", "can radyo", "aşk fm",
    "radyo megasite", "radyo ankara havaları", "ankara havalari",
    "radyo 7 ankara havaları", "radyo 06", "best kına", "kral ankara",
    "radyo oyun havası", "ankara rüzgarı fm", "dost fm ankara havaları",
    "deva fm oyun havası",
}

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE
TIMEOUT = 10
PLS_RE = re.compile(r"\.pls(\?|$)", re.IGNORECASE)
M3U8_RE = re.compile(r"\.m3u8(\?|$)", re.IGNORECASE)


def unwrap(url: str, headers: dict) -> str:
    """Follow a .pls / plain .m3u to the real stream URL it points at.

    HLS (.m3u8) is a stream in its own right, so it is never unwrapped."""
    if not (PLS_RE.search(url) or url.lower().split("?")[0].endswith(".m3u")):
        return url
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=TIMEOUT, context=CTX) as r:
            raw = r.read(8192)
    except (urllib.error.URLError, OSError):
        return url
    for line in raw.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"(?i)file\d*\s*=\s*(.+)$", line)
        return urllib.parse.urljoin(url, m.group(1).strip() if m else line)
    return url


def probe(station: dict) -> tuple[dict, str, int]:
    """Return (station, status, bytes). status: ok | dead | error."""
    url = station.get("url", "")
    if not url:
        return station, "dead", 0
    headers = {"User-Agent": UA, "Accept": "*/*"}
    if station.get("headers", {}).get("User-Agent"):
        headers["User-Agent"] = station["headers"]["User-Agent"]
    target = unwrap(url, headers)

    last = "error"
    for _ in range(2):
        try:
            req = urllib.request.Request(target, headers=headers)
            with urllib.request.urlopen(req, timeout=TIMEOUT, context=CTX) as r:
                chunk = r.read(4096)
            if not chunk:
                last = "dead"
                continue
            if chunk[:7].upper() == b"#EXTM3U" or chunk[:3] == b"ID3" or chunk[0] == 0xFF:
                return station, "ok", len(chunk)
            head = chunk[:400].lower()
            if b"<html" in head or b"not found" in head or b"404" in head:
                last = "dead"
                continue
            return station, "ok", len(chunk)
        except Exception:
            last = "error"
    return station, last, 0


def categorize(station: dict) -> str:
    """Assign a Turkish category from the station name and its tags.

    radio-browser has no reliable Turkish genre field, so the name and the
    free-form tags are matched against keyword lists. The first category that
    matches wins; stations that match nothing land in "Diğer"."""
    name = (station.get("name") or "").casefold()
    tags = " ".join(t.casefold() for t in (station.get("tags") or []))
    hay = name + " " + tags
    # Stations the keyword rules below would misplace. These are known oyun
    # havası / wedding-dance broadcasters, so they are pinned by exact name.
    if name.strip() in OYUN_HAVASI_NAMES:
        return "Oyun Havası"
    rules = [
        # Oyun havası sits first: wedding/dance-hall music is its own popular
        # category in Turkey and its names also contain "havası"/"düğün".
        ("Oyun Havası", ("oyun havası", "oyun havasi", "oyunhavasi",
                         "düğün", "dugun", "halay", "çiftetelli", "ciftetelli",
                         "roman havası", "gazino", "eğlence havası",
                         "davul zurna", "kına")),
        ("Haber", ("haber", "news", "cnn", "ntv", "gazete", "ekonomi",
                   "bloomberg", "politika")),
        ("Spor", ("spor", "sport", "fitness", "maç")),
        ("Dini", ("islam", "kuran", "kur'an", "dini", "ilahi", "mevlana",
                  "cami", "risale", "muslim", "hac", "diyanet")),
        ("Türkü / Halk", ("türkü", "folk", "ozan", "halk", "anadolu",
                          "bozkurt", "mahalli")),
        ("Arabesk / Fantazi", ("arabesk", "fantazi", "damar", "aşk")),
        ("Rock / Alternatif", ("rock", "metal", "alternatif", "indie",
                               "punk", "grunge")),
        ("Elektronik / Dans", ("house", "techno", "dance", "electron",
                               "disco", "dj", "chill", "lounge", "deep",
                               "trance", "dub", "downtempo", "ambient",
                               "nu ", "club", "remix")),
        ("Klasik / Caz", ("classic", "klasik", "jazz", "caz", "senfoni",
                          "opera", "saz")),
        ("Pop", ("pop", "hit", "top ", "power", "number", "hot ")),
        ("Türkçe Müzik", ("türkçe", "turkish music", "turk", "slow",
                          "kral", "fenomen", "metro", "süper", "joy",
                          "mydonose", "alem", "best fm", "radyo d")),
        ("Çocuk / Aile", ("çocuk", "kids", "çizgi")),
        ("Yerel", ("local", "yerel", "il radyosu")),
    ]
    for label, words in rules:
        if any(w in hay for w in words):
            return label
    return "Diğer"


def main() -> int:
    stations = json.loads(SRC.read_text("utf-8"))
    print(f"Toplam {len(stations)} radyo kontrol ediliyor...")
    ok, bad = [], []
    with ThreadPoolExecutor(max_workers=32) as pool:
        futures = {pool.submit(probe, s): s for s in stations}
        done = 0
        for fut in as_completed(futures):
            station, status, size = fut.result()
            done += 1
            if status == "ok":
                ok.append(station)
            else:
                station["_status"] = status
                bad.append(station)
            if done % 25 == 0:
                print(f"  {done}/{len(stations)}  calisan={len(ok)}  sorunlu={len(bad)}")

    ok.sort(key=lambda s: s.get("votes", 0) or 0, reverse=True)
    # Radios carry their own Turkish category so the tree can group them.
    for s in ok:
        s["group"] = categorize(s)
    SRC.write_text(json.dumps(ok, ensure_ascii=False, indent=2), "utf-8")
    DEAD.write_text(json.dumps(bad, ensure_ascii=False, indent=2), "utf-8")
    print(f"\nCalisan: {len(ok)}   Sorunlu: {len(bad)}")
    print(f"  -> {SRC} (oy sayisina gore sirali)")
    print(f"  -> {DEAD} (adresi duzeltilecekler)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
