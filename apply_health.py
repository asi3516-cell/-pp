"""Reorder each channel's mirrors so a working address comes first, and move
channels whose every address failed into a dedicated "Calismayanlar" group.

Reads data/urlcheck.json (produced by the health sweep) and rewrites the
bundled list in data/channels.json, the APK asset and the web bundle.
"""
import json
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
BAD_GROUP = "Calismayanlar"


def load_health() -> dict[str, str]:
    path = os.path.join(ROOT, "data", "urlcheck.json")
    return {row[0]: row[1] for row in json.load(open(path, encoding="utf-8"))}


def load_geo() -> set[str]:
    """Addresses that answered with 401/403/421. Those are usually region
    locked, not dead, so a Turkish connection can still play them. They must
    not be written off as broken."""
    path = os.path.join(ROOT, "data", "urlcheck.json")
    geo = set()
    for row in json.load(open(path, encoding="utf-8")):
        msg = str(row[3])
        if row[1] != "OK" and any(code in msg for code in ("401", "403", "421")):
            geo.add(row[0])
    return geo


def reorder(channels: list[dict], health: dict[str, str], geo: set[str]) -> tuple[list[dict], int]:
    working, broken = [], []
    for ch in channels:
        urls = ch.get("urls") or [{"url": ch["url"], "label": ch.get("source", "")}]
        # Stable partition: working mirrors keep their relative order and move
        # ahead of the dead ones, so the player tries a live address first.
        ok = [u for u in urls if health.get(u["url"]) == "OK"]
        dead = [u for u in urls if health.get(u["url"]) != "OK"]
        ch["urls"] = ok + dead
        if ok:
            ch["url"] = ok[0]["url"]
            working.append(ch)
        elif any(u["url"] in geo for u in urls):
            # Region locked rather than gone; leave it where it was.
            working.append(ch)
        else:
            ch["group"] = BAD_GROUP
            broken.append(ch)
    return working + broken, len(broken)


def main() -> None:
    health = load_health()
    geo = load_geo()
    channels = json.load(open(os.path.join(ROOT, "data", "channels.json"), encoding="utf-8"))
    ordered, broken = reorder(channels, health, geo)
    payload = json.dumps(ordered, ensure_ascii=False, indent=1)

    targets = [
        os.path.join(ROOT, "data", "channels.json"),
        os.path.join(ROOT, "android", "app", "src", "main", "assets", "channels.json"),
    ]
    for path in targets:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(payload)

    js = json.dumps(ordered, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(ROOT, "static", "channels-data.js"), "w", encoding="utf-8") as fh:
        fh.write("window.HH_CHANNELS = " + js + ";\n")

    print(f"{len(ordered)} kanal | {broken} tanesi '{BAD_GROUP}' grubuna tasindi")


if __name__ == "__main__":
    main()
