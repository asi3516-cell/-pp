#!/usr/bin/env python3
"""hh Web Player server.

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
from concurrent.futures import ThreadPoolExecutor
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


def _read_version(base: Path) -> str:
    """The single stamp written by VERSION; empty if the file is missing."""
    try:
        return (base / "VERSION").read_text("utf-8").strip().splitlines()[0]
    except (OSError, IndexError):
        return ""


ROOT = _app_root()
HH_VERSION = _read_version(ROOT)
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
    # favoritesByList keeps a separate favorite list for every source ("Canlı
    # TV", "Radyo", each portal), so switching lists also switches favorites.
    return {"playlists": [], "favorites": [], "favoritesByList": {},
            "recent": [], "overrides": {}, "settings": {}}


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
# Probe result cache
# --------------------------------------------------------------------------
# The inspect dialog re-checks the same address every time a channel is opened.
# Caching the result for an hour keeps a slow or dead mirror from being probed
# over and over. The cache holds non-2xx answers too, so a blocked mirror is
# only discovered once per TTL.
PROBE_TTL = 3600
PROBE_CACHE_FILE = DATA_DIR / "probe-cache.json"
_probe_lock = threading.Lock()
_probe_cache: dict | None = None


def _load_probe_cache() -> dict:
    global _probe_cache
    if _probe_cache is not None:
        return _probe_cache
    cache: dict = {}
    try:
        raw = json.loads(PROBE_CACHE_FILE.read_text("utf-8"))
    except (OSError, ValueError):
        raw = {}
    if isinstance(raw, dict) and isinstance(raw.get("entries"), dict):
        for url, item in raw["entries"].items():
            if isinstance(item, dict) and isinstance(item.get("at"), (int, float)):
                cache[url] = item
    _probe_cache = cache
    return cache


def _probe_cache_get(url: str) -> dict | None:
    with _probe_lock:
        cache = _load_probe_cache()
        item = cache.get(url)
        if not item:
            return None
        if time.time() - item.get("at", 0) > PROBE_TTL:
            cache.pop(url, None)
            return None
        return item.get("result")


def _probe_cache_put(url: str, result: dict) -> None:
    with _probe_lock:
        cache = _load_probe_cache()
        cache[url] = {"at": time.time(), "result": result}
        # Keep the file bounded; the dict preserves insertion order.
        while len(cache) > 2000:
            cache.pop(next(iter(cache)))
        try:
            DATA_DIR.mkdir(parents=True, exist_ok=True)
            tmp = PROBE_CACHE_FILE.with_suffix(".tmp")
            tmp.write_text(json.dumps({"entries": cache}, ensure_ascii=False),
                           "utf-8")
            tmp.replace(PROBE_CACHE_FILE)
        except OSError:
            pass


# --------------------------------------------------------------------------
# Upstream fetching helpers
# --------------------------------------------------------------------------
def fetch(url: str, headers: dict | None = None, timeout: int = DEFAULT_TIMEOUT,
          max_bytes: int = 0):
    """Fetch an upstream URL and return (status, headers, body-bytes).

    [max_bytes] caps the body so a probe of a large media file reads only the
    header bytes it needs instead of the whole stream.
    """
    req_headers = {
        "User-Agent": UA,
        "Accept": "*/*",
        "Accept-Encoding": "gzip, identity",
    }
    if headers:
        req_headers.update({k: v for k, v in headers.items() if v})
    req = urllib.request.Request(url, headers=req_headers)
    with urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) as resp:
        raw = resp.read(max_bytes) if max_bytes else resp.read()
        hdrs = {k.lower(): v for k, v in resp.headers.items()}
        if hdrs.get("content-encoding") == "gzip" and raw[:2] == b"\x1f\x8b":
            raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read()
        return resp.status, hdrs, raw


def head_fetch(url: str, timeout: int = DEFAULT_TIMEOUT):
    """HEAD an upstream URL and return (status, headers) without the body.

    Probing uses this first: a healthy mirror is identified from the headers
    alone, and a host that cannot even answer a HEAD is treated as dead.
    """
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "*/*",
    }, method="HEAD")
    with urllib.request.urlopen(req, timeout=timeout, context=_SSL_CTX) as resp:
        return resp.status, {k.lower(): v for k, v in resp.headers.items()}


M3U8_RE = re.compile(r"\.m3u8(\?|$)", re.IGNORECASE)
URI_ATTR_RE = re.compile(r'URI="([^"]+)"')


PLS_FILE_RE = re.compile(r"\.pls(\?|$)", re.IGNORECASE)
AUDIO_TYPES = {
    "mp3": "audio/mpeg", "aac": "audio/aac", "aacp": "audio/aacp",
    "ogg": "audio/ogg", "oga": "audio/ogg", "opus": "audio/ogg",
    "m4a": "audio/mp4", "mp4": "video/mp4", "wav": "audio/wav",
    "flac": "audio/flac", "ts": "video/mp2t", "m3u8": "application/vnd.apple.mpegurl",
}


def guess_stream_type(url: str) -> str:
    path = urllib.parse.urlparse(url).path
    ext = path.rsplit(".", 1)[-1].lower() if "." in path else ""
    return AUDIO_TYPES.get(ext, "audio/mpeg")


def resolve_stream(url: str, headers: dict) -> tuple[str, str]:
    """Follow a .pls / .m3u playlist to the real stream URL it points at.

    Radio-browser lists many stations as a small playlist file rather than the
    stream itself, so the player has to unwrap one level. Returns the resolved
    URL and a content type guess."""
    if not (PLS_FILE_RE.search(url) or M3U8_RE.search(url) or
            url.lower().split("?")[0].endswith((".m3u", ".m3u8", ".pls"))):
        return url, guess_stream_type(url)
    try:
        status, hdrs, raw = fetch(url, headers, timeout=DEFAULT_TIMEOUT)
    except (urllib.error.URLError, OSError):
        return url, guess_stream_type(url)
    text = raw.decode("utf-8", "replace")
    # HLS manifests are left to the browser's player, not unwrapped here.
    if raw[:7].upper() == b"#EXTM3U" and M3U8_RE.search(url):
        return url, "application/vnd.apple.mpegurl"
    candidates = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"(?i)file\d*\s*=\s*(.+)$", line)
        candidates.append((m.group(1) if m else line).strip())
    if not candidates:
        return url, guess_stream_type(url)
    target = urllib.parse.urljoin(url, candidates[0])
    return target, guess_stream_type(target)


def _icy_value(meta: bytes, key: str) -> str:
    """Pull one StreamTitle/StreamUrl value out of an ICY metadata block."""
    m = re.search((key + r"='([^']*)'").encode("ascii"), meta)
    if m:
        return m.group(1).decode("utf-8", "replace").strip()
    m = re.search((key + r'="([^"]*)"').encode("ascii"), meta)
    return m.group(1).decode("utf-8", "replace").strip() if m else ""


def fetch_now_playing(url: str, headers: dict) -> dict:
    """Read the current song title from a Shoutcast/Icecast stream.

    These servers interleave a metadata block into the audio every
    `icy-metaint` bytes, but only when the client asks for it with the
    `Icy-MetaData: 1` header. We read just the first block and hang up."""
    try:
        resolved, _ = resolve_stream(url, headers)
    except Exception:
        resolved = url
    req = urllib.request.Request(resolved, headers=dict(headers, **{"Icy-MetaData": "1"}))
    try:
        resp = urllib.request.urlopen(req, timeout=8, context=_SSL_CTX)
    except (urllib.error.URLError, OSError):
        return {}
    try:
        metaint = int(resp.headers.get("icy-metaint") or 0)
        if metaint <= 0:
            return {}
        buf = b""
        # Read past the audio bytes up to the length byte of the metadata.
        while len(buf) < metaint + 1:
            chunk = resp.read(metaint + 1 - len(buf))
            if not chunk:
                return {}
            buf += chunk
        length = buf[metaint] * 16
        meta = b""
        while len(meta) < length:
            chunk = resp.read(length - len(meta))
            if not chunk:
                break
            meta += chunk
        if not meta:
            return {}
        return {
            "title": _icy_value(meta, "StreamTitle"),
            "station": _icy_value(meta, "StreamUrl"),
        }
    except (OSError, ValueError):
        return {}
    finally:
        try:
            resp.close()
        except OSError:
            pass


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
    server_version = "hh/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the console readable
        if os.environ.get("HH_VERBOSE"):
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    # -- helpers -----------------------------------------------------------
    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None,
              head_only: bool = False, gz_ready: bool = False) -> None:
        # The channel list is tens of megabytes; gzip cuts it by ~85% so the
        # browser can parse it instead of stalling on the download. `gz_ready`
        # marks a body that was already compressed by the caller.
        wants_gz = "gzip" in (self.headers.get("Accept-Encoding") or "")
        gz = gz_ready and wants_gz
        if gz_ready and not wants_gz:
            body = gzip.decompress(body)
        elif not gz_ready and len(body) > 8192 and wants_gz:
            body = gzip.compress(body, 6)
            gz = True
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        if gz:
            self.send_header("Content-Encoding", "gzip")
            self.send_header("Vary", "Accept-Encoding")
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
            for key in ("playlists", "favorites", "favoritesByList",
                        "recent", "overrides", "settings"):
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
        if path == "/api/version":
            return self._json(200, {"version": HH_VERSION})
        if path == "/api/now":
            return self._api_now(query)
        if path == "/api/store":
            return self._json(200, load_store())
        if path == "/api/channels":
            return self._serve_channels(head_only)
        if path == "/api/proxy":
            return self._api_proxy(query, head_only)
        if path == "/api/stream":
            return self._api_stream(query)
        if path == "/api/fetch":
            return self._api_fetch(query, head_only)
        if path == "/api/probe":
            return self._api_probe(query, head_only)
        if path == "/download/player":
            return self._download_player(head_only)
        if path == "/download/apk":
            return self._download_apk(head_only)
        if path == "/download/apk-universal":
            return self._download_apk(head_only, "TV-Player-universal.apk")
        if path == "/download/apk-arm":
            return self._download_apk(head_only, "TV-Player-armeabi-v7a.apk")
        if path == "/download/all":
            return self._download_all(head_only)
        if path == "/download/windows-rar":
            return self._send_file(
                ROOT / "build" / "pkg" / "hh-Windows.rar",
                "hh-Windows.rar", "application/vnd.rar", head_only)
        if path == "/download/windows-exe":
            return self._send_file(
                ROOT / "build" / "pkg" / "hh.exe",
                "hh.exe", "application/vnd.microsoft.portable-executable", head_only)
        if path == "/download/source-zip":
            return self._send_file(
                ROOT / "build" / "pkg" / "hh-Kaynak.zip",
                "hh-Kaynak.zip", "application/zip", head_only)
        if path == "/download/source-rar":
            return self._send_file(
                ROOT / "build" / "pkg" / "hh-Kaynak.rar",
                "hh-Kaynak.rar", "application/vnd.rar", head_only)
        if path == "/download/source-7z":
            return self._send_file(
                ROOT / "build" / "pkg" / "hh-Kaynak.7z",
                "hh-Kaynak.7z", "application/x-7z-compressed", head_only)
        if path == "/channels.m3u":
            return self._download_channels(head_only)
        return self._static(path, head_only)

    def _download_channels(self, head_only: bool) -> None:
        """Serve the merged Turkish list as a plain M3U, so it can be opened in
        any player (Android, VLC, TV boxes). Every stream alternative becomes
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
                title = name if i == 0 else f"{name} {i + 1}"
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
            "build/tv_player.spec", "build/requirements-build.txt",
            ".github/workflows/build.yml",
        ]
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            for name in files:
                f = ROOT / name
                if f.exists():
                    zf.write(f, f"hh/{name}")
            # The Gradle build files sit at android/, not under src/main, so
            # they are listed explicitly for a self-contained project.
            for name in ("build.gradle", "settings.gradle", "gradle.properties"):
                f = ROOT / "android" / name
                if f.exists():
                    zf.write(f, f"hh/android/{name}")
            for folder in ("static", "data", "android/app/src/main"):
                for f in sorted((ROOT / folder).rglob("*")):
                    if not f.is_file() or f.name.endswith((".class", ".dex")):
                        continue
                    # android/app/src/main/assets is regenerated by the
                    # syncAssets task from static/ and data/, so it is skipped
                    # to keep the archive small.
                    if folder.endswith("src/main") and "assets" in f.parts:
                        continue
                    zf.write(f, f"hh/{f.relative_to(ROOT)}")
            for extra in (ROOT / "android" / "libs").glob("*.jar"):
                zf.write(extra, f"hh/{extra.relative_to(ROOT)}")
            binary = ROOT / "dist" / "hh"
            if binary.exists():
                zf.write(binary, "hh/dist/hh")
            apk = ROOT / "dist" / "TV-Player.apk"
            if apk.exists():
                zf.write(apk, "hh/dist/TV-Player.apk")
        body = buf.getvalue()
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Disposition",
                         'attachment; filename="hh.zip"')
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

    def _download_apk(self, head_only: bool, name: str = "TV-Player.apk") -> None:
        """Serve a built Android APK, if it has been assembled."""
        apk = ROOT / "dist" / name
        if not apk.exists() and name == "TV-Player.apk":
            apk = ROOT / "dist" / "hh.apk"
        if not apk.exists():
            return self._send(404, b"apk not built", "text/plain", head_only=head_only)
        self._send_file(apk, name,
                        "application/vnd.android.package-archive", head_only)

    def _download_player(self, head_only: bool) -> None:
        """Serve the built portable binary (source checkout only)."""
        binary = ROOT / "dist" / "hh"
        if not binary.exists():
            return self._send(404, b"not built", "text/plain", head_only=head_only)
        size = binary.stat().st_size
        self.send_response(200)
        self._cors()
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition",
                         'attachment; filename="hh"')
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
    def _serve_channels(self, head_only: bool) -> None:
        """Serve the bundled list on its own, from a gzipped cache.

        Remote playlists are fetched on demand by the Xtream/Stalker endpoints,
        so this endpoint must stay fast: it is read on every page load and a
        fresh fetch of a 58 MB payload takes ~16 s."""
        cache = DATA_DIR / "channels.cache.json.gz"
        src = BUNDLED_DATA_DIR / "channels.json"
        try:
            if not cache.exists() or cache.stat().st_mtime < src.stat().st_mtime:
                body = json.dumps(
                    {"channels": json.loads(src.read_text("utf-8"))},
                    ensure_ascii=False).encode("utf-8")
                tmp = cache.with_suffix(".tmp")
                tmp.write_bytes(gzip.compress(body, 6))
                tmp.replace(cache)
            body = cache.read_bytes()
        except (OSError, ValueError):
            self._json(500, {"error": "bundled list unavailable"})
            return
        self._send(200, body, "application/json; charset=utf-8",
                   head_only=head_only, gz_ready=True)

    def _api_now(self, query: dict) -> None:
        """Current song title for a radio stream, read from ICY metadata."""
        url = (query.get("url") or [""])[0]
        if not re.match(r"^https?://", url, re.I):
            return self._json(400, {"error": "bad url"})
        headers = {"Accept": "*/*"}
        for src, dst in (("u", "User-Agent"), ("h", "Referer")):
            val = (query.get(src) or [None])[0]
            if val:
                headers[dst] = val
        return self._json(200, fetch_now_playing(url, headers))

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
        urls = urls[:12]

        def check(u: str) -> dict:
            cached = _probe_cache_get(u)
            if cached is not None:
                return dict(cached)
            entry = {"url": u, "ok": False, "status": 0, "error": "",
                     "kind": "", "note": ""}
            try:
                # HEAD first: it settles the common cases (dead host, 403, 404,
                # a confident content-type) without transferring the body, and
                # is far cheaper than a full GET on a dead mirror.
                status = hdrs = None
                try:
                    status, hdrs = head_fetch(u, timeout=6)
                except urllib.error.HTTPError as exc:
                    status, hdrs = exc.code, {}
                except (urllib.error.URLError, OSError):
                    status, hdrs = None, None
                ctype = ((hdrs or {}).get("content-type") or "").lower()
                # A confident *media* type settles the check from the headers
                # alone, so the body is never transferred. Playlists still get
                # their body read: it is small and the variant count is worth
                # showing.
                if "mpegts" in ctype or "mp2t" in ctype or "video" in ctype:
                    entry["status"] = status
                    entry["kind"] = "media"
                    entry["ok"] = status == 200
                else:
                    # Unknown type, or the host refused HEAD: read the body. A
                    # still-unsupported request is retried as GET, because some
                    # servers answer 405/501 to HEAD but serve GET fine.
                    raw_status, raw_hdrs, raw = None, {}, b""
                    try:
                        raw_status, raw_hdrs, raw = fetch(u, timeout=6, max_bytes=32768)
                    except urllib.error.HTTPError as exc:
                        raw_status, raw_hdrs, raw = exc.code, {}, b""
                    except (urllib.error.URLError, OSError) as exc:
                        if status in (None, 405, 501):
                            raise
                        entry["status"] = status
                        entry["error"] = str(exc)[:120]
                        _probe_cache_put(u, entry)
                        return entry
                    status = raw_status or status
                    ctype = ((raw_hdrs or {}).get("content-type") or ctype).lower()
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
            _probe_cache_put(u, entry)
            return entry

        # Check the mirrors in parallel: done serially, a handful of dead
        # addresses each burn the full 12s timeout and the dialog spins for
        # minutes. Results keep the original order.
        with ThreadPoolExecutor(max_workers=min(8, len(urls))) as ex:
            results = list(ex.map(check, urls))
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
            # Only forward a cookie when there is one; an empty "&c=" would be
            # glued onto every child URL and turn it into a 404 upstream.
            cookie = fwd_headers.get("Cookie")
            if cookie:
                extra_qs += "&c=" + urllib.parse.quote(cookie, safe="")
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

    # -- streaming proxy ---------------------------------------------------
    def _api_stream(self, query: dict) -> None:
        """Relay a continuous audio/video stream to the browser.

        The page is served over https, but many radio stations (and a few TV
        feeds) only offer http, which the browser blocks as mixed content. The
        server fetches the upstream itself and pipes the bytes through, so the
        stream plays from a same-origin https URL. Playlist files (.pls / .m3u)
        are resolved to the first real stream inside them."""
        url = (query.get("url") or [""])[0]
        if not url:
            return self._json(400, {"error": "missing url"})
        if not re.match(r"^https?://", url, re.I):
            return self._json(400, {"error": "bad url"})

        headers = {"Accept": "*/*"}
        for src, dst in (("u", "User-Agent"), ("h", "Referer"), ("o", "Origin")):
            val = (query.get(src) or [None])[0]
            if val:
                headers[dst] = val

        try:
            resolved, ctype = resolve_stream(url, headers)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            return self._json(502, {"error": str(exc)})

        try:
            req = urllib.request.Request(resolved, headers=headers)
            upstream = urllib.request.urlopen(
                req, timeout=DEFAULT_TIMEOUT, context=_SSL_CTX)
        except (urllib.error.URLError, OSError) as exc:
            return self._json(502, {"error": str(exc)})

        self.send_response(200)
        self.send_header("Content-Type", ctype or "audio/mpeg")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self._cors()
        self.end_headers()
        self.close_connection = True
        try:
            while True:
                chunk = upstream.read(32768)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            try:
                upstream.close()
            except OSError:
                pass

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
    ap = argparse.ArgumentParser(description="hh Web Player server")
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
    print(f"hh player running at {url}")
    print("Press Ctrl+C to stop.")
    if args.open or os.environ.get("HH_OPEN") or getattr(sys, "frozen", False):
        _open_browser(url)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
        httpd.shutdown()


if __name__ == "__main__":
    main()
