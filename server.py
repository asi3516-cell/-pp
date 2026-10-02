#!/usr/bin/env python3
"""IPTV Web Player server.

Serves the static web player and exposes a small JSON API plus a CORS
stream proxy so that HLS playlists (.m3u8) and segments can be played from
the browser even when the upstream provider does not send CORS headers.

Uses only the Python standard library so it runs anywhere Python 3.9+ is
available.
"""

from __future__ import annotations

import argparse
import gzip
import io
import json
import mimetypes
import os
import re
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

def _app_root() -> Path:
    """Folder that holds the bundled `static/` and `data/`.

    When frozen into a single-file executable (PyInstaller), the assets are
    unpacked to a temp dir exposed as ``sys._MEIPASS``. Otherwise the assets
    sit next to this source file.
    """
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    return Path(__file__).resolve().parent


def _writable_dir() -> Path:
    """Folder for user data (store.json), next to the app or its executable."""
    if getattr(sys, "frozen", False):
        base = Path(sys.executable).resolve().parent
    else:
        base = Path(__file__).resolve().parent
    try:
        base.mkdir(parents=True, exist_ok=True)
        probe = base / ".write-test"
        probe.write_text("ok", "utf-8")
        probe.unlink()
        return base / "data"
    except OSError:
        # Fall back to the user's home directory (e.g. read-only app bundle).
        fallback = Path.home() / ".iptv-player"
        fallback.mkdir(parents=True, exist_ok=True)
        return fallback


ROOT = _app_root()
STATIC_DIR = ROOT / "static"
DATA_DIR = _writable_dir()
STORE_FILE = DATA_DIR / "store.json"
# Read-only bundled defaults (channel list shipped inside the app).
BUNDLED_DATA_DIR = ROOT / "data"

DEFAULT_TIMEOUT = 20
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

_SSL_CTX = ssl.create_default_context()
_SSL_CTX.check_hostname = False
_SSL_CTX.verify_mode = ssl.CERT_NONE

_store_lock = threading.Lock()


# --------------------------------------------------------------------------
# Persistent store (playlists, favorites, settings)
# --------------------------------------------------------------------------
def _default_store() -> dict:
    return {"playlists": [], "favorites": [], "settings": {}}


def load_store() -> dict:
    with _store_lock:
        if not STORE_FILE.exists():
            return _default_store()
        try:
            data = json.loads(STORE_FILE.read_text("utf-8"))
        except (OSError, ValueError):
            return _default_store()
        base = _default_store()
        base.update({k: data.get(k, base[k]) for k in base})
        return base


def save_store(store: dict) -> None:
    with _store_lock:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = STORE_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(store, ensure_ascii=False, indent=2), "utf-8")
        tmp.replace(STORE_FILE)


# --------------------------------------------------------------------------
# Upstream fetching helpers
# --------------------------------------------------------------------------
def fetch(url: str, headers: dict | None = None, timeout: int = DEFAULT_TIMEOUT):
    """Fetch an upstream URL and return (status, headers, body-bytes)."""
    req_headers = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Encoding": "gzip, identity",
    }
    if headers:
        req_headers.update({k: v for k, v in headers.items() if v})
    req = urllib.request.Request(url, headers=req_headers)
    with urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) as resp:
        raw = resp.read()
        hdrs = {k.lower(): v for k, v in resp.headers.items()}
        if hdrs.get("content-encoding") == "gzip" and raw[:2] == b"\x1f\x8b":
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return resp.status, hdrs, raw


M3U8_RE = re.compile(r"\.m3u8(\?|$)", re.IGNORECASE)
URI_ATTR_RE = re.compile(r'URI="([^"]+)"')


def is_playlist(url: str, content_type: str) -> bool:
    if M3U8_RE.search(url):
        return True
    ct = (content_type or "").lower()
    return "mpegurl" in ct or "vnd.apple" in ct


def proxy_url(absolute: str, extra_qs: str = "") -> str:
    return "/api/proxy?url=" + urllib.parse.quote(absolute, safe="") + extra_qs


