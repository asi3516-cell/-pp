"""Live-probe the addresses that were dropped from the bundled list and print
which of them answer with a playable stream right now."""
from __future__ import annotations

import concurrent.futures as cf
import json
import re
import ssl
import urllib.request

SUF = re.compile(r"\s*\(\d+\)\s*$")
UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
REFERER = "https://www.canlitv.fun/"
CTX = ssl.create_default_context()
CTX.check_hostname = False
CTX.verify_mode = ssl.CERT_NONE


def base(n: str) -> str:
    return SUF.sub("", n).strip()


def probe(url: str) -> tuple[str, str, int]:
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Referer": REFERER,
        "Accept": "*/*", "Range": "bytes=0-2047",
    })
    try:
        with urllib.request.urlopen(req, timeout=12, context=CTX) as r:
            head = r.read(2048)
            code = r.getcode()
    except urllib.error.HTTPError as e:
        return url, f"HTTP {e.code}", 0
    except Exception as e:
        return url, f"ERR {type(e).__name__}", 0
    if code != 200:
        return url, f"HTTP {code}", len(head)
    if head[:7] == b"#EXTM3U":
        return url, "OK-HLS", len(head)
    if head[:4] == b"\x00\x00\x00" or head[:3] == b"ID3" or head[:2] == b"\xff\xfb":
        return url, "OK-TS", len(head)
    if b"<" in head[:200]:
        return url, "HTML?", len(head)
    return url, f"OK {len(head)}B", len(head)


def main() -> None:
    pre = json.load(open("data/channels.pre-merge.json", encoding="utf-8"))
    cur = json.load(open("data/channels.json", encoding="utf-8"))
    cur_names = {c["name"] for c in cur}
    missing: dict[str, list[dict]] = {}
    for c in pre:
        k = base(c["name"])
        if k in cur_names:
            continue
        missing.setdefault(k, []).append(c)

    targets: list[tuple[str, dict, str]] = []
    for k, rows in missing.items():
        for c in rows:
            for u in (c.get("urls") or [{"url": c.get("url")}]):
                targets.append((k, c, u["url"]))

    urls = sorted({t[2] for t in targets})
    print(f"{len(missing)} eksik kanal | {len(urls)} benzersiz adres test ediliyor...")
    results: dict[str, str] = {}
    with cf.ThreadPoolExecutor(max_workers=24) as ex:
        for url, status, _ in ex.map(probe, urls):
            results[url] = status

    playable: dict[str, list[tuple[dict, str]]] = {}
    for k, c, url in targets:
        st = results[url]
        if st.startswith("OK"):
            playable.setdefault(k, []).append((c, url))

    print(f"\n=== CALISAN eksik kanal: {len(playable)} ===")
    for k in sorted(playable):
        print(f"{k}")
        for c, url in playable[k]:
            print(f"    [{results[url]}] {url}")
    json.dump(
        {k: [{"name": c["name"], "group": c.get("group"), "url": u,
              "logo": c.get("logo"), "kind": c.get("kind", "live"),
              "status": results[u]}
             for c, u in v] for k, v in playable.items()},
        open("build/recoverable.json", "w", encoding="utf-8"),
        ensure_ascii=False, indent=1,
    )
    print("\nyazildi: build/recoverable.json")


if __name__ == "__main__":
    main()
