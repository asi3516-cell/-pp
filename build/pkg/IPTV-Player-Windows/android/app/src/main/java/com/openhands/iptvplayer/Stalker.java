package com.openhands.iptvplayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Minimal MAG/Stalker handshake, mirroring server.py's stalker_channels(). */
final class Stalker {

    private static final String STB_UA =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";

    private Stalker() { }

    static JSONObject channels(String portal, String mac) throws Exception {
        JSONObject err = new JSONObject();
        if (portal == null || mac == null || portal.isEmpty() || mac.isEmpty()) {
            err.put("error", "portal ve MAC adresi gerekli");
            return err;
        }
        JSONObject hs = call(portal, "type=stb&action=handshake&token=&prehash=0&JsHttpRequest=1-xml", mac, "");
        String token = hs.optString("token", "");
        if (token.isEmpty()) {
            err.put("error", "Portal el sıkışmayı reddetti (MAC yetkili olmayabilir)");
            return err;
        }
        try {
            call(portal, "type=stb&action=get_profile&hd=1&num_banks=2&stb_type=MAG250"
                    + "&client_type=STB&image_version=218&video_out=hdmi&hw_version=1.7-BD-00"
                    + "&not_valid_token=0&auth_second_step=1", mac, token);
        } catch (Exception ignored) { }

        JSONObject data = call(portal, "type=itv&action=get_all_channels&JsHttpRequest=1-xml", mac, token);
        JSONArray list = data.optJSONArray("data");
        JSONArray out = new JSONArray();
        if (list == null) {
            err.put("error", "kanal listesi alınamadı");
            return err;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            String cmd = item.optString("cmd", "").trim();
            int sp = cmd.indexOf(' ');
            if (sp > 0) cmd = cmd.substring(sp + 1);
            if (!(cmd.startsWith("http://") || cmd.startsWith("https://")
                    || cmd.startsWith("rtmp://") || cmd.startsWith("rtsp://"))) continue;
            JSONObject ch = new JSONObject();
            ch.put("name", item.optString("name", "Unnamed"));
            ch.put("url", cmd);
            ch.put("logo", item.optString("logo", item.optString("tv_icon", "")));
            ch.put("group", "MAC / Portal");
            ch.put("source", "MAC / Portal");
            JSONObject headers = new JSONObject();
            headers.put("User-Agent", STB_UA);
            headers.put("Cookie", cookie(mac, token));
            ch.put("headers", headers);
            JSONArray urls = new JSONArray();
            JSONObject u = new JSONObject();
            u.put("url", cmd);
            u.put("label", "MAC / Portal");
            urls.put(u);
            ch.put("urls", urls);
            out.put(ch);
        }
        JSONObject res = new JSONObject();
        res.put("count", out.length());
        res.put("channels", out);
        return res;
    }

    private static JSONObject call(String portal, String action, String mac, String token)
            throws Exception {
        String base = portal.endsWith("/") ? portal : portal + "/";
        String urlStr = base + "server/load.php?" + action;
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", STB_UA);
        conn.setRequestProperty("Cookie", cookie(mac, token));
        conn.setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi");
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        byte[] buf = new byte[8192];
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new JSONObject(bos.toString("UTF-8")).optJSONObject("js")
                != null ? new JSONObject(bos.toString("UTF-8")).getJSONObject("js")
                : new JSONObject(bos.toString("UTF-8"));
    }

    private static String cookie(String mac, String token) throws Exception {
        return "mac=" + URLEncoder.encode(mac, "UTF-8")
                + "; stb_lang=en; timezone=Europe/Istanbul"
                + (token.isEmpty() ? "" : "; token=" + URLEncoder.encode(token, "UTF-8"));
    }
}
