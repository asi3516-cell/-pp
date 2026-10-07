"""Restore the channels that were dropped when the whole "Calismayanlar"
bucket was deleted, but only the addresses that actually answer today.

build/recoverable.json is the output of build/probe_lost.py: every dropped
channel whose address returned a playable HLS/TS stream in the last sweep.
New channels are appended; channels that already exist just gain the extra
working mirrors (so "a HABER" picks up its ercdn mirror too).
"""
from __future__ import annotations

import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SUF = re.compile(r"\s*\(\d+\)\s*$")


def base(name: str) -> str:
    return SUF.sub("", name).strip().lower()


def main() -> None:
    path = os.path.join(ROOT, "data", "channels.json")
    channels = json.load(open(path, encoding="utf-8"))
    rec = json.load(open(os.path.join(ROOT, "build", "recoverable.json"), encoding="utf-8"))

    index = {base(c["name"]): c for c in channels}
    added, mirrored = 0, 0
    radio_hint = re.compile(r"radyo|radio|/rd-", re.I)

    for key, rows in rec.items():
        ok = [r for r in rows if str(r.get("status", "")).startswith("OK")]
        if not ok:
            continue
        key = base(key)
        # The bundled list labels some TV channels "radio" (they came from a
        # radio feed), so trust the verified addresses instead: a channel is
        # radio only if every working address is a radio stream. That also
        # drops a TV channel whose only surviving mirror is its radio feed.
        is_radio = all(radio_hint.search(r["url"]) for r in ok)
        if key in index:
            ch = index[key]
            known = {u["url"] for u in ch.get("urls") or []}
            fresh = [r["url"] for r in ok if r["url"] not in known]
            if not fresh:
                continue
            ch["kind"] = "radio" if is_radio else "live"
            # Put the just-verified mirrors ahead of the bundled ones: some
            # channels carry a radio feed as their first address, so without
            # this the TV channel opens as audio only.
            ch["urls"] = ([{"url": u, "label": "Kurtarildi"} for u in fresh]
                          + list(ch.get("urls") or []))
            ch["url"] = fresh[0]
            mirrored += len(fresh)
            continue
        channels.append({
            "name": ok[0]["name"].split(" (")[0].strip(),
            "url": ok[0]["url"],
            "logo": ok[0].get("logo") or "",
            "group": ok[0].get("group") if ok[0].get("group") != "Calismayanlar" else "Ulusal",
            "tvgId": "",
            "source": "Kurtarildi",
            "rank": 900,
            "urls": [{"url": r["url"], "label": "Kurtarildi"} for r in ok],
            "kind": "radio" if is_radio else "live",
        })
        added += 1

    payload = json.dumps(channels, ensure_ascii=False, indent=1)
    targets = [
        path,
        os.path.join(ROOT, "android", "app", "src", "main", "assets", "channels.json"),
    ]
    for t in targets:
        if os.path.exists(os.path.dirname(t)):
            with open(t, "w", encoding="utf-8") as fh:
                fh.write(payload)
    js = json.dumps(channels, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(ROOT, "static", "channels-data.js"), "w", encoding="utf-8") as fh:
        fh.write("window.HH_CHANNELS = " + js + ";\n")

    print(f"{added} kanal geri eklendi, {mirrored} yedek adres mevcut kanallara eklendi")
    print(f"toplam kanal: {len(channels)}")


if __name__ == "__main__":
    main()
