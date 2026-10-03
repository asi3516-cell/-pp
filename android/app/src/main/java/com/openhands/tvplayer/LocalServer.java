package com.openhands.tvplayer;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Tiny loopback server inside the app: serves the bundled web player from
 * assets and exposes the same JSON API the desktop build has, so the existing
 * front-end works unchanged. The /api/proxy route is what makes cross-origin
 * HLS playback possible from a WebView.
 */
public class LocalServer extends NanoHTTPD {

    private final AssetManager assets;
    private final Context context;
    private final Map<String, String> mime = new HashMap<>();

    public LocalServer(Context context, AssetManager assets, int port) {
        super("127.0.0.1", port);
        this.context = context;
        this.assets = assets;
        mime.put("html", "text/html; charset=utf-8");
        mime.put("js", "application/javascript; charset=utf-8");
        mime.put("css", "text/css; charset=utf-8");
        mime.put("json", "application/json; charset=utf-8");
        mime.put("svg", "image/svg+xml");
        mime.put("webmanifest", "application/manifest+json");
        mime.put("m3u", "audio/x-mpegurl; charset=utf-8");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> q = decode(session.getQueryParameterString());
        try {
            if (uri.equals("/api/channels")) return channels();
            if (uri.equals("/api/proxy")) return proxy(q);
            if (uri.equals("/api/fetch")) return fetch(q);
            if (uri.equals("/api/now")) return now(q);
            if (uri.equals("/api/store")) {
                if ("POST".equalsIgnoreCase(session.getMethod().name())) {
                    return storeSave(session);
                }
                return json(storeLoad());
            }
            if (uri.equals("/api/stream")) return stream(q);
            if (uri.equals("/channels.m3u")) return m3u();
            return asset(uri);
        } catch (Exception e) {
            return text(Response.Status.INTERNAL_ERROR, "hata: " + e.getMessage());
        }
    }

    private Response channels() throws Exception {
        JSONArray arr = new JSONArray(readAsset("channels.json"));
        JSONObject out = new JSONObject();
        out.put("count", arr.length());
        out.put("channels", arr);
        return json(out.toString());
    }

