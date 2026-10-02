#!/usr/bin/env python3
"""Collect Turkish channel streams from canlitv.you.

Each channel has a `/<slug>` page that only sets an internal id; the stream URL
lives on `/embed/?id=<id>` in a jwplayer `file:` field. For many channels that is
already a direct HLS URL. When it instead points at a third-party web player we
try to pull the real stream out of that page too:

* tabii.com (TRT channels) embeds a JSON payload whose media list contains a
  DRM-free ("clear") HLS URL, e.g. TRT 1 -> tv-trt1.medya.trt.com.tr/master.m3u8.
* ATV group channels are served from daioncdn.net; we add their known streams.

Run:  python build_canlitv.py   ->  writes data/canlitv.json + data/canlitv_ranks.json
"""

from __future__ import annotations

import concurrent.futures as futures
import json
import re
import subprocess
import urllib.parse
from pathlib import Path

from server import name_key

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "canlitv.json"
RANKS = ROOT / "data" / "canlitv_ranks.json"
BASE = "https://www.canlitv.you"

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

SLUG_RE = re.compile(r'href="(/[a-z0-9-]+)"[^>]*title="([^"]*canlı izle[^"]*)"[^>]*>([^<]*)</a>')
ID_RE = re.compile(r"aktifKanal=(\d+)")
FILE_RE = re.compile(r'file:\s*"([^"]+)"')
STREAM_RE = re.compile(r"\.(m3u8|mp4|webm)(\?|$)", re.I)
RANK_RE = re.compile(
    r"<li class='ft_\d+ tv fk_'>\s*(\d+)\.\s*<a href=\"(/[^\"]+)\"[^>]*>([^<]*)</a>"
)


def get(url: str, referer: str = "") -> str:
    """Fetch a page with curl; the site's Cloudflare layer rejects urllib."""
    cmd = ["curl", "-s", "-L", "--max-time", "20", "-A", UA,
           "-H", "Accept-Language: tr,en;q=0.8"]
    if referer:
        cmd += ["-e", referer]
    cmd.append(url)
    out = subprocess.run(cmd, capture_output=True, check=False).stdout
    return out.decode("utf-8", "replace")


def slug_list() -> list[tuple[str, str]]:
    """Return unique (slug, name) pairs from the TV channel listing."""
    html = get(f"{BASE}/modul/liste.php?tur=tv&Kanal_id=3", referer=f"{BASE}/")
    seen, out = set(), []
    for slug, _title, name in SLUG_RE.findall(html):
        slug = slug.lstrip("/")
        if slug in seen:
            continue
        seen.add(slug)
        out.append((slug, re.sub(r"<[^>]+>", "", name).strip()))
    return out


def popularity() -> tuple[dict[str, int], dict[str, int]]:
    """Return (slug -> rank) and (normalized name -> rank) popularity maps.

    The homepage ranks *every* listed channel, including the ones whose stream
    we cannot resolve (ATV, TRT 1, Kanal D ...). Keeping the name map lets the
    iptv-org fallback streams for those channels sort into the right place.
    """
    html = get(f"{BASE}/", referer=f"{BASE}/")
    by_slug, by_name = {}, {}
    for rank, slug, name in RANK_RE.findall(html):
        by_slug.setdefault(slug.lstrip("/"), int(rank))
        by_name.setdefault(name_key(name), int(rank))
    return by_slug, by_name


# JSON payload inside tabii.com pages, and the DRM-free HLS entry inside it.
JSON_BLOCK_RE = re.compile(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', re.S)
# ATV-group channels are hosted on daioncdn; the site picks the stream by id.
ATV_STREAMS = {
    "atv": "https://trkvz.daioncdn.net/atv/atv.m3u8?ce=3&app=866e32e3-9fea-477f-a5ef-64ebe32956f3",
    "a2tv": "https://trkvz.daioncdn.net/a2tv/a2tv.m3u8?ce=3&app=59363a60-be96-4f73-9eff-355d0ff2c758",
    "a haber": "https://trkvz.daioncdn.net/ahaber/ahaber.m3u8",
    "a spor": "https://trkvz.daioncdn.net/aspor/aspor.m3u8?ce=3&app=",
    "a para": "https://trkvz.daioncdn.net/apara/apara.m3u8",
    "atv avrupa": "https://trkvz-live.ercdn.net/atvavrupahd/atvavrupahd.m3u8",
    "minika go": "https://trkvz.daioncdn.net/minikago/minikago.m3u8",
    "minika cocuk": "https://trkvz.daioncdn.net/minikago_cocuk/minikago_cocuk.m3u8",
}


def _tabii_stream(url: str) -> str | None:
    """Extract the DRM-free HLS URL for the channel the tabii page is showing."""
    page = get(url, referer="https://www.tabii.com/")
    want = name_key(urllib.parse.urlparse(url).path.rsplit("/", 1)[-1])
    for block in JSON_BLOCK_RE.findall(page):
        try:
            data = json.loads(block)
        except ValueError:
            continue
        for ch in data.get("props", {}).get("pageProps", {}).get("liveChannels", []):
            if want and name_key(str(ch.get("slug", ""))) != want:
                continue
            for media in ch.get("media", []):
                if media.get("type") == "hls" and media.get("drmSchema") == "clear":
                    return media.get("url")
    return None


def resolve(slug: str) -> list[str]:
    """Return playable stream URLs for a channel, best first."""
    page = get(f"{BASE}/{slug}")
    m = ID_RE.search(page)
    if not m:
        return []
    embed = get(f"{BASE}/embed/?id={m.group(1)}", referer=f"{BASE}/{slug}")
    fm = FILE_RE.search(embed)
    if not fm:
        return []
    url = fm.group(1)
    if STREAM_RE.search(url):
        if url.startswith("//"):
            url = "https:" + url
        elif url.startswith("/"):
            url = urllib.parse.urljoin(BASE, url)
        return [url]

    # Third-party web player: try to pull the raw stream out of it.
    if "tabii.com" in url:
        found = _tabii_stream(url)
        return [found] if found else []
    return []


def main() -> None:
    slugs = slug_list()
    print(f"bulunan kanal: {len(slugs)}")
    ranks, ranks_by_name = popularity()
    print(f"popülerlik sırası: {len(ranks)}")

    channels, failed = [], 0
    with futures.ThreadPoolExecutor(max_workers=8) as pool:
        futs = {pool.submit(resolve, s): (s, n) for s, n in slugs}
        for fut in futures.as_completed(futs):
            slug, name = futs[fut]
            try:
                res = fut.result()
            except Exception:  # noqa: BLE001
                failed += 1
                continue
            if not res:
                # ATV-group channels go through a third-party player we cannot
                # scrape; use their known daioncdn streams instead.
                atv = ATV_STREAMS.get(name_key(name or slug))
                if atv:
                    res = [atv]
                else:
                    continue
            channels.append({
                "name": name or slug,
                "url": res[0],
                "urls": res,
                "logo": "",
                "group": "Türkiye",
                "tvg-id": "",
                "source": "Canlitv",
                # Lower rank number = more watched; unknown channels sort last.
                "rank": ranks.get(slug, 100000),
            })

    channels.sort(key=lambda c: (c["rank"], c["name"].casefold()))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(channels, ensure_ascii=False, indent=2), "utf-8")
    RANKS.write_text(json.dumps(ranks_by_name, ensure_ascii=False, indent=2), "utf-8")
    print(f"doğrudan akış: {len(channels)}  (başarısız: {failed}) -> {OUT}")
    print(f"popülerlik haritası: {len(ranks_by_name)} kanal -> {RANKS}")


if __name__ == "__main__":
    main()
