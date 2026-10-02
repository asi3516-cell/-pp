package com.openhands.iptvplayer;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
    private final Map<String, String> mime = new HashMap<>();

    public LocalServer(AssetManager assets, int port) {
        super("127.0.0.1", port);
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
            if (uri.equals("/api/stalker")) return stalker(q);
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
                orDefault(q.get("u"), "Mozilla/5.0 (Android) IPTVPlayer"));
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

    private Response stalker(Map<String, String> q) throws Exception {
        JSONObject res = Stalker.channels(q.get("portal"), q.get("mac"));
        return json(res.toString());
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