    private Response m3u() throws Exception {
        JSONArray arr = new JSONArray(readAsset("channels.json"));
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String name = c.optString("name", "Kanal");
            String logo = c.optString("logo", "");
            String group = c.optString("group", "Genel");
            JSONArray urls = c.optJSONArray("urls");
            if (urls == null || urls.length() == 0) {
                sb.append("#EXTINF:-1 tvg-logo=\"").append(logo)
                  .append("\" group-title=\"").append(group).append("\",")
                  .append(name).append("\n").append(c.optString("url")).append("\n");
                continue;
            }
            for (int j = 0; j < urls.length(); j++) {
                JSONObject u = urls.getJSONObject(j);
                String title = j == 0 ? name : name + " (yedek: " + u.optString("label") + ")";
                sb.append("#EXTINF:-1 tvg-logo=\"").append(logo)
                  .append("\" group-title=\"").append(group).append("\",")
                  .append(title).append("\n").append(u.optString("url")).append("\n");
            }
        }
        return newFixedLengthResponse(Response.Status.OK, mime.get("m3u"), sb.toString());
    }

    private Response proxy(Map<String, String> q) throws Exception {
        String target = q.get("url");
        if (target == null) return text(Response.Status.BAD_REQUEST, "url yok");

        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent",
                orDefault(q.get("u"), "Mozilla/5.0 (Android) TV Player"));
        if (q.get("h") != null) conn.setRequestProperty("Referer", q.get("h"));
        if (q.get("o") != null) conn.setRequestProperty("Origin", q.get("o"));
        if (q.get("c") != null) conn.setRequestProperty("Cookie", q.get("c"));
        String range = q.get("r");
        if (range != null) conn.setRequestProperty("Range", range);

        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
        }
        String ctype = orDefault(conn.getContentType(), "application/octet-stream");
        byte[] raw = bos.toByteArray();

        if (ctype.contains("mpegurl") || ctype.contains("vnd.apple")
                || target.split("\\?")[0].endsWith(".m3u8")) {
            String text = new String(raw, "UTF-8");
            StringBuilder sb = new StringBuilder();
            String carry = "&u=" + enc(q.get("u")) + "&c=" + enc(q.get("c"))
                    + "&h=" + enc(q.get("h")) + "&o=" + enc(q.get("o"));
            java.util.regex.Pattern uriAttr =
                    java.util.regex.Pattern.compile("URI=\"([^\"]+)\"");
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty()) { sb.append(line).append("\n"); continue; }
                if (t.startsWith("#")) {
                    java.util.regex.Matcher m = uriAttr.matcher(line);
                    StringBuilder rep = new StringBuilder();
                    while (m.find()) {
                        String abs = resolve(target, m.group(1));
                        m.appendReplacement(rep, "URI=\"/api/proxy?url="
                                + java.util.regex.Matcher.quoteReplacement(enc(abs)) + "\"");
                    }
                    m.appendTail(rep);
                    sb.append(rep).append("\n");
                } else {
                    sb.append("/api/proxy?url=").append(enc(resolve(target, t)))
                      .append(carry).append("\n");
                }
            }
            return newFixedLengthResponse(Response.Status.OK,
                    "application/vnd.apple.mpegurl", sb.toString());
        }
        return newFixedLengthResponse(Response.Status.lookup(status), ctype,
                new java.io.ByteArrayInputStream(raw), raw.length);
    }

    /**
     * Relay a live audio/video stream through the app itself.
     *
     * The player is served from http://127.0.0.1, but most radio stations and
     * several TV feeds are plain http or reject the WebView's origin. Fetching
     * the bytes here and piping them back as a same-origin response removes
     * both problems, and unwraps .pls/.m3u pointers to the real stream. */
    private Response stream(Map<String, String> q) throws Exception {
        String target = q.get("url");
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            return text(Response.Status.BAD_REQUEST, "url yok");
        }
        String ua = orDefault(q.get("u"), "Mozilla/5.0 (Android) TV Player");
        String resolved = resolveStream(target, ua, q.get("h"), q.get("o"));
        String ctype = guessStreamType(resolved);

        HttpURLConnection conn = (HttpURLConnection) new URL(resolved).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Icy-MetaData", "0");
        if (q.get("h") != null) conn.setRequestProperty("Referer", q.get("h"));
        if (q.get("o") != null) conn.setRequestProperty("Origin", q.get("o"));
        String upstreamType = conn.getContentType();
        if (upstreamType != null && upstreamType.contains("mpegurl")) {
            ctype = "application/vnd.apple.mpegurl";
        }
        InputStream in = conn.getInputStream();
        Response res = newChunkedResponse(Response.Status.OK, ctype, in);
        res.addHeader("Cache-Control", "no-cache");
        res.addHeader("Access-Control-Allow-Origin", "*");
        return res;
    }

    private String resolveStream(String url, String ua, String referer, String origin) {
        String path = url.split("\\?")[0].toLowerCase();
        if (!(path.endsWith(".pls") || path.endsWith(".m3u") || path.endsWith(".m3u8"))) {
            return url;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", ua);
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (origin != null) c.setRequestProperty("Origin", origin);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && bos.size() < 65536) bos.write(buf, 0, n);
            in.close();
            String text = bos.toString("UTF-8");
            if (text.trim().toUpperCase().startsWith("#EXTM3U") && path.endsWith(".m3u8")) {
                return url;
            }
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.toLowerCase().startsWith("file")) {
                    int eq = t.indexOf('=');
                    if (eq > 0) t = t.substring(eq + 1).trim();
                }
                return resolve(url, t);
            }
        } catch (Exception ignored) { }
        return url;
    }

    private static String guessStreamType(String url) {
        String path = url.split("\\?")[0].toLowerCase();
        if (path.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".aac") || path.endsWith(".m4a")) return "audio/aac";
        if (path.endsWith(".ogg") || path.endsWith(".oga")) return "audio/ogg";
        if (path.endsWith(".opus")) return "audio/opus";
        if (path.endsWith(".mp4") || path.endsWith(".m4v")) return "video/mp4";
        return "audio/mpeg";
    }

    private Response fetch(Map<String, String> q) throws Exception {
        String target = q.get("url");
        if (target == null) return text(Response.Status.BAD_REQUEST, "url yok");
        HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) TV Player");
        int status = conn.getResponseCode();
        InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
        }
        String ctype = orDefault(conn.getContentType(), "text/plain; charset=utf-8");
        byte[] raw = bos.toByteArray();
        return newFixedLengthResponse(Response.Status.lookup(status), ctype,
                new java.io.ByteArrayInputStream(raw), raw.length);
    }

    private File storeFile() {
        return new File(context.getFilesDir(), "store.json");
    }

    private String storeLoad() {
        try {
            File f = storeFile();
            if (!f.exists()) return "{}";
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String body = bos.toString("UTF-8");
            return body.isEmpty() ? "{}" : body;
        } catch (Exception e) {
            return "{}";
        }
    }

    private Response storeSave(IHTTPSession session) throws Exception {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String payload = body.get("postData");
        if (payload == null || payload.isEmpty()) payload = "{}";
        try {
            new JSONObject(payload);
        } catch (Exception e) {
            return text(Response.Status.BAD_REQUEST, "gecersiz govde");
        }
        FileOutputStream out = new FileOutputStream(storeFile());
        out.write(payload.getBytes("UTF-8"));
        out.close();
        return json(storeLoad());
    }

    /** Current song title for a radio stream, read from the ICY metadata
     * block that Shoutcast/Icecast servers interleave into the audio. */
    private Response now(Map<String, String> q) throws Exception {
        String target = q.get("url");
        if (target == null) return text(Response.Status.BAD_REQUEST, "url yok");
        String ua = orDefault(q.get("u"), "Mozilla/5.0 (Android) TV Player");
        String resolved = resolveStream(target, ua, q.get("h"), q.get("o"));
        String title = "";
        String station = "";
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(resolved).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Icy-MetaData", "1");
            int metaint = 0;
            try { metaint = Integer.parseInt(orDefault(conn.getHeaderField("icy-metaint"), "0")); }
            catch (NumberFormatException ignored) { }
            if (metaint > 0) {
                in = conn.getInputStream();
                byte[] buf = new byte[metaint + 1];
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) break;
                    read += n;
                }
                if (read > metaint) {
                    int len = (buf[metaint] & 0xFF) * 16;
                    if (len > 0) {
                        byte[] meta = new byte[len];
                        int got = 0;
                        while (got < len) {
                            int n = in.read(meta, got, len - got);
                            if (n < 0) break;
                            got += n;
                        }
                        String block = new String(meta, 0, got, "ISO-8859-1");
                        title = icyValue(block, "StreamTitle");
                        station = icyValue(block, "StreamUrl");
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
        JSONObject out = new JSONObject();
        out.put("title", title);
        out.put("station", station);
        return json(out.toString());
    }

    private static String icyValue(String block, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + "='([^']*)'").matcher(block);
        if (m.find()) return m.group(1).trim();
        m = java.util.regex.Pattern.compile(key + "=\"([^\"]*)\"").matcher(block);
        return m.find() ? m.group(1).trim() : "";
    }

    private Response asset(String uri) throws Exception {
        String rel = uri.equals("/") ? "index.html" : URLDecoder.decode(uri.substring(1), "UTF-8");
        try {
            byte[] data = readAsset(rel);
            return newFixedLengthResponse(Response.Status.OK, mime(rel),
                    new java.io.ByteArrayInputStream(data), data.length);
        } catch (Exception e) {
            byte[] data = readAsset("index.html");
            return newFixedLengthResponse(Response.Status.OK, mime.get("html"),
                    new java.io.ByteArrayInputStream(data), data.length);
        }
    }

    private Response json(String body) {
        return newFixedLengthResponse(Response.Status.OK, mime.get("json"), body);
    }

    private Response text(Response.Status s, String body) {
        return newFixedLengthResponse(s, "text/plain; charset=utf-8", body);
    }

    private String mime(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        return orDefault(mime.get(ext), "application/octet-stream");
    }

    private byte[] readAsset(String name) throws Exception {
        InputStream in = assets.open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static Map<String, String> decode(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null) return out;
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            try {
                out.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                        URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static String enc(String s) {
        if (s == null) return "";
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static String resolve(String base, String ref) {
        try { return new URL(new URL(base), ref).toString(); }
        catch (Exception e) { return ref; }
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isEmpty() ? def : v;
    }
}
