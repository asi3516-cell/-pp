#!/usr/bin/env python3
"""Merge the "(2)", "(3)" duplicate rows into one channel each.

The bundled list carries every mirror as its own row, so a channel that has
four addresses shows up four times. That is what makes the list look like it
is missing a merge. Here the rows that share a base name are folded into a
single channel whose `urls` array holds every address, working ones first, so
the player can fall through the mirrors on failure.
"""

from __future__ import annotations

import json
import os
import re

ROOT = os.path.dirname(os.path.abspath(__file__))
SUFFIX = re.compile(r"\s*\(\d+\)\s*$")


def base_name(name: str) -> str:
    return SUFFIX.sub("", name).strip()


def load_health() -> dict[str, str]:
    path = os.path.join(ROOT, "data", "urlcheck.json")
    return {row[0]: row[1] for row in json.load(open(path, encoding="utf-8"))}


def merge(channels: list[dict], health: dict[str, str]) -> list[dict]:
    order: list[str] = []
    buckets: dict[str, list[dict]] = {}
    for ch in channels:
        key = base_name(ch["name"])
        if key not in buckets:
            buckets[key] = []
            order.append(key)
        buckets[key].append(ch)

    merged: list[dict] = []
    for key in order:
        rows = buckets[key]
        if len(rows) == 1:
            merged.append(rows[0])
            continue

        seen: set[str] = set()
        urls: list[dict] = []
        for row in rows:
            for entry in row.get("urls") or [{"url": row["url"]}]:
                if entry["url"] in seen:
                    continue
                seen.add(entry["url"])
                urls.append(entry)
        # Working addresses first so the player starts on a live mirror.
        urls.sort(key=lambda e: 0 if health.get(e["url"]) == "OK" else 1)

        # Keep the group of a row that still has a working address, so a channel
        # does not fall into the broken bucket because of one dead mirror.
        group = next(
            (r["group"] for r in rows
             if any(health.get(u["url"]) == "OK" for u in (r.get("urls") or []))),
            rows[0]["group"],
        )
        logo = next((r["logo"] for r in rows if r.get("logo")), None)
        kind = "live" if any(r.get("kind") != "radio" for r in rows) else "radio"

        head = dict(rows[0])
        head["name"] = key
        head["group"] = group
        head["logo"] = logo
        head["kind"] = kind
        head["urls"] = urls
        head["url"] = urls[0]["url"]
        merged.append(head)
    return merged


def main() -> None:
    health = load_health()
    path = os.path.join(ROOT, "data", "channels.json")
    channels = json.load(open(path, encoding="utf-8"))
    merged = merge(channels, health)
    payload = json.dumps(merged, ensure_ascii=False, indent=1)
    for target in (
        path,
        os.path.join(ROOT, "android", "app", "src", "main", "assets", "channels.json"),
    ):
        if os.path.exists(os.path.dirname(target)):
            with open(target, "w", encoding="utf-8") as fh:
                fh.write(payload)
    print(f"{len(channels)} satir -> {len(merged)} kanal")


if __name__ == "__main__":
    main()
