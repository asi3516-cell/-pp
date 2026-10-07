#!/usr/bin/env python3
"""Zincirin tek commit'te gitmesi icin urlcheck.json -> channels.json -> gomulu
-> android assets -> probe onbellegi saglik supurmesi.

Faz 3: 7 gun olu kalan adresleri listelerden dusurur, data/urlcheck.dropped.json
dosyasina atar. Varsayilan olarak yalnizca rapor uretir; --apply ile yazar.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
URLCHECK = DATA / "urlcheck.json"
LAST_OK = DATA / "urlcheck.last_ok.json"
DROPPED = DATA / "urlcheck.dropped.json"
STALE_DAYS = 7


def load_rows() -> list:
    try:
        rows = json.loads(URLCHECK.read_text("utf-8"))
    except (OSError, ValueError):
        return []
    # Liste bicimi beklenir; probe onbellegi ayri dosyada tutulur.
    return rows if isinstance(rows, list) else []


def sweep(dry_run: bool = True) -> dict:
    """last_ok damgasi tutar; STALE_DAYS gun olu kalan adresleri isaretler."""
    now = time.time()
    try:
        last_ok = json.loads(LAST_OK.read_text("utf-8"))
    except (OSError, ValueError):
        last_ok = {}

    rows = load_rows()
    dropped, kept_rows = [], []
    for row in rows:
        if not isinstance(row, list) or not row:
            continue
        url, status = row[0], row[1]
        if status == "OK":
            last_ok[url] = now
        else:
            seen = last_ok.get(url)
            if seen is None:
                # Bilinmeyen ilk gorulme; bugunle damgala ki 7 gun sonra duser.
                last_ok[url] = now
            elif now - seen > STALE_DAYS * 86400:
                dropped.append(row)
                continue
        kept_rows.append(row)

    if not dry_run:
        URLCHECK.write_text(json.dumps(kept_rows, ensure_ascii=False, indent=1), "utf-8")
        LAST_OK.write_text(json.dumps(last_ok, ensure_ascii=False), "utf-8")
        DROPPED.write_text(json.dumps(dropped, ensure_ascii=False, indent=1), "utf-8")

    return {"incelenen": len(rows), "dusen": len(dropped)}


def main() -> None:
    import sys
    apply = "--apply" in sys.argv
    result = sweep(dry_run=not apply)
    print(f"urlcheck: {result['incelenen']} adres, "
          f"{result['dusen']} tanesi {STALE_DAYS} gun olu -> dusuruldu"
          + ("" if apply else " (kuru calisma, yazmadi)"))


if __name__ == "__main__":
    main()
