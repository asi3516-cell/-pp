#!/usr/bin/env python3
"""Sorunlu radyolar icin radio-browser'dan calisan yedek adres bulur.

data/radios-dead.json icindeki her istasyon icin ismiyle radio-browser'da
arama yapar, donen aday adresleri tek tek dener ve calisan ilk adresi
data/radios.json'a geri ekler. Bulunamayanlar dead dosyasinda kalir.
"""
from __future__ import annotations

import json
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from verify_radios import probe, UA, CTX

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "data" / "radios.json"
DEAD = ROOT / "data" / "radios-dead.json"
APIS = ["https://de1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"]


def search(name: str) -> list[dict]:
    q = urllib.parse.urlencode({
        "name": name, "limit": 12, "hidebroken": "true",
        "order": "votes", "reverse": "true"})
    for api in APIS:
        try:
            req = urllib.request.Request(api + "/json/stations/search?" + q,
                                         headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=12, context=CTX) as r:
                return json.loads(r.read().decode("utf-8", "replace"))
        except Exception:
            continue
    return []


def tokens(text: str, min_len: int = 2) -> set[str]:
    text = text.lower()
    for ch in ".,;:!?()[]{}'\"-_/|+&":
        text = text.replace(ch, " ")
    return {t for t in text.split() if len(t) >= min_len}


def name_ok(want: str, cand: str) -> bool:
    """Guard against picking a different station that merely happens to play.

    The candidate name must share a meaningful token with the wanted name, so
    e.g. "Haberturk Radyo" is not satisfied by a "Haberturk TV" stream."""
    a, b = tokens(want), tokens(cand)
    if not a or not b:
        return False
    # Short tokens ("tv", "fm") matter for these checks even though they are
    # too generic to prove a match on their own. Glued names such as
    # "haberturktv" carry the marker inside a single token, so check both.
    glued_a, glued_b = "".join(a), "".join(b)
    if ("tv" in b or "tv" in glued_b) and "tv" not in glued_a:
        return False
    if "radyo" in glued_a and "radyo" not in glued_b and "fm" not in glued_b:
        return False
    strong_a, strong_b = tokens(want, 3), tokens(cand, 3)
    return bool(strong_a & strong_b)


def fix(station: dict) -> dict | None:
    # Every bundled station is Turkish, so a same-named foreign station is not
    # an acceptable stand-in; only Turkish candidates are considered.
    cands = [c for c in search(station["name"])
             if (c.get("countrycode") or "").upper() == "TR"]
    for cand in cands:
        if not name_ok(station["name"], cand.get("name", "")):
            continue
        url = cand.get("url_resolved") or cand.get("url")
        if not url:
            continue
        # Some radio-browser entries carry a TV feed URL under a radio name
        # (e.g. "haberturktv"). A stream whose *path* mentions "tv" is never a
        # valid stand-in for a station that is not itself a TV channel. The
        # host is ignored so that "radyotvonline.net" stays acceptable.
        path = urllib.parse.urlparse(url).path.lower()
        if re.search(r"[a-z]tv|tv[a-z_/]|/tv\b", path) and "tv" not in station["name"].lower():
            continue
        trial = dict(station)
        trial["url"] = url
        trial["logo"] = station.get("logo") or cand.get("favicon") or ""
        trial["homepage"] = cand.get("homepage") or station.get("homepage", "")
        _, status, _ = probe(trial)
        if status == "ok":
            trial.pop("_status", None)
            trial["fixed"] = True
            return trial
    return None


def main() -> int:
    dead = json.loads(DEAD.read_text("utf-8"))
    good = json.loads(SRC.read_text("utf-8"))
    print(f"{len(dead)} sorunlu radyo icin yedek adres araniyor...")
    fixed, still = [], []
    with ThreadPoolExecutor(max_workers=8) as pool:
        for st, res in zip(dead, pool.map(fix, dead)):
            if res:
                fixed.append(res)
                print("  +", st["name"], "->", res["url"][:70])
            else:
                still.append(st)
                print("  -", st["name"], "(bulunamadi)")

    good.extend(fixed)
    good.sort(key=lambda s: s.get("votes", 0) or 0, reverse=True)
    SRC.write_text(json.dumps(good, ensure_ascii=False, indent=2), "utf-8")
    DEAD.write_text(json.dumps(still, ensure_ascii=False, indent=2), "utf-8")
    print(f"\nDuzeltilen: {len(fixed)}   Hala sorunlu: {len(still)}   Toplam: {len(good)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
