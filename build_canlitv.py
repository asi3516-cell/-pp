#!/usr/bin/env python3
"""Collect Turkish channel streams from canlitv.you.

The site lists each channel on a `/<slug>` page that only sets an internal
channel id; the actual stream URL lives on `/embed/?id=<id>` inside a jwplayer
`file:` field. For many channels that field is a direct HLS/MP4 URL we can play;
for others it points at a third-party web player (atv.com.tr, tabii.com, ...)
which we skip because it is not a raw stream.

Run:  python build_canlitv.py   ->  writes data/canlitv.json
"""

from __future__ import annotations

import concurrent.futures as futures
import json
import re
import subprocess
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "data" / "canlitv.json"
BASE = "https://www.canlitv.you"

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

SLUG_RE = re.compile(r'href="(/[a-z0-9-]+)"[^>]*title="([^"]*canlı izle[^"]*)"[^>]*>([^<]*)</a>')
ID_RE = re.compile(r"aktifKanal=(\d+)")
FILE_RE = re.compile(r'file:\s*"([^"]+)"')
STREAM_RE = re.compile(r"\.(m3u8|mp4|webm)(\?|$)", re.I)


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
        if slug in seen:
            continue
        seen.add(slug)
        out.append((slug, re.sub(r"<[^>]+>", "", name).strip()))
    return out


def resolve(slug: str) -> dict | None:
    page = get(f"{BASE}/{slug}")
    m = ID_RE.search(page)
    if not m:
        return None
    embed = get(f"{BASE}/embed/?id={m.group(1)}", referer=f"{BASE}/{slug}")
    fm = FILE_RE.search(embed)
    if not fm:
        return None
    url = fm.group(1)
    if not STREAM_RE.search(url):
        return None  # third-party web player, not a raw stream
    if url.startswith("//"):
        url = "https:" + url
    elif url.startswith("/"):
        url = urllib.parse.urljoin(BASE, url)
    return {"url": url, "id": m.group(1)}


def main() -> None:
    slugs = slug_list()
    print(f"bulunan kanal: {len(slugs)}")

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
                continue
            channels.append({
                "name": name or slug,
                "url": res["url"],
                "logo": "",
                "group": "Türkiye",
                "tvg-id": "",
                "source": "Canlitv",
            })

    channels.sort(key=lambda c: c["name"].casefold())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(channels, ensure_ascii=False, indent=2), "utf-8")
    print(f"doğrudan akış: {len(channels)}  (başarısız: {failed}) -> {OUT}")


if __name__ == "__main__":
    main()
