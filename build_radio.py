#!/usr/bin/env python3
"""Build data/radios.json from the open radio-browser database.

Run:  python build_radio.py
Only Turkish stations are kept, ordered by community votes so the most
listened ones come first. The player shows them under a "Radyo" group and
plays them with the same engine as TV (a plain audio stream is handled too).
"""

from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "radios.json"

# Public, key-less community API. Several mirrors exist; the first that
# answers is used.
API = ("https://de1.api.radio-browser.info/json/stations/"
       "bycountrycodeexact/TR?hidebroken=true&order=votes&reverse=true")
UA = "IPTVPlayer/1.0"

LIMIT = 250


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=45) as resp:
        return resp.read().decode("utf-8", "replace")


ICON_LINK = re.compile(
    r'<link[^>]+rel=["\'][^"\']*icon[^"\']*["\'][^>]*>', re.IGNORECASE)
HREF = re.compile(r'href=["\']([^"\']+)["\']', re.IGNORECASE)
OG_IMAGE = re.compile(
    r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']',
    re.IGNORECASE)


def homepage_icon(homepage: str) -> str:
    """Best-effort icon for a station: its apple-touch-icon, favicon or og:image."""
    if not homepage:
        return ""
    try:
        html = fetch(homepage)[:200000]
    except Exception:
        return ""
    # Prefer an explicit apple-touch-icon, then any icon, then og:image.
    best = ""
    for tag in ICON_LINK.findall(html):
        m = HREF.search(tag)
        if not m:
            continue
        href = m.group(1).strip()
        if not href:
            continue
        if "apple-touch" in tag.lower():
            best = href
            break
        if not best:
            best = href
    if not best:
        m = OG_IMAGE.search(html)
        if m:
            best = m.group(1).strip()
    if not best:
        return ""
    if best.startswith("//"):
        return "https:" + best
    if best.startswith("/"):
        m = re.match(r"(https?://[^/]+)", homepage)
        return (m.group(1) if m else "") + best
    if not best.startswith("http"):
        return homepage.rstrip("/") + "/" + best
    return best


def slug(name: str) -> str:
    s = name.casefold()
    for a, b in (("ı", "i"), ("ş", "s"), ("ğ", "g"), ("ü", "u"),
                 ("ö", "o"), ("ç", "c"), ("â", "a"), ("î", "i")):
        s = s.replace(a, b)
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


def main() -> None:
    stations = json.loads(fetch(API))
    items = []
    seen_names: set[str] = set()
    seen_urls: set[str] = set()

    for s in stations:
        url = (s.get("url_resolved") or "").strip()
        name = (s.get("name") or "").strip()
        if not url or not name:
            continue
        # A station is often listed several times (once per mirror); keep the
        # most-voted copy of each name and each stream URL.
        key = slug(name)
        if key in seen_names or url in seen_urls:
            continue
        seen_names.add(key)
        seen_urls.add(url)
        tags = (s.get("tags") or "").split(",")
        items.append({
            "name": name,
            "url": url,
            "logo": s.get("favicon") or "",
            "group": "Radyo",
            "tvgId": "",
            "source": "Radyo",
            "kind": "radio",
            "votes": s.get("votes") or 0,
            "homepage": s.get("homepage") or "",
            "tags": [t for t in (x.strip() for x in tags) if t][:6],
        })
        if len(items) >= LIMIT:
            break

    # Fill in a logo for stations the database has none for, using the
    # station's own website icon so every row has artwork.
    missing = [it for it in items if not it["logo"] and it["homepage"]]
    print(f"fetching icons for {len(missing)} stations…")
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(max_workers=12) as pool:
        icons = list(pool.map(lambda it: homepage_icon(it["homepage"]), missing))
    for it, icon in zip(missing, icons):
        if icon:
            it["logo"] = icon
    filled = sum(1 for it in items if it["logo"])
    print(f"+ {filled}/{len(items)} stations have a logo")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(items, ensure_ascii=False, indent=2), "utf-8")
    print(f"wrote {len(items)} Turkish radio stations -> {OUT}")


if __name__ == "__main__":
    main()