def rewrite_playlist(text: str, base_url: str, extra_qs: str = "") -> str:
    """Rewrite every URI in an HLS playlist so it points back at our proxy.

    `extra_qs` carries forwarded headers (e.g. UA/Cookie for MAC portals) so
    that child playlists and segments keep the same authorization.
    """
    out_lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            # Rewrite URI="..." attributes (encryption keys, media renditions).
            def _sub(m: re.Match) -> str:
                target = urllib.parse.urljoin(base_url, m.group(1))
                return 'URI="%s"' % proxy_url(target, extra_qs)

            out_lines.append(URI_ATTR_RE.sub(_sub, line))
        elif stripped:
            target = urllib.parse.urljoin(base_url, stripped)
            out_lines.append(proxy_url(target, extra_qs))
        else:
            out_lines.append(line)
    return "\n".join(out_lines)


# --------------------------------------------------------------------------
# HTTP handler
# --------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "IPTVPlayer/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the console readable
        if os.environ.get("IPTV_VERBOSE"):
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # -- helpers -----------------------------------------------------------
    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None,
              head_only: bool = False) -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not head_only:
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def _json(self, status: int, obj) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self._send(status, body, "application/json; charset=utf-8")

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except ValueError:
            return {}

    # -- routing -----------------------------------------------------------
    def do_OPTIONS(self):  # noqa: N802
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self):  # noqa: N802
        self._route(head_only=True)

    def do_GET(self):  # noqa: N802
        self._route(head_only=False)

    def do_POST(self):  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/") or "/"
        if path == "/api/store":
            store = self._read_json()
            if not isinstance(store, dict):
                return self._json(400, {"error": "invalid body"})
            current = load_store()
            for key in ("playlists", "favorites", "settings"):
                if key in store:
                    current[key] = store[key]
            save_store(current)
            return self._json(200, current)
        return self._json(404, {"error": "not found"})

    def _route(self, head_only: bool) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/api/health":
            return self._json(200, {"ok": True, "time": int(time.time())})
        if path == "/api/store":
            return self._json(200, load_store())
        if path == "/api/channels":
            return self._api_channels()
        if path == "/api/stalker":
            return self._api_stalker(query)
        if path == "/api/xtream":
            return self._api_xtream(query)
        if path == "/api/proxy":
            return self._api_proxy(query, head_only)
        if path == "/api/fetch":
            return self._api_fetch(query, head_only)
        if path == "/api/probe":
            return self._api_probe(query, head_only)
        if path == "/download/player":
            return self._download_player(head_only)
        if path == "/download/apk":
            return self._download_apk(head_only)
        if path == "/download/all":
            return self._download_all(head_only)
        if path == "/download/windows-rar":
            return self._send_file(
                ROOT / "build" / "pkg" / "IPTV-Player-Windows.rar",
                "IPTV-Player-Windows.rar", "application/vnd.rar", head_only)
        if path == "/download/source-rar":
            return self._send_file(
                ROOT / "build" / "pkg" / "IPTV-Player-Kaynak.rar",
                "IPTV-Player-Kaynak.rar", "application/vnd.rar", head_only)
        if path == "/channels.m3u":
            return self._download_channels(head_only)
        return self._static(path, head_only)

    def _download_channels(self, head_only: bool) -> None:
        """Serve the merged Turkish list as a plain M3U, so it can be opened in
        any IPTV app (Android, VLC, TV boxes). Every stream alternative becomes
        its own entry, so same-name backups appear as separate lines."""
        lines = ["#EXTM3U"]
        defaults = BUNDLED_DATA_DIR / "channels.json"
        channels = []
        if defaults.exists():
            try:
                channels = json.loads(defaults.read_text("utf-8"))
            except (OSError, ValueError):
                pass
        for ch in channels:
            name = ch.get("name", "Kanal")
            logo = ch.get("logo", "")
            group = ch.get("group", "Genel")
            urls = ch.get("urls") or [{"url": ch.get("url", ""), "label": ""}]
            for i, item in enumerate(urls):
                url = item.get("url") if isinstance(item, dict) else item
                if not url:
                    continue
                label = item.get("label", "") if isinstance(item, dict) else ""
                title = name if i == 0 else f"{name} (yedek: {label})"
                lines.append(
                    f'#EXTINF:-1 tvg-logo="{logo}" group-title="{group}",{title}')
                lines.append(url)
        body = ("\n".join(lines) + "\n").encode("utf-8")
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "audio/x-mpegurl; charset=utf-8")
        self.send_header("Content-Disposition",
                         'attachment; filename="turkce-kanallar.m3u"')
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def _download_all(self, head_only: bool) -> None:
        """Serve the whole project (source + built binary + channel list) as a
        single archive, so it can be grabbed and built on the user's machine."""
        files = [
            "server.py", "build_channels.py", "build_canlitv.py", "build_exe.py",
            "build_apk.py", "build_exe.bat", "README.md", "build/make.py",
            "build/iptv_player.spec", "build/requirements-build.txt",
            ".github/workflows/build.yml",
        ]
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            for name in files:
                f = ROOT / name
                if f.exists():
                    zf.write(f, f"IPTV-Player/{name}")
            for folder in ("static", "data", "android/app/src/main"):
                for f in sorted((ROOT / folder).rglob("*")):
                    if not f.is_file() or f.name.endswith((".class", ".dex")):
                        continue
                    zf.write(f, f"IPTV-Player/{f.relative_to(ROOT)}")
            for extra in (ROOT / "android" / "libs").glob("*.jar"):
                zf.write(extra, f"IPTV-Player/{extra.relative_to(ROOT)}")
            binary = ROOT / "dist" / "IPTV-Player"
            if binary.exists():
                zf.write(binary, "IPTV-Player/dist/IPTV-Player")
            apk = ROOT / "dist" / "IPTV-Player.apk"
            if apk.exists():
                zf.write(apk, "IPTV-Player/dist/IPTV-Player.apk")
        body = buf.getvalue()
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Disposition",
                         'attachment; filename="IPTV-Player.zip"')
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def _send_file(self, path, filename: str, ctype: str, head_only: bool) -> None:
        if not path.exists():
            return self._send(404, b"not built", "text/plain", head_only=head_only)
        size = path.stat().st_size
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Disposition",
                         f'attachment; filename="{filename}"')
        self.send_header("Content-Length", str(size))
        self.end_headers()
        if head_only:
            return
        with path.open("rb") as fh:
            while True:
                chunk = fh.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def _download_apk(self, head_only: bool) -> None:
        """Serve the built Android APK, if it has been assembled."""
        apk = ROOT / "dist" / "IPTV-Player.apk"
        if not apk.exists():
            return self._send(404, b"apk not built", "text/plain", head_only=head_only)
        size = apk.stat().st_size
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition",
                         'attachment; filename="IPTV-Player.apk"')
        self.send_header("Content-Length", str(size))
        self.end_headers()
        if head_only:
            return
        with apk.open("rb") as fh:
            while True:
                chunk = fh.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    def _download_player(self, head_only: bool) -> None:
        """Serve the built portable binary (source checkout only)."""
        binary = ROOT / "dist" / "IPTV-Player"
        if not binary.exists():
            return self._send(404, b"not built", "text/plain", head_only=head_only)
        size = binary.stat().st_size
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition",
                         'attachment; filename="IPTV-Player"')
        self.send_header("Content-Length", str(size))
        self.end_headers()
        if head_only:
            return
        with binary.open("rb") as fh:
            while True:
                chunk = fh.read(64 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    # -- API ---------------------------------------------------------------
    def _api_channels(self) -> None:
        """Return the bundled default channel list plus user M3U playlists."""
        channels = []
        defaults = BUNDLED_DATA_DIR / "channels.json"
        if defaults.exists():
            try:
                channels.extend(json.loads(defaults.read_text("utf-8")))
            except (OSError, ValueError):
                pass

        store = load_store()
        for pl in store.get("playlists", []):
            url = pl.get("url")
            if not url:
                continue
            try:
                _, hdrs, raw = fetch(url)
                text = raw.decode("utf-8", "replace")
                channels.extend(parse_m3u(text, pl.get("name") or url))
            except (urllib.error.URLError, OSError, ValueError):
                continue

        # De-duplicate by name+url.
        seen = set()
        unique = []
        for ch in channels:
            key = (ch.get("name"), ch.get("url"))
            if key in seen:
                continue
            seen.add(key)
            unique.append(ch)
        self._json(200, {"count": len(unique), "channels": unique})

    def _api_stalker(self, query: dict) -> None:
        portal = (query.get("portal") or [""])[0]
        mac = (query.get("mac") or [""])[0]
        try:
            channels, token = stalker_channels(portal, mac)
        except json.JSONDecodeError:
            return self._json(502, {"error": "geçersiz portal yanıtı (JSON değil)"})
        except ValueError as exc:
            return self._json(400, {"error": str(exc)})
        except urllib.error.HTTPError as exc:
            return self._json(502, {"error": f"portal HTTP {exc.code}"})
        except (urllib.error.URLError, OSError) as exc:
            return self._json(502, {"error": f"portal erişilemedi: {exc}"})
        return self._json(200, {
            "count": len(channels),
            "token": token,
            "channels": channels,
        })

    def _api_xtream(self, query: dict) -> None:
        portal = (query.get("portal") or [""])[0]
        user = (query.get("user") or [""])[0]
        password = (query.get("pass") or [""])[0]
        if not portal or not user or not password:
            return self._json(400, {"error": "portal, kullanıcı adı ve şifre gerekli"})
        try:
            channels, pl_url = xtream_channels(portal, user, password)
        except ValueError as exc:
            return self._json(400, {"error": str(exc)})
        except urllib.error.HTTPError as exc:
            return self._json(502, {"error": f"portal HTTP {exc.code}"})
        except (urllib.error.URLError, OSError) as exc:
            return self._json(502, {"error": f"portal erişilemedi: {exc}"})
        return self._json(200, {
            "count": len(channels),
            "playlist": pl_url,
            "channels": channels,
        })

    def _api_fetch(self, query: dict, head_only: bool) -> None:
        url = (query.get("url") or [""])[0]
        if not url:
            return self._json(400, {"error": "missing url"})
        try:
            status, hdrs, raw = fetch(url)
        except (urllib.error.URLError, OSError) as exc:
            return self._json(502, {"error": str(exc)})
        ctype = hdrs.get("content-type", "text/plain; charset=utf-8")
        self._send(status, raw, ctype, head_only=head_only)

    def _api_probe(self, query: dict, head_only: bool) -> None:
        """Check whether one or more stream URLs actually respond. Used by the
        'İncele' (inspect) view to show which alternatives work from here."""
        urls = []
        for raw in query.get("url", []):
            urls.extend(u for u in raw.split("|") if u)
        if not urls:
            return self._json(400, {"error": "missing url"})
        results = []
        for u in urls[:12]:
            entry = {"url": u, "ok": False, "status": 0, "error": "",
                     "kind": "", "note": ""}
            try:
                status, hdrs, raw = fetch(u, timeout=12)
                ctype = (hdrs.get("content-type") or "").lower()
                entry["status"] = status
                if raw[:7].upper() == b"#EXTM3U":
                    entry["kind"] = "playlist"
                    text = raw.decode("utf-8", "replace")
                    variants = [ln.strip() for ln in text.splitlines()
                                if ln.strip() and not ln.startswith("#")]
                    entry["variants"] = len(variants)
                    entry["ok"] = len(variants) > 0
                elif "mpegurl" in ctype or "vnd.apple" in ctype:
                    entry["kind"] = "playlist"
                    entry["ok"] = True
                elif "mpegts" in ctype or "mp2t" in ctype or "video" in ctype:
                    entry["kind"] = "media"
                    entry["ok"] = status == 200
                else:
                    entry["kind"] = "data"
                    entry["ok"] = status == 200 and len(raw) > 0
                    entry["note"] = ctype or "bilinmeyen tür"
            except urllib.error.HTTPError as exc:
                entry["status"] = exc.code
                entry["error"] = "HTTP %d (%s)" % (exc.code, exc.reason)
            except (urllib.error.URLError, OSError) as exc:
                entry["error"] = str(exc)[:120]
            results.append(entry)
        self._json(200, {"results": results})

    def _api_proxy(self, query: dict, head_only: bool) -> None:
        url = (query.get("url") or [""])[0]
        if not url:
            return self._json(400, {"error": "missing url"})

        fwd_headers = {}
        for src, dst in (("h", "Referer"), ("u", "User-Agent"),
                         ("o", "Origin"), ("c", "Cookie")):
            val = (query.get(src) or [None])[0]
            if val:
                fwd_headers[dst] = val

        rng = self.headers.get("Range")
        if rng:
            fwd_headers["Range"] = rng

        try:
            status, hdrs, raw = fetch(url, fwd_headers)
        except urllib.error.HTTPError as exc:
            return self._send(exc.code, b"upstream error", "text/plain")
        except (urllib.error.URLError, OSError) as exc:
            return self._json(502, {"error": str(exc)})

        ctype = hdrs.get("content-type", "")
        if is_playlist(url, ctype) and raw[:7].upper() == b"#EXTM3U":
            text = raw.decode("utf-8", "replace")
            extra_qs = ""
            if fwd_headers.get("User-Agent"):
                extra_qs += "&u=" + urllib.parse.quote(fwd_headers["User-Agent"], safe="")
            cookie = fwd_headers.get("Cookie")
            extra_qs += "&c=" + urllib.parse.quote(cookie or "", safe="")
            rewritten = rewrite_playlist(text, url, extra_qs)
            body = rewritten.encode("utf-8")
            return self._send(
                200, body, "application/vnd.apple.mpegurl",
                {"Cache-Control": "no-cache"}, head_only=head_only,
            )

        extra = {}
        for h in ("content-range", "accept-ranges"):
            if h in hdrs:
                extra[h.title()] = hdrs[h]
        if not ctype:
            ctype = mimetypes.guess_type(urllib.parse.urlparse(url).path)[0] or "application/octet-stream"
        self._send(status, raw, ctype, extra, head_only=head_only)

    # -- static files ------------------------------------------------------
    def _static(self, path: str, head_only: bool) -> None:
        rel = urllib.parse.unquote(path.lstrip("/")) or "index.html"
        candidate = (STATIC_DIR / rel).resolve()
        try:
            candidate.relative_to(STATIC_DIR.resolve())
        except ValueError:
            return self._send(403, b"forbidden", "text/plain")
        if candidate.is_dir():
            candidate = candidate / "index.html"
        if not candidate.exists():
            # Single-page-app fallback.
            candidate = STATIC_DIR / "index.html"
            if not candidate.exists():
                return self._send(404, b"not found", "text/plain")
        ctype = mimetypes.guess_type(str(candidate))[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in (
            "application/javascript", "application/json", "image/svg+xml",
        ):
            ctype += "; charset=utf-8"
        body = candidate.read_bytes()
        self._send(200, body, ctype, {"Cache-Control": "no-cache"}, head_only=head_only)


# --------------------------------------------------------------------------
# Minimal M3U parser (server side, used when importing playlists)
# --------------------------------------------------------------------------
ATTR_RE = re.compile(r'([\w-]+)="([^"]*)"')


def display_name(line: str) -> str:
    """Return the title after the EXTINF attributes.

    Attribute values may themselves contain commas (e.g. an http-user-agent),
    so only commas outside of quoted values delimit the display name.
    """
    in_quote = False
    last = -1
    for i, ch in enumerate(line):
        if ch == '"':
            in_quote = not in_quote
        elif ch == "," and not in_quote:
            last = i
    return line[last + 1:].strip() if last >= 0 else ""


# Tokens that carry no identity, so "360 TV", "360 (720p)" and "A Spor SD" all
# collapse to the same key as their counterpart from another source.
_NAME_NOISE = {
    "tv", "hd", "sd", "fhd", "uhd", "4k", "canli", "canlı", "live",
    "turkiye", "türkiye", "channel", "kanali", "kanalı",
}


def name_key(name: str) -> str:
    """Normalize a channel name for cross-source matching / de-duplication."""
    key = re.sub(r"\([^)]*\)", " ", name.casefold().strip())
    key = re.sub(r"\[[^\]]*\]", " ", key)
    key = re.sub(r"[^a-z0-9çğıöşü]+", " ", key)
    return " ".join(t for t in key.split() if t not in _NAME_NOISE)


def parse_m3u(text: str, source: str = "playlist") -> list:
    channels = []
    info = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            info = {"source": source}
            for key, val in ATTR_RE.findall(line):
                info[key] = val
            info["name"] = display_name(line) or info.get("tvg-name") or "Unnamed"
        elif line.startswith("#"):
            continue
        elif line.startswith(("http://", "https://", "rtmp://", "rtsp://")):
            if not info:
                info = {"name": line, "source": source}
            ch = normalize_channel(info)
            ch["url"] = line
            channels.append(ch)
            info = {}
    return channels


def normalize_channel(ch: dict) -> dict:
    """Map raw EXTINF attributes to the shape the front-end expects."""
    out = {
        "name": ch.get("name") or "Unnamed",
        "url": ch.get("url", ""),
        "logo": ch.get("tvg-logo") or ch.get("logo") or "",
        "group": ch.get("group-title") or ch.get("group") or "Genel",
        "tvgId": ch.get("tvg-id") or ch.get("tvgId") or "",
        "source": ch.get("source", "Playlist"),
    }
    if ch.get("headers"):
        out["headers"] = ch["headers"]
    if ch.get("kind"):
        out["kind"] = ch["kind"]
    if ch.get("urls"):
        out["urls"] = ch["urls"]
    if ch.get("rank") is not None:
        out["rank"] = ch["rank"]
    return out


STB_UA = (
    "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 "
    "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
)


def _stalker_headers(mac: str, token: str = "") -> dict:
    cookie = f"mac={mac}; stb_lang=tr; timezone=Europe/Istanbul"
    if token:
        cookie += f"; token={token}"
    return {"User-Agent": STB_UA, "Cookie": cookie, "Accept": "application/json"}


def _stalker_call(portal: str, params: str, mac: str, token: str = "",
                  timeout: int = DEFAULT_TIMEOUT):
    """Call a Stalker (MAG) portal load.php endpoint and return its `js` object."""
    base = portal.rstrip("/")
    url = f"{base}/server/load.php?{params}&JsHttpRequest=1-xml"
    _, _, raw = fetch(url, _stalker_headers(mac, token), timeout=timeout)
    data = json.loads(raw.decode("utf-8", "replace"))
    return data.get("js", data) if isinstance(data, dict) else data


def xtream_channels(portal: str, user: str, password: str) -> tuple[list, str]:
    """Load an Xtream Codes / XUI account. Returns (channels, playlist-url).

    Streams are built from the player API so the same credentials work for
    live TV, movies and series. `kind` tells the front-end which is which."""
    base = portal.strip().rstrip("/")
    if not re.match(r"^https?://", base):
        base = "http://" + base
    qs = urllib.parse.urlencode(
        {"username": user, "password": password, "action": "get_live_streams"})
    status, _hdrs, raw = fetch(f"{base}/player_api.php?{qs}")
    if status != 200:
        raise ValueError(f"portal HTTP {status}")
    try:
        live = json.loads(raw.decode("utf-8", "replace") or "[]")
    except json.JSONDecodeError:
        raise ValueError("portal JSON döndürmedi (Xtream API değil)")
    if isinstance(live, dict):
        raise ValueError(live.get("user_info", {}).get("auth")
                         and "kimlik doğrulanamadı" or "portal yanıtı geçersiz")

    def stream_url(folder, stream_id, ext):
        return f"{base}/{folder}/{user}/{password}/{stream_id}.{ext}"

    def categories(action):
        q = urllib.parse.urlencode(
            {"username": user, "password": password, "action": action})
        try:
            _s, _h, raw = fetch(f"{base}/player_api.php?{q}")
            items = json.loads(raw.decode("utf-8", "replace") or "[]")
        except (urllib.error.URLError, OSError, json.JSONDecodeError):
            return {}
        if not isinstance(items, list):
            return {}
        return {str(c.get("category_id")): c.get("category_name")
                for c in items if isinstance(c, dict)}

    live_cats = categories("get_live_categories")

    channels = []
    for item in live:
        if not isinstance(item, dict):
            continue
        sid = item.get("stream_id")
        if sid is None:
            continue
        ext = item.get("container_extension") or "ts"
        cat = live_cats.get(str(item.get("category_id")))
        channels.append({
            "name": item.get("name") or f"Kanal {sid}",
            "url": stream_url("live", sid, ext),
            "logo": item.get("stream_icon") or "",
            "group": item.get("category_name") or cat or "Genel",
            "tvgId": str(item.get("epg_channel_id") or ""),
            "source": "Xtream",
            "kind": "live",
        })

    # Movies and series need one extra call each; failures are non-fatal.
    for action, kind, folder, ext in (("get_vod_streams", "vod", "movie", "mkv"),
                                      ("get_series", "series", "series", "mkv")):
        qs = urllib.parse.urlencode(
            {"username": user, "password": password, "action": action})
        try:
            status, _hdrs, raw = fetch(f"{base}/player_api.php?{qs}")
            items = json.loads(raw.decode("utf-8", "replace") or "[]")
        except (urllib.error.URLError, OSError, json.JSONDecodeError):
            continue
        if not isinstance(items, list):
            continue
        cats = categories("get_vod_categories" if kind == "vod"
                          else "get_series_categories")
        for item in items:
            if not isinstance(item, dict):
                continue
            sid = item.get("stream_id") or item.get("series_id")
            if sid is None:
                continue
            e = item.get("container_extension") or ext
            cat = cats.get(str(item.get("category_id")))
            channels.append({
                "name": item.get("name") or f"{kind} {sid}",
                "url": stream_url(folder, sid, e),
                "logo": item.get("stream_icon") or item.get("cover") or "",
                "group": item.get("category_name") or cat or "Genel",
                "tvgId": "",
                "source": "Xtream",
                "kind": kind,
            })
    return channels, f"{base}/get.php?username={user}&password={password}&type=m3u_plus"


def stalker_channels(portal: str, mac: str) -> tuple[list, str]:
    """Handshake with a MAC-authorized portal and return (channels, token).

    Works with the common MAG/Stalker `load.php` API. The portal is provided
    by the user and must already be authorized for the given MAC address.
    """
    portal = portal.strip()
    mac = mac.strip()
    if not portal or not mac:
        raise ValueError("portal ve MAC adresi gerekli")

    hs = _stalker_call(
        portal,
        "type=stb&action=handshake&token=&prehash=0&JsHttpRequest=1-xml",
        mac,
    )
    token = (hs or {}).get("token", "")
    if not token:
        raise ValueError("Portal el sıkışmayı reddetti (MAC yetkili olmayabilir)")

    try:
        _stalker_call(
            portal,
            "type=stb&action=get_profile&hd=1&num_banks=2&stb_type=MAG250"
            "&client_type=STB&image_version=218&video_out=hdmi&hw_version=1.7-BD-00"
            "&not_valid_token=0&auth_second_step=1",
            mac, token,
        )
    except (urllib.error.URLError, OSError, ValueError):
        pass  # some portals skip this step

    data = _stalker_call(
        portal,
        "type=itv&action=get_all_channels&JsHttpRequest=1-xml",
        mac, token,
    )
    genres = {}
    try:
        g = _stalker_call(portal, "type=itv&action=get_genres&JsHttpRequest=1-xml",
                          mac, token)
        for item in (g or {}).get("data", []) or []:
            genres[str(item.get("id"))] = item.get("title") or "Genel"
    except (urllib.error.URLError, OSError, ValueError, AttributeError):
        pass

    channels = []
    for item in (data or {}).get("data", []) or []:
        cmd = (item.get("cmd") or "").strip()
        # `cmd` is prefixed with a player command such as "ffmpeg " or "auto ".
        if " " in cmd:
            cmd = cmd.split(" ", 1)[1]
        if not cmd.startswith(("http://", "https://", "rtmp://", "rtsp://")):
            continue
        channels.append(normalize_channel({
            "name": item.get("name") or "Unnamed",
            "url": cmd,
            "tvg-logo": item.get("logo") or item.get("tv_icon") or "",
            "group-title": genres.get(str(item.get("tv_genre_id")), "Genel"),
            "source": "MAC / Portal",
            # Streams are tied to the MAC session, so they must be fetched
            # through the server with these headers.
            "headers": {
                "User-Agent": STB_UA,
                "Cookie": _stalker_headers(mac, token)["Cookie"],
            },
        }))
    return channels, token


def _open_browser(url: str) -> None:
    import webbrowser

    def _go():
        time.sleep(0.8)
        try:
            webbrowser.open(url)
        except Exception:  # noqa: BLE001
            pass

    threading.Thread(target=_go, daemon=True).start()


def main() -> None:
    ap = argparse.ArgumentParser(description="IPTV Web Player server")
    ap.add_argument("-p", "--port", type=int,
                    default=int(os.environ.get("PORT", 8000)))
    ap.add_argument("-H", "--host", default=os.environ.get("HOST", "127.0.0.1"))
    ap.add_argument("--open", action="store_true",
                    help="open the player in the default browser on startup")
    args = ap.parse_args()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    # Pick a free port if the requested one is busy.
    httpd = None
    last_err = None
    for port in range(args.port, args.port + 20):
        try:
            httpd = ThreadingHTTPServer((args.host, port), Handler)
            args.port = port
            break
        except OSError as exc:
            last_err = exc
    if httpd is None:
        raise SystemExit(f"Could not bind a port: {last_err}")

    url = f"http://127.0.0.1:{args.port}/"
    print(f"IPTV player running at {url}")
    print("Press Ctrl+C to stop.")
    if args.open or os.environ.get("IPTV_OPEN"):
        _open_browser(url)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
        httpd.shutdown()


if __name__ == "__main__":
    main()
