/* TV Player - canlı TV ve radyo oynatıcı */
(function () {
  "use strict";

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  var state = {
    channels: [],
    filtered: [],
    favorites: [],
    favoritesByList: {},
    recent: [],
    recentsByList: {},
    playlists: [],
    overrides: {},
    deleted: {},
    added: [],
    epgUrl: "",
    epg: {},
    current: null,
    group: "all",
    tab: "all",
    kind: "all",
    source: "Canlı TV",
    sources: [],
    expanded: {},
    openGroup: null,
    loaded: { "Canlı TV": true },
    detail: null,
    detailSeason: null,
    detailPoster: "",
    query: "",
    queue: [],
    streamIdx: 0
  };

  var video = $("#video");
  var overlay = $("#overlay");
  var overlayMsg = $("#overlayMsg");
  var spinner = $("#spinner");
  var osd = $("#osd");
  var hls = null;
  var osdTimer = null;

  /* ----------------------------- storage ----------------------------- */
  // Playlists live on the server so they survive a reload and can be opened
  // from any device using the same address. localStorage is an offline cache.
  var LS_KEY = "tv.state.v1";
  // Bumped on every release; a mismatch between the shell and an older service
  // worker cache is what makes a box show yesterday's UI after an update.
  var APP_VERSION = window.HH_VERSION || "";

  function enforceVersion() {
    if (!APP_VERSION || !navigator.serviceWorker || !window.caches) return;
    try {
      if (localStorage.getItem("tv.version") === APP_VERSION) return;
      localStorage.setItem("tv.version", APP_VERSION);
    } catch (e) { return; }
    // Drop every cache this origin owns, then reload once so the fresh shell
    // is fetched instead of the stale one the service worker kept.
    caches.keys().then(function (keys) {
      return Promise.all(keys.map(function (k) { return caches.delete(k); }));
    }).then(function () {
      navigator.serviceWorker.getRegistrations().then(function (regs) {
        return Promise.all(regs.map(function (r) { return r.unregister(); }));
      }).then(function () {
        if (!sessionStorage.getItem("tv.reloaded")) {
          sessionStorage.setItem("tv.reloaded", "1");
          location.reload();
        }
      });
    }).catch(function () { /* offline: keep the cached shell */ });
  }

  function snapshot() {
    return {
      favorites: state.favorites,
      favoritesByList: state.favoritesByList,
      recent: state.recent,
      recentsByList: state.recentsByList,
      playlists: state.playlists,
      overrides: state.overrides,
      deleted: state.deleted,
      added: state.added,
      epgUrl: state.epgUrl,
      ui: { source: state.source, kind: state.kind }
    };
  }

  function applySnapshot(raw) {
    if (!raw || typeof raw !== "object") return false;
    state.favorites = raw.favorites || [];
    state.favoritesByList = raw.favoritesByList || {};
    state.recent = raw.recent || [];
    state.recentsByList = raw.recentsByList || {};
    state.playlists = raw.playlists || [];
    state.overrides = raw.overrides || {};
    state.deleted = raw.deleted || {};
    state.added = raw.added || [];
    state.epgUrl = raw.epgUrl || "";
    if (raw.ui) {
      if (raw.ui.source) state.source = raw.ui.source;
      if (raw.ui.kind) state.kind = raw.ui.kind;
    }
    return true;
  }

  function loadLocal() {
    try {
      return applySnapshot(JSON.parse(localStorage.getItem(LS_KEY) || "{}"));
    } catch (e) { return false; }
  }

  function saveLocal() {
    try { localStorage.setItem(LS_KEY, JSON.stringify(snapshot())); } catch (e) {}
  }

  function loadServerStore() {
    return api("/api/store").then(function (data) {
      var settings = data.settings || {};
      return applySnapshot({
        favorites: data.favorites,
        favoritesByList: data.favoritesByList,
        recent: data.recent,
        recentsByList: data.recentsByList,
        playlists: data.playlists,
        overrides: data.overrides,
        deleted: data.deleted,
        added: data.added,
        epgUrl: settings.epgUrl,
        ui: settings.ui
      });
    }).catch(function () { return false; });
  }

  var saveTimer = null;
  function persist() {
    saveLocal();
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      post("/api/store", JSON.stringify({
        favorites: state.favorites,
        favoritesByList: state.favoritesByList,
        recent: state.recent,
        recentsByList: state.recentsByList,
        playlists: state.playlists,
        overrides: state.overrides,
        deleted: state.deleted,
        added: state.added,
        settings: { epgUrl: state.epgUrl, ui: { source: state.source, kind: state.kind } }
      })).catch(function () { /* offline: localStorage still has it */ });
    }, 400);
  }

  /* ------------------------------ api -------------------------------- */
  // XHR instead of fetch: fetch() is missing on the old WebViews that ship
  // with many Android TV boxes, and a missing fetch made the whole boot fail
  // so no channel ever appeared.
  function request(method, path, body) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open(method, path, true);
      if (body) xhr.setRequestHeader("Content-Type", "application/json");
      xhr.onload = function () {
        if (xhr.status >= 200 && xhr.status < 300) {
          resolve({
            ok: true,
            status: xhr.status,
            text: function () { return Promise.resolve(xhr.responseText); },
            json: function () { return Promise.resolve(JSON.parse(xhr.responseText)); }
          });
        } else {
          reject(new Error("HTTP " + xhr.status));
        }
      };
      xhr.onerror = function () { reject(new Error("ağ hatası")); };
      xhr.ontimeout = function () { reject(new Error("zaman aşımı")); };
      try { xhr.send(body || null); } catch (e) { reject(e); }
    });
  }

  function get(path) { return request("GET", path); }
  function post(path, body) { return request("POST", path, body); }

  function api(path) {
    return get(path).then(function (r) { return r.json(); });
  }

  function proxied(url, extra) {
    var q = "?url=" + encodeURIComponent(url);
    if (extra) {
      ["h", "u", "o", "c"].forEach(function (k) {
        if (extra[k]) q += "&" + k + "=" + encodeURIComponent(extra[k]);
      });
    }
    return "/api/proxy" + q;
  }

  // Ask the server for the song a Shoutcast/Icecast stream is playing right
  // now. The server reads the stream's ICY metadata block; many stations
  // send no metadata at all, in which case the station name is kept.
  function pollNowPlaying() {
    if (!isRadioNow()) { icyTitle = ""; return; }
    var ch = state.current;
    var q = "/api/now?url=" + encodeURIComponent(ch.url);
    if (ch.headers && ch.headers["User-Agent"]) {
      q += "&u=" + encodeURIComponent(ch.headers["User-Agent"]);
    }
    api(q).then(function (data) {
      if (!isRadioNow() || state.current !== ch) return;
      var t = (data && data.title) || "";
      if (t !== icyTitle) { icyTitle = t; pushWidgetState(!video.paused); }
    }).catch(function () {});
  }

  // Build the proxy query from a channel's stored request headers (used by
  // MAC/Stalker portals, whose streams require the session UA and cookie).
  function proxyFor(ch, url) {
    var h = (ch && ch.headers) || {};
    return proxied(url, {
      u: h["User-Agent"],
      c: h["Cookie"],
      h: h["Referer"]
    });
  }

  /* --------------------------- M3U parser ---------------------------- */
  // Title after EXTINF attributes; attribute values may contain commas
  // (e.g. http-user-agent), so only unquoted commas delimit the name.
  function displayName(line) {
    var inQuote = false;
    var last = -1;
    for (var i = 0; i < line.length; i++) {
      var c = line.charAt(i);
      if (c === '"') inQuote = !inQuote;
      else if (c === "," && !inQuote) last = i;
    }
    return last >= 0 ? line.slice(last + 1).trim() : "";
  }

  function parseM3U(text, source) {
    var out = [];
    var info = null;
    var lines = text.split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (!line) continue;
      if (line.charAt(0) === "#") {
        if (line.indexOf("#EXTINF") === 0) {
          info = { source: source || "playlist" };
          var attrRe = /([\w-]+)="([^"]*)"/g;
          var m;
          while ((m = attrRe.exec(line))) info[m[1]] = m[2];
          info.name = displayName(line) || info["tvg-name"] || "Unnamed";
        }
        continue;
      }
      if (/^(https?|rtmp|rtsp):\/\//i.test(line)) {
        if (!info) info = { name: line, source: source || "playlist" };
        info.url = line;
        out.push(normalize(info));
        info = null;
      }
    }
    return out;
  }

  // Classify a stream as live TV, a movie or a series episode. Xtream/XUI
  // portals separate them by URL path (/movie/, /series/), everything else is
  // treated as live television.
  // contentKind runs a handful of regexes per channel and the tree scans the
  // list several times per render, so memoise per channel object and recompute
  // only when the fields it reads actually change.
  var kindCache = new WeakMap();
  function contentKind(ch) {
    var u = (ch.url || "").toLowerCase();
    var sig = u + "\u0000" + (ch.kind || "");
    var hit = kindCache.get(ch);
    if (hit && hit.sig === sig) return hit.kind;
    var kind = computeContentKind(ch, u);
    kindCache.set(ch, { sig: sig, kind: kind });
    return kind;
  }

  function computeContentKind(ch, u) {
    // A channel can carry several iptv-org group titles joined by semicolons;
    // the first one is the primary category, so the tree shows one clear name.
    var group = (ch.group || "Genel").split(";")[0].trim() || "Genel";
    ch.group = group;
    if (ch.kind === "radio") return "radio";
    if (ch.kind === "vod" || ch.kind === "series") return ch.kind;
    if (/\/movie\//.test(u) || /\.(mkv|mp4|avi)$/.test(u) && /\/movie/.test(u)) return "vod";
    if (/\/series\//.test(u)) return "series";
    return "live";
  }

  function normalize(ch) {
    var raw = ch.urls;
    var urls = [];
    if (raw && raw.length) {
      raw.forEach(function (u) {
        if (typeof u === "string") urls.push({ url: u, label: "" });
        else if (u && u.url) urls.push({ url: u.url, label: u.label || "" });
      });
    } else if (ch.url) {
      urls.push({ url: ch.url, label: "" });
    }
    return {
      name: ch.name || "Unnamed",
      url: (urls[0] && urls[0].url) || ch.url,
      urls: urls,
      logo: ch["tvg-logo"] || ch.logo || "",
      group: ch["group-title"] || ch.group || "Genel",
      tvgId: ch["tvg-id"] || ch.tvgId || "",
      source: ch.source || "Özel",
      kind: ch.kind || "",
      headers: ch.headers || null
    };
  }

  function cloneChannel(ch) {
    var copy = {};
    for (var k in ch) if (Object.prototype.hasOwnProperty.call(ch, k)) copy[k] = ch[k];
    return copy;
  }

  // A channel can carry several stream URLs. Instead of hiding the backups
  // behind the main row, each one becomes its own list entry ("ATV", "ATV 2")
  // so a dead main stream can be skipped by just picking the next row.
  function flattenStreams(list) {
    var out = [];
    list.forEach(function (ch) {
      var streams = (ch.urls && ch.urls.length) ? ch.urls : [{ url: ch.url }];
      if (streams.length <= 1) {
        ch.streamGroup = ch.name;
        ch.streamIdx = 0;
        ch.streamCount = 1;
        delete ch.urls;
        out.push(ch);
        return;
      }
      streams.forEach(function (s, i) {
        var copy = cloneChannel(ch);
        copy.name = i === 0 ? ch.name : ch.name + " " + (i + 1);
        copy.url = s.url;
        copy.streamGroup = ch.name;
        copy.streamIdx = i;
        copy.streamCount = streams.length;
        delete copy.urls;
        out.push(copy);
      });
    });
    return out;
  }

  // Edits the user makes in the inspect dialog, keyed by where the channel
  // came from. They are reapplied on every load so a fixed URL survives a
  // fresh playlist fetch that would otherwise restore the broken one.
  function chKey(ch) {
    return [ch.listName || "Canlı TV", ch.group || "Genel",
            ch.streamGroup || ch.name, ch.streamIdx || 0].join("|");
  }

  function applyOverrides() {
    state.channels.forEach(function (ch) {
      var o = state.overrides[chKey(ch)];
      if (!o) return;
      if (o.name) ch.name = o.name;
      if (o.url) ch.url = o.url;
      if (o.logo != null) ch.logo = o.logo;
    });
  }

  // Channels the user added with "+ Kanal". They are re-added on every load so
  // an added channel survives a reload and is restored after a box reset.
  function applyAdded() {
    var added = state.added || [];
    if (!added.length) return;
    var have = {};
    state.channels.forEach(function (c) {
      have[(c.listName || "Canlı TV") + "|" + (c.url || "")] = 1;
    });
    added.forEach(function (a) {
      var ch = normalize(a);
      ch.listName = a.listName || "Özel kanallar";
      var key = ch.listName + "|" + (ch.url || "");
      if (have[key]) return;
      have[key] = 1;
      state.channels.unshift(ch);
      state.loaded[ch.listName] = true;
    });
  }

  // Channels the user deleted. They are remembered by list + stream URL, so a
  // fresh fetch of the same list does not bring them back.
  function applyDeleted() {
    var del = state.deleted || {};
    var keys = Object.keys(del);
    if (!keys.length) return;
    state.channels = state.channels.filter(function (ch) {
      return !del[(ch.listName || "Canlı TV") + "|" + (ch.url || "")];
    });
  }

  function deleteChannel(ch) {
    var key = (ch.listName || "Canlı TV") + "|" + (ch.url || "");
    state.deleted[key] = 1;
    // A user-added channel is dropped from the added list too; otherwise the
    // next load (or a restored backup) would bring it straight back.
    if (state.added && state.added.length) {
      state.added = state.added.filter(function (a) {
        return (a.listName || "Özel kanallar") + "|" + (a.url || "") !== key;
      });
    }
    state.channels = state.channels.filter(function (c) {
      return (c.listName || "Canlı TV") + "|" + (c.url || "") !== key;
    });
    // Also drop the dead stream from the channel's alternative list.
    state.channels.forEach(function (c) {
      if (c.urls && c.urls.length) {
        c.urls = c.urls.filter(function (u) { return u.url !== ch.url; });
      }
    });
    if (state.current === ch) { state.current = null; showBrowse(false); }
    persist();
    buildSources();
    buildGroups();
    renderTree();
    render();
    showOsd("🗑 " + ch.name + " silindi");
  }

  function parseM3UFile(file, source) {
    return new Promise(function (resolve) {
      var fr = new FileReader();
      fr.onload = function () { resolve(parseM3U(String(fr.result), source || file.name)); };
      fr.readAsText(file);
    });
  }

  /* ----------------------------- channels ---------------------------- */
  // Remote lists are loaded on demand, not at boot: a slow portal must not
  // hold up the first paint. Each list is fetched once and remembered by name.
  function loadRemoteList(pl) {
    var label = pl.name;
    if (state.loaded[label] || loadingLists[label]) return;
    loadingLists[label] = true;

    var done = function (channels) {
      channels.forEach(function (c) { c.listName = label; });
      // Replace any previous copy of this list before adding the fresh one, so
      // a reload (or a double click while it was fetching) can never duplicate
      // channels and inflate the group counts.
      var kept = state.channels.filter(function (c) {
        return (c.listName || "Canlı TV") !== label;
      });
      state.channels = normalizeLogos(flattenStreams(kept.concat(channels)));
      applyOverrides();
      state.loaded[label] = true;
      delete loadingLists[label];
      buildSources();
      buildGroups();
      render();
      persist();
    };

    var fail = function (msg) {
      delete loadingLists[label];
      setOverlay(true, "<h2>" + escapeHtml(label) + " yüklenemedi</h2><p>" +
        escapeHtml(msg) + "</p>");
    };

    if (pl.local) {
      done(pl.channels || []);
    } else if (pl.xc) {
      api("/api/xtream?portal=" + encodeURIComponent(pl.portal) +
          "&user=" + encodeURIComponent(pl.user) +
          "&pass=" + encodeURIComponent(pl.pass))
        .then(function (d) { done(d.channels || []); })
        .catch(function (e) { fail(e.message || "portal yanıt vermedi"); });
    } else if (pl.mac) {
      api("/api/stalker?portal=" + encodeURIComponent(pl.portal) +
          "&mac=" + encodeURIComponent(pl.macAddr))
        .then(function (d) { done(d.channels || []); })
        .catch(function (e) { fail(e.message || "portal yanıt vermedi"); });
    } else {
      get("/api/fetch?url=" + encodeURIComponent(pl.url))
        .then(function (r) { return r.text(); })
        .then(function (t) { done(parseM3U(t, label)); })
        .catch(function (e) { fail(e.message || "liste indirilemedi"); });
    }
  }

  function loadChannels() {
    // The channel list is compiled straight into the app (channels-data.js),
    // so it shows immediately with no server, network or permission needed.
    // The server API is only a bonus for freshly added channels.
    function embedded() {
      return (window.HH_CHANNELS || []).slice();
    }
    // Last list that actually loaded, kept so a broken bundle on a box with no
    // server still shows something instead of an empty screen.
    var CACHE_KEY = "tv.channels.cache";
    function saveCache(raw) {
      try {
        localStorage.setItem(CACHE_KEY, JSON.stringify({
          at: Date.now(), channels: raw
        }));
      } catch (e) {}
    }
    function loadCache() {
      try {
        var hit = JSON.parse(localStorage.getItem(CACHE_KEY) || "null");
        if (hit && hit.channels && hit.channels.length) return hit;
      } catch (e) {}
      return null;
    }
    function stampCache(hit) {
      var el = $("#listStamp");
      if (!el) return;
      var d = new Date(hit.at);
      el.textContent = "Son liste: " +
        ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2);
      el.hidden = false;
    }
    var start = embedded().length ? Promise.resolve(embedded()) : api("/api/channels")
      .then(function (data) { return (data && data.channels) || []; })
      .catch(function () { return []; });
    return start
      .then(function (raw) {
        if (!raw || !raw.length) {
          // Both the bundle and the server came up empty; fall back to the
          // last good list and label it with the time it was captured.
          var hit = loadCache();
          if (hit) { stampCache(hit); return hit.channels; }
          return embedded();
        }
        return raw;
      })
      .then(function (raw) {
      var mapped = (raw || []).map(function (c) {
        // Radios are bundled alongside TV but are their own top-level source,
        // so they get their own list name and never mix into Canlı TV.
        c.listName = contentKind(c) === "radio" ? "Radyo" : "Canlı TV";
        return c;
      });
      saveCache(mapped);
      // Paint live TV first: the 210 radio stations are normalised and drawn
      // only when the Radio tab (or a radio kind) is first opened, so the
      // first frame carries the much smaller TV list.
      state.radios = mapped.filter(function (c) { return c.listName === "Radyo"; });
      state.channels = mapped.filter(function (c) { return c.listName !== "Radyo"; });
      radioLoaded = !state.radios.length;
      state.channels = normalizeLogos(dedupe(state.channels));
      state.channels = flattenStreams(state.channels);
      applyOverrides();
      applyAdded();
      applyDeleted();
      state.loaded = { "Canlı TV": true };
      buildSources();
      buildGroups();
      buildKindTabs();
      render();
      return state.channels;
    }).catch(function () {
      state.channels = [];
      buildSources();
      buildKindTabs();
      render();
      return state.channels;
    });
  }

  var radioLoaded = false;

  // Folds the bundled radios into the live list on first use. Called from
  // render() when the Radio tab is selected, so opening the app never pays for
  // it.
  function ensureRadioLoaded() {
    if (radioLoaded) return;
    radioLoaded = true;
    var radios = (state.radios || []).map(function (c) {
      c.listName = "Radyo";
      return c;
    });
    radios = normalizeLogos(dedupe(radios));
    radios = flattenStreams(radios);
    state.channels = state.channels.concat(radios);
    state.radios = null;
    applyOverrides();
    state.loaded["Radyo"] = true;
    if (state.expanded) state.expanded["Radyo"] = true;
    buildSources();
    buildGroups();
    buildKindTabs();
    render();
  }
  window.tvEnsureRadio = ensureRadioLoaded;

  // The plain channels.json is a fallback for builds without the embedded
  // script; every read path stays offline.
  function bundledChannels() {
    if (window.HH_CHANNELS && window.HH_CHANNELS.length) {
      return Promise.resolve(window.HH_CHANNELS.slice());
    }
    return get("channels.json").then(function (r) {
      return r.json();
    }).catch(function () { return []; });
  }

  // The same channel can arrive from several lists with different logo URLs.
  // Pick the most common one per name so a channel keeps one stable logo.
  function normalizeLogos(list) {
    var votes = {};
    list.forEach(function (ch) {
      if (!ch.logo) return;
      var n = ch.name || "";
      (votes[n] = votes[n] || {})[ch.logo] = (votes[n][ch.logo] || 0) + 1;
    });
    var best = {};
    Object.keys(votes).forEach(function (n) {
      var top = "", topN = 0;
      Object.keys(votes[n]).forEach(function (l) {
        if (votes[n][l] > topN) { topN = votes[n][l]; top = l; }
      });
      best[n] = top;
    });
    list.forEach(function (ch) { ch.logo = best[ch.name] || ch.logo; });
    return list;
  }

  // Distinct lists (bundled + each playlist / portal) the user can switch
  // between, so channels from different sources are not one big pile.
  // Remote (Xtream/Stalker/M3U) lists are read straight from the store and are
  // not fetched on boot, so a slow portal never blocks the first paint. A list
  // becomes a sidebar branch only once the user picks it and it loads.
  var loadingLists = {};

  function remoteLists() {
    return state.playlists.filter(function (pl) { return !pl.hidden && !pl.local; });
  }

  function buildSources() {
    var counts = {};
    var order = [];
    state.channels.forEach(function (ch) {
      var n = ch.listName || "Canlı TV";
      if (!counts[n]) { counts[n] = 0; order.push(n); }
      counts[n] += 1;
    });
    // Bundled lists first in a fixed order, then user lists in the order they
    // were added — never alphabetically, so the panel's own ordering is kept.
    var sources = order.filter(function (n) {
      return n !== "Canlı TV" && n !== "Radyo";
    }).map(function (n) { return { name: n, count: counts[n] }; });
    if (counts["Canlı TV"]) {
      sources.unshift({ name: "Canlı TV", count: counts["Canlı TV"] });
    }
    if (counts["Radyo"]) {
      var radyo = { name: "Radyo", count: counts["Radyo"] };
      var at = counts["Canlı TV"] ? 1 : 0;
      sources.splice(at, 0, radyo);
    }
    state.sources = sources;
    // Lists that are known but not fetched yet still belong in the picker, so
    // a portal added a moment ago is selectable before it has loaded.
    remoteLists().forEach(function (pl) {
      if (!sources.some(function (s) { return s.name === pl.name; })) {
        sources.push({ name: pl.name, count: 0, pending: true });
      }
    });
    // The user's own list is what they came for, so it is placed above the
    // bundled list in both the tree and the picker.
    var my = sources.filter(function (s) {
      return s.name !== "Canlı TV" && s.name !== "Radyo";
    });
    var bundled = sources.filter(function (s) {
      return s.name === "Canlı TV" || s.name === "Radyo";
    });
    state.sources = my.concat(bundled);
    // A list must always be selected: the pinned bundled list is the fallback.
    if (state.source !== "all" &&
        !state.sources.some(function (s) { return s.name === state.source; })) {
      state.source = "Canlı TV";
    }
    renderTree();
  }

  /* ---------------------------- navigation ---------------------------- */
  // The sidebar is a tree: pick a list first, then a content type inside it
  // (Live TV / Movies / Series), then a group, then a channel. The bundled
  // Turkish list and each added portal stay separate all the way down, so
  // their live channels never get mixed together.
  var KIND_LABEL = { live: "Canlı TV", vod: "Film", series: "Dizi", radio: "Radyo" };
  var KIND_ICON = { live: "📺", vod: "🎬", series: "📽", radio: "📻" };

  function listNames() {
    return state.sources.map(function (s) { return s.name; });
  }

  function scopeFor(sourceName, kind) {
    return state.channels.filter(function (ch) {
      var n = ch.listName || "Canlı TV";
      if (sourceName !== "all" && n !== sourceName) return false;
      if (kind && kind !== "all" && contentKind(ch) !== kind) return false;
      return true;
    });
  }

  // Which content types a given list actually contains, in list order.
  function kindsFor(sourceName) {
    var seen = {};
    var order = [];
    scopeFor(sourceName, "all").forEach(function (ch) {
      var k = contentKind(ch);
      if (!seen[k]) { seen[k] = 0; order.push(k); }
      seen[k] += 1;
    });
    return order.map(function (k) { return { kind: k, count: seen[k] }; });
  }

  function renderTree() {
    var box = $("#navTree");
    box.innerHTML = "";

    // The two favorites live at the very top so they are always one tap away.
    box.appendChild(treeLeaf({
      label: "⭐ Canlı TV favorileri", count: favList("Canlı TV").length,
      active: state.tab === "fav" && state.source === "Canlı TV",
      onPick: function () { selectTab("fav", "all", "Canlı TV"); }
    }));
    box.appendChild(treeLeaf({
      label: "📻 Radyo favorileri", count: favList("Radyo").length,
      active: state.tab === "fav" && state.source === "Radyo",
      onPick: function () { selectTab("fav", "all", "Radyo"); }
    }));

    // Then the lists themselves: the user's own list first, then the bundled
    // Canlı TV and Radyo. A user list branch is shown before the built-in ones.
    state.sources.forEach(function (s) {
      box.appendChild(treeBranch({
        label: (s.name === "Canlı TV" ? "📌 " : s.name === "Radyo" ? "📻 " : "") + s.name,
        count: s.pending ? "—" : s.count,
        // Every list — Canlı TV and Radyo included — shows its categories as a
        // tree level so a station can be reached by genre, not just by name.
        groups: true,
        key: s.name, active: state.tab === "all" && state.source === s.name
      }));
    });
  }

  // Choosing a list focuses it: the tree opens that list and the channel pane
  // switches to it right away, so you never have to hunt for the list twice.
  function selectList(name) {
    // "fav:<list>" opens that list's own favorites.
    if (name.indexOf("fav:") === 0) {
      var key = name.slice(4);
      selectTab("fav", "all");
      state.source = key;
      state.expanded[key] = true;
      buildGroups();
      render();
      persist();
      return;
    }
    state.tab = "all";
    state.source = name;
    state.kind = "all";
    state.detail = null;
    state.expanded[name] = true;

    // Picking a list that has not been fetched yet loads it now, so the list
    // named in the picker is the one that appears in the sidebar.
    var pl = state.playlists.filter(function (p) {
      return p.name === name;
    })[0];
    if (pl && !state.loaded[name]) {
      setOverlay(true, "<h2>" + escapeHtml(name) + "</h2><p>Liste yükleniyor…</p>");
      loadRemoteList(pl);
    }
    buildGroups();
    render();
    persist();
  }

  /* ------------------------- tree ------------------------- */
  function treeBranch(o) {
    var wrap = document.createElement("div");
    wrap.className = "tree-branch";
    var open = !!state.expanded[o.key];
    var row = document.createElement("button");
    row.className = "tree-row" + (o.active ? " active" : "");
    row.innerHTML = '<span class="tw">' + (open ? "▾" : "▸") + "</span>" +
      '<span class="tl">' + escapeHtml(o.label) + "</span>" +
      '<span class="tn">' + o.count + "</span>";
    row.onclick = function () {
      // The branch follows the click only: selecting a list must not force it
      // back open, otherwise the row can never be collapsed again.
      var willOpen = !open;
      state.expanded[o.key] = willOpen;
      if (willOpen) {
        state.source = o.key;
        state.tab = "all";
        state.kind = "all";
        state.group = "all";
      }
      renderTree();
      buildGroups();
      render();
      // A personal list is fetched the first time it is opened from the tree,
      // so the branch fills in with its own Live TV / Movies / Series leaves.
      if (willOpen && !state.loaded[o.key]) {
        var pl = state.playlists.filter(function (p) { return p.name === o.key; })[0];
        if (pl) {
          setOverlay(true, "<h2>" + escapeHtml(o.key) + "</h2><p>Liste yükleniyor…</p>");
          loadRemoteList(pl);
        }
      }
    };
    wrap.appendChild(row);

    if (open) {
      var kids = document.createElement("div");
      kids.className = "tree-kids";
      // Each list's own favorites sit first, so a list's favorites are one tap
      // away and switch together with the list.
      if (o.kinds !== false) {
        kids.appendChild(treeLeaf({
          label: "⭐ Favoriler", count: favList(o.key).length,
          active: state.tab === "fav" && state.source === o.key,
          onPick: function () { selectTab("fav", "all", o.key); }
        }));
      }
      // No "Hepsi" child: tapping the branch row itself already selects every
      // channel in the list, so a duplicate leaf only adds noise.
      var kinds = o.kinds === false ? [] : kindsFor(o.key);
      var activeKind = null;
      kinds.forEach(function (k) {
        if (state.source === o.key && state.kind === k.kind) activeKind = k.kind;
        kids.appendChild(treeLeaf({
          label: KIND_ICON[k.kind] + " " + KIND_LABEL[k.kind], count: k.count,
          active: state.source === o.key && state.kind === k.kind,
          onPick: function () { selectKind(o.key, k.kind); }
        }));
      });
      // Categories show as soon as the list is opened. A list with a single
      // content type (Canlı TV, Radyo) needs no extra tap; a multi-type portal
      // shows the groups of whichever type is currently selected.
      if (o.groups !== false && !activeKind && kinds.length === 1) {
        activeKind = kinds[0].kind;
      }
      if (o.groups !== false && activeKind) {
        var groups = groupCountsFor(o.key, activeKind);
        if (groups.length > 1) {
          var gkids = document.createElement("div");
          gkids.className = "tree-kids";
          groups.forEach(function (g) {
            // Accordion: one group open at a time. Opening a group lists its
            // channels right underneath it, so a station is reached in the tree
            // itself instead of a second pane.
            var gkey = o.key + "\u0000" + activeKind + "\u0000" + g.name;
            var openG = state.openGroup === gkey;
            gkids.appendChild(treeLeaf({
              label: (openG ? "▾ " : "▸ ") + g.name, count: g.count,
              active: openG,
              onPick: function () {
                state.openGroup = openG ? null : gkey;
                renderTree();
              }
            }));
            if (openG) {
              var chans = scopeFor(o.key, activeKind).filter(function (ch) {
                return (ch.group || "Genel") === g.name;
              });
              var cwrap = document.createElement("div");
              cwrap.className = "tree-kids ch-kids";
              chans.slice(0, 250).forEach(function (ch) {
                var cb = document.createElement("button");
                cb.className = "tree-row leaf ch-leaf" +
                  (state.current && state.current.url === ch.url ? " active" : "");
                var initial = (ch.name || "?").trim().charAt(0).toUpperCase();
                cb.innerHTML = '<span class="tw"></span>' +
                  '<span class="ch-mini" style="background:hsl(' +
                  logoHue(ch.name || "") + ' 55% 30%)">' + escapeHtml(initial) +
                  "</span><span class=\"tl\">" + escapeHtml(ch.name) + "</span>";
                cb.onclick = function () { play(ch); };
                cwrap.appendChild(cb);
              });
              if (chans.length > 250) {
                var more = document.createElement("div");
                more.className = "tree-row leaf ch-leaf muted";
                more.textContent = "+" + (chans.length - 250) + " kanal daha…";
                cwrap.appendChild(more);
              }
              gkids.appendChild(cwrap);
            }
          });
          kids.appendChild(gkids);
        }
      }
      wrap.appendChild(kids);
    }
    return wrap;
  }

  function groupCountsFor(sourceName, kind) {
    var counts = {};
    var order = [];
    scopeFor(sourceName, kind).forEach(function (ch) {
      var g = ch.group || "Genel";
      if (!counts[g]) { counts[g] = 0; order.push(g); }
      counts[g] += 1;
    });
    return order.map(function (g) { return { name: g, count: counts[g] }; });
  }

  function selectGroup(sourceName, kind, group) {
    state.tab = "all";
    state.source = sourceName;
    state.kind = kind;
    state.group = group;
    buildGroups();
    render();
    renderTree();
  }

  function treeLeaf(o) {
    var b = document.createElement("button");
    b.className = "tree-row leaf" + (o.active ? " active" : "");
    b.innerHTML = '<span class="tw"></span><span class="tl">' +
      escapeHtml(o.label) + "</span><span class='tn'>" + o.count + "</span>";
    b.onclick = o.onPick;
    return b;
  }

  function selectTab(tab, kind, source) {
    state.tab = tab;
    state.source = source || "all";
    state.kind = kind || "all";
    state.group = "all";
    clearSearch();
    renderTree();
    buildGroups();
    buildKindTabs();
    render();
  }

  // The search box keeps filtering the personal views too, which made a
  // favorites list look empty after a search. Clear it on every navigation.
  function clearSearch() {
    state.query = "";
    var inp = $("#search");
    if (inp) inp.value = "";
    var clr = $("#clearSearch");
    if (clr) clr.hidden = true;
  }

  function selectKind(sourceName, kind) {
    state.tab = "all";
    state.source = sourceName;
    state.kind = kind;
    state.group = "all";
    state.expanded[sourceName] = true;
    clearSearch();
    renderTree();
    buildGroups();
    buildKindTabs();
    render();
  }

  // The Canlı TV / Film / Dizi / Radyo tabs under the tree: tapping one jumps
  // straight to that content type inside the selected list.
  function buildKindTabs() {
    var box = $("#kindTabs");
    if (!box) return;
    var src = state.source === "all" ? "Canlı TV" : state.source;
    $$(".ktab", box).forEach(function (b) {
      var kind = b.dataset.kind;
      var has = kindsFor(src).some(function (k) { return k.kind === kind; });
      b.hidden = !has && !(kind === "radio" && src === "Canlı TV");
      b.classList.toggle("active", state.tab === "all" && state.source === src &&
        state.kind === kind);
      b.onclick = function () { selectKind(src, kind); };
    });
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function dedupe(list) {
    var seenName = {};
    var seenUrl = {};
    var out = [];
    list.forEach(function (ch) {
      // Within one list, a channel that appears twice with the same stream URL
      // is a copy: keep the first and drop the rest. The list name is part of
      // the key so two different portals never collapse into each other.
      var listKey = ch.listName || "Canlı TV";
      var urlKey = listKey + "|" + (ch.url || "");
      var nameKey = listKey + "|" + (ch.name || "") + "|" + (ch.url || "");
      if (ch.url && seenUrl[urlKey]) return;
      if (seenName[nameKey]) return;
      seenName[nameKey] = 1;
      if (ch.url) seenUrl[urlKey] = 1;
      out.push(ch);
    });
    return out;
  }

  function buildGroups() {
    var box = $("#groups");
    if (!box) return;
    // The tree already carries the group level, so the chip bar is only a
    // shortcut for added portals. The bundled list and radio never group here.
    if (state.tab !== "all" || state.source === "all" ||
        state.source === "Canlı TV" || state.kind === "radio") {
      box.innerHTML = "";
      box.hidden = true;
      return;
    }
    var scope = state.channels.filter(function (ch) {
      if ((ch.listName || "Canlı TV") !== state.source) return false;
      var kind = contentKind(ch);
      if (kind === "radio") return false;
      if (state.kind !== "all" && kind !== state.kind) return false;
      return true;
    });
    var counts = {};
    var order = [];
    scope.forEach(function (ch) {
      var g = ch.group || "Genel";
      if (!counts[g]) { counts[g] = 0; order.push(g); }
      counts[g] += 1;
    });
    box.innerHTML = "";
    box.hidden = false;
    var allChip = document.createElement("button");
    allChip.className = "gchip" + (state.group === "all" ? " active" : "");
    allChip.textContent = "Tüm gruplar (" + scope.length + ")";
    allChip.onclick = function () { state.group = "all"; buildGroups(); render(); };
    box.appendChild(allChip);

    order.forEach(function (g) {
      var b = document.createElement("button");
      b.className = "gchip" + (state.group === g ? " active" : "");
      b.textContent = g + " (" + counts[g] + ")";
      b.onclick = function () { state.group = g; buildGroups(); render(); };
      box.appendChild(b);
    });
  }

  function isFav(url) { return favList().indexOf(url) >= 0; }

  // Every list keeps its own favorites, so switching lists switches the star
  // state too. The current list is the one being browsed; radio lives inside
  // its own source so it keeps a separate set as well.
  function listKeyOf(ch) {
    return ch.listName || "Canlı TV";
  }

  function favList(key) {
    key = key || (state.tab === "all" ? state.source : state.source);
    if (key === "all" || !key) key = "Canlı TV";
    if (!state.favoritesByList[key]) state.favoritesByList[key] = [];
    return state.favoritesByList[key];
  }

  // True when the given channel is starred in its own list.
  function isFavOf(ch) {
    return favList(listKeyOf(ch)).indexOf(ch.url) >= 0;
  }

  function recentList(key) {
    key = key || (state.tab === "all" ? state.source : state.source);
    if (key === "all" || !key) key = "Canlı TV";
    if (!state.recentsByList[key]) state.recentsByList[key] = [];
    return state.recentsByList[key];
  }

  function applyFilter() {
    var q = state.query.toLowerCase();
    var list = state.channels.filter(function (ch) {
      if (state.tab === "all") {
        if (state.source !== "all" &&
            (ch.listName || "Canlı TV") !== state.source) return false;
        if (state.kind !== "all" && contentKind(ch) !== state.kind) return false;
        if (state.group !== "all" && (ch.group || "Genel") !== state.group) return false;
      }
      if (state.tab === "fav") {
        // Live TV and radio favorites are two separate leaves; a per-list
        // favorites leaf additionally narrows to that one list.
        var isRadio = contentKind(ch) === "radio";
        if (state.source === "Canlı TV") {
          // The bundled favorites leaf is Live TV only; radio has its own.
          if (isRadio) return false;
        } else if (state.source === "Radyo") {
          if (!isRadio) return false;
        } else if (state.kind === "radio") {
          if (!isRadio) return false;
        } else if (state.kind === "live") {
          if (isRadio) return false;
        }
        if (!isFavOf(ch)) return false;
        if (state.source !== "all" && listKeyOf(ch) !== state.source) return false;
      }
      if (state.tab === "recent") {
        if (recentList(listKeyOf(ch)).indexOf(ch.url) < 0) return false;
        var recentRadio = contentKind(ch) === "radio";
        if (state.kind === "radio" ? !recentRadio : recentRadio) return false;
      }
      // Favorites and recents are personal views: they show channels from every
      // list, not only the one currently selected in the sidebar.
      if (q && ch.name.toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
    if (state.tab === "recent") {
      list.sort(function (a, b) {
        var ra = recentList(listKeyOf(a)).indexOf(a.url);
        var rb = recentList(listKeyOf(b)).indexOf(b.url);
        return ra - rb;
      });
    }
    state.filtered = list;
  }

  // Rendering a channel row calls this twice per row; on a 1500-channel list
  // the string hash dominated the frame. Memoise by name so repeated renders
  // and same-named logos cost nothing.
  var logoHueCache = {};
  function logoHue(name) {
    var cached = logoHueCache[name];
    if (cached !== undefined) return cached;
    var h = 0;
    for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    logoHueCache[name] = h;
    return h;
  }

  // Season/episode markers as providers write them: "S01E02", "S1 E2",
  // "1x02". Returns null for a plain movie title.
  function parseEp(name) {
    var m = /S\s*(\d{1,3})\s*[ ._-]*E\s*(\d{1,4})/i.exec(name) ||
            /(\d{1,2})\s*x\s*(\d{1,3})/.exec(name);
    return m ? { s: +m[1], e: +m[2] } : null;
  }

  function seriesBase(name) {
    return name.replace(/\s*[-–]?\s*S\s*\d{1,3}\s*[ ._-]*E\s*\d{1,4}.*$/i, "")
               .replace(/\s*\d{1,2}\s*x\s*\d{1,3}.*$/, "").trim() || name;
  }

  // Browse screens (poster grid + series episodes) take over the main pane,
  // so a movie poster never sits behind a live channel's player.
  function showBrowse(on) {
    $("#browse").classList.toggle("hide", !on);
    $("#stage").classList.toggle("hide", on);
  }

  function renderGrid() {
    showBrowse(true);
    var body = $("#browseBody");
    body.className = "grid";
    body.innerHTML = "";
    var list = state.filtered;
    $("#browseBack").hidden = true;
    $("#browseTitle").textContent = state.kind === "series" ? "Diziler"
      : state.kind === "vod" ? "Filmler" : "Film ve Diziler";
    $("#browseCount").textContent = list.length + " içerik";

    var frag = document.createDocumentFragment();
    list.slice(0, 3000).forEach(function (ch) {
      var isSeries = contentKind(ch) === "series";
      var card = document.createElement("button");
      card.className = "poster";
      var img = document.createElement("img");
      img.alt = "";
      img.loading = "lazy";
      img.onerror = function () {
        img.remove();
        card.classList.add("noimg");
        card.insertAdjacentHTML("afterbegin",
          '<span class="pinit">' + escapeHtml((ch.name || "?").charAt(0).toUpperCase()) + "</span>");
      };
      if (ch.logo) img.src = ch.logo;
      else img.dispatchEvent(new Event("error"));
      card.appendChild(img);
      var nm = document.createElement("span");
      nm.className = "pname";
      nm.textContent = ch.name;
      card.appendChild(nm);
      if (isSeries) {
        var bd = document.createElement("span");
        bd.className = "pbadge";
        bd.textContent = "Dizi";
        card.appendChild(bd);
      }
      card.onclick = function () { isSeries ? openSeries(ch) : play(ch); };
      frag.appendChild(card);
    });
    body.appendChild(frag);
  }

  function openSeries(ch) {
    state.detail = seriesBase(ch.name);
    state.detailPoster = ch.logo || "";
    render();
  }

  function renderSeries(base) {
    showBrowse(true);
    var body = $("#browseBody");
    body.className = "series";
    body.innerHTML = "";
    $("#browseBack").hidden = false;
    $("#browseBack").onclick = function () {
      state.detail = null;
      render();
    };
    $("#browseTitle").textContent = base;
    $("#browseCount").textContent = "";

    var eps = state.channels.filter(function (ch) {
      return seriesBase(ch.name) === base;
    });
    var seasons = {};
    var noSeason = [];
    eps.forEach(function (ch) {
      var p = parseEp(ch.name);
      if (p) (seasons[p.s] = seasons[p.s] || []).push({ ch: ch, e: p.e });
      else noSeason.push({ ch: ch, e: 0 });
    });
    var seasonNums = Object.keys(seasons).map(Number).sort(function (a, b) { return a - b; });

    var head = document.createElement("div");
    head.className = "series-head";
    if (state.detailPoster) {
      var pi = document.createElement("img");
      pi.src = state.detailPoster;
      pi.alt = "";
      head.appendChild(pi);
    }
    var htxt = document.createElement("div");
    htxt.innerHTML = "<h3>" + escapeHtml(base) + "</h3><p class='muted'>" +
      (seasonNums.length ? seasonNums.length + " sezon · " : "") +
      eps.length + " bölüm</p>";
    head.appendChild(htxt);
    body.appendChild(head);

    function episodeList(items) {
      var box = document.createElement("div");
      box.className = "ep-list";
      items.sort(function (a, b) { return a.e - b.e; });
      items.forEach(function (it) {
        var b = document.createElement("button");
        b.className = "ep" + (state.current && state.current.url === it.ch.url ? " active" : "");
        b.innerHTML = '<span class="epn">' + (it.e ? "B" + it.e : "•") + "</span>" +
          '<span class="ept">' + escapeHtml(it.ch.name) + "</span>";
        b.onclick = function () { play(it.ch); };
        box.appendChild(b);
      });
      return box;
    }

    if (seasonNums.length) {
      var chips = document.createElement("div");
      chips.className = "season-chips";
      var first = seasonNums[0];
      var active = state.detailSeason && seasonNums.indexOf(state.detailSeason) >= 0
        ? state.detailSeason : first;
      var listBox = document.createElement("div");
      var showSeason = function (s) {
        state.detailSeason = s;
        Array.prototype.forEach.call(chips.children, function (c) {
          c.classList.toggle("active", +c.dataset.s === s);
        });
        listBox.innerHTML = "";
        listBox.appendChild(episodeList(seasons[s] || []));
      };
      seasonNums.forEach(function (s) {
        var c = document.createElement("button");
        c.className = "schip";
        c.dataset.s = s;
        c.textContent = "Sezon " + s + " (" + seasons[s].length + ")";
        c.onclick = function () { showSeason(s); };
        chips.appendChild(c);
      });
      body.appendChild(chips);
      body.appendChild(listBox);
      showSeason(active);
    } else if (noSeason.length) {
      body.appendChild(episodeList(noSeason));
    } else {
      body.insertAdjacentHTML("beforeend", "<p class='muted'>Bölüm bulunamadı.</p>");
    }
  }

  function render() {
    // The Radio tab pulls in the bundled radios the first time it is opened.
    if (state.source === "Radyo" || state.kind === "radio") ensureRadioLoaded();
    applyFilter();
    // Movies and series are browsed as a poster grid; a series opens its own
    // season/episode screen. Live TV keeps the compact single-column list.
    if (state.detail) return renderSeries(state.detail);
    // Movies and series open as a poster grid in the main pane, whichever list
    // they come from, so a VOD pick shows artwork instead of a text list.
    if (state.tab === "all" && (state.kind === "vod" || state.kind === "series")) {
      return renderGrid();
    }
    showBrowse(false);
    var box = $("#channels");
    // Remember which row had the remote's focus: re-rendering replaces the
    // whole list, and without this the D-pad would drop back to the body.
    var active = document.activeElement;
    var keepUrl = active && active.classList && active.classList.contains("ch")
      ? active.dataset.url : null;
    box.className = "channel-list";
    box.innerHTML = "";
    box.scrollTop = 0;
    appendChannels(box, 0, PAGE_FIRST);
    // Restore focus to the same channel, or hand it to the first row so the
    // remote always has somewhere to go.
    var rows = box.querySelectorAll(".ch");
    var target = null;
    if (keepUrl) {
      for (var i = 0; i < rows.length; i++) {
        if (rows[i].dataset.url === keepUrl) { target = rows[i]; break; }
      }
    }
    if (!target && rows.length) target = rows[0];
    if (target && !(active && active.classList && active.classList.contains("ch"))) {
      target.setAttribute("tabindex", "0");
    } else if (target && target !== active) {
      try { target.focus({ preventScroll: true }); } catch (e) { target.focus(); }
    }
  }

  // One channel row. Rendered in batches (see appendChannels) so a huge list
  // does not build thousands of nodes before the first usable frame.
  function channelNode(ch) {
    var node = document.createElement("div");
      node.className = "ch";
      node.tabIndex = 0;
      node.dataset.url = ch.url;
      if (state.current && state.current.url === ch.url) node.classList.add("active");

      var logo = document.createElement("div");
      logo.className = "ch-logo";
      var initial = (ch.name || "?").trim().charAt(0).toUpperCase();
      logo.style.background =
        "linear-gradient(135deg,hsl(" + logoHue(ch.name || "") + " 55% 34%),hsl(" +
        ((logoHue(ch.name || "") + 40) % 360) + " 55% 22%))";
      var ph = document.createElement("span");
      ph.className = "ph";
      ph.textContent = initial;
      logo.appendChild(ph);
      if (ch.logo) {
        var img = document.createElement("img");
        img.alt = "";
        img.loading = "lazy";
        img.decoding = "async";
        img.onload = function () { ph.style.display = "none"; };
        img.onerror = function () { img.remove(); };
        img.src = ch.logo;
        logo.appendChild(img);
      }

      var text = document.createElement("div");
      text.className = "ch-text";
      var nm = document.createElement("span");
      nm.className = "ch-name";
      nm.textContent = ch.name;
      var sub = document.createElement("span");
      sub.className = "ch-sub muted";
      sub.textContent = ch.group || "Genel";
      text.appendChild(nm);
      text.appendChild(sub);

      var favBtn = document.createElement("button");
      favBtn.className = "ch-fav" + (isFavOf(ch) ? " on" : "");
      favBtn.textContent = isFavOf(ch) ? "★" : "☆";
      favBtn.title = "Favori";
      favBtn.onclick = function (ev) { ev.stopPropagation(); toggleFav(ch); };

      var head = document.createElement("div");
      head.className = "ch-head";
      head.appendChild(logo);
      head.appendChild(text);
      head.appendChild(favBtn);

      var inspectBtn = document.createElement("button");
      inspectBtn.className = "ch-inspect";
      inspectBtn.type = "button";
      inspectBtn.textContent = "ⓘ";
      inspectBtn.title = "İncele / düzenle";
      inspectBtn.onclick = function (ev) { ev.stopPropagation(); inspect(ch); };
      head.appendChild(inspectBtn);

      head.onclick = function () { play(ch); };
      node.appendChild(head);

      node.onkeydown = function (ev) {
        if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); play(ch); }
      };
    return node;
  }

  var PAGE_FIRST = 200;
  var PAGE_MORE = 200;
  var renderedCount = 0;

  // Appends the next batch of rows. Called once for the initial paint and then
  // again when the user reaches the bottom or taps "daha göster", so the first
  // frame only ever carries PAGE_FIRST nodes. A 1500-row burst was what froze
  // the UI on TV boxes.
  function appendChannels(box, from, count) {
    var end = Math.min(from + count, state.filtered.length);
    if (from >= end) return;
    var frag = document.createDocumentFragment();
    for (var i = from; i < end; i++) frag.appendChild(channelNode(state.filtered[i]));
    box.appendChild(frag);
    renderedCount = end;
    updateListInfo(box, end < state.filtered.length);
  }

  function updateListInfo(box, hasMore) {
    var info = $("#chCount");
    if (info) info.textContent = state.filtered.length + " / " + state.channels.length + " kanal";
    var old = $(".more-toggle");
    if (old) old.remove();
    if (!hasMore) return;
    var btn = document.createElement("button");
    btn.className = "more-toggle";
    btn.textContent = "+" + (state.filtered.length - renderedCount) + " daha göster";
    btn.tabIndex = 0;
    btn.onclick = function () { appendChannels(box, renderedCount, PAGE_MORE); };
    // Reaching the button with the remote's D-pad loads the next page in place,
    // so a long list is navigable without tapping.
    btn.onfocus = function () { appendChannels(box, renderedCount, PAGE_MORE); };
    box.appendChild(btn);
  }

  function toggleFav(ch) {
    if (typeof ch === "string") ch = { url: ch, listName: state.source };
    var list = favList(listKeyOf(ch));
    var i = list.indexOf(ch.url);
    if (i >= 0) list.splice(i, 1);
    else list.push(ch.url);
    // Keep the legacy flat list in sync so older exports still carry every
    // starred stream.
    var flat = state.favorites.indexOf(ch.url);
    if (i >= 0 && flat >= 0) state.favorites.splice(flat, 1);
    if (i < 0 && flat < 0) state.favorites.push(ch.url);
    persist();
    render();
    updateFavBtn();
  }

  function pushRecent(ch) {
    if (typeof ch === "string") ch = { url: ch, listName: state.source };
    var list = recentList(listKeyOf(ch));
    var i = list.indexOf(ch.url);
    if (i >= 0) list.splice(i, 1);
    list.unshift(ch.url);
    state.recentsByList[listKeyOf(ch)] = list.slice(0, 50);
    persist();
  }

  /* ------------------------------ player ----------------------------- */
  // Movies and series have no live picture to show while they buffer, so the
  // stage shows their poster instead; live channels hide it immediately.
  function showVodInfo(ch) {
    var box = $("#vodInfo");
    var kind = contentKind(ch);
    // Live TV has no poster; radio reuses the same panel with its logo.
    if (kind === "live") { box.classList.add("hide"); return; }
    var poster = $("#vodPoster");
    if (ch.logo) {
      poster.src = ch.logo;
      poster.hidden = false;
    } else {
      poster.removeAttribute("src");
      poster.hidden = true;
    }
    $("#vodTitle").textContent = ch.name;
    var label = kind === "vod" ? "Film" : (kind === "radio" ? "Radyo" : "Dizi");
    $("#vodSub").textContent = label + (ch.group ? " · " + ch.group : "");
    box.classList.remove("hide");
  }

  // Radio has no picture, so the stage shows the station logo and name instead
  // of a black frame while the audio plays.
  function showRadioArt(ch) {
    showVodInfo(ch);
    var box = $("#vodInfo");
    box.classList.remove("hide");
  }

  function hideVodInfo() { $("#vodInfo").classList.add("hide"); }

  function setOverlay(show, msgHtml) {
    overlay.classList.toggle("hide", !show);
    if (msgHtml) overlayMsg.innerHTML = msgHtml;
  }

  function showOsd(text) {
    osd.textContent = text;
    osd.classList.add("show");
    clearTimeout(osdTimer);
    osdTimer = setTimeout(function () { osd.classList.remove("show"); }, 2500);
  }

  function destroyHls() {
    if (hls) { try { hls.destroy(); } catch (e) {} hls = null; }
  }

  // ---- black-frame watchdog ------------------------------------------
  // A stream can report "playing" while showing a black frame (bad mirror,
  // geo-block, silent switch). After BLACK_FRAME_MS with no decoded picture,
  // try the next mirror. Radio is skipped: it has no picture by design.
  var BLACK_FRAME_MS = 3000;
  var blackTimer = null;
  var blackTried = "";
  function clearBlackTimer() {
    if (blackTimer) { clearTimeout(blackTimer); blackTimer = null; }
  }
  function armBlackTimer(stream, index) {
    clearBlackTimer();
    blackTimer = setTimeout(function () {
      var ch = state.current;
      if (!ch || contentKind(ch) === "radio") return;
      if (nativePlayer()) return;            // the native player has its own
      if (video.videoWidth > 0) return;      // a picture arrived, all good
      var key = (ch.url || "") + "|" + index;
      if (blackTried === key) return;        // never loop on the same mirror
      blackTried = key;
      showOsd("Ayna " + (index + 2) + " deneniyor");
      loadStream(index + 1);
    }, BLACK_FRAME_MS);
  }

  // Inside the APK a native ExoPlayer renders the video; the WebView only draws
  // the channel list. On the desktop (no bridge) the web <video> is used.
  function nativePlayer() {
    return (typeof window.AndroidPlayer !== "undefined") ? window.AndroidPlayer : null;
  }

  function stageBounds() {
    var st = $("#stage");
    if (!st || !st.getBoundingClientRect) return null;
    var r = st.getBoundingClientRect();
    // getBoundingClientRect is in CSS pixels, the native View is laid out in
    // physical pixels: without this scale the native player would open small.
    var d = window.devicePixelRatio || 1;
    return {
      x: Math.round(r.left * d), y: Math.round(r.top * d),
      w: Math.round(r.width * d), h: Math.round(r.height * d)
    };
  }

  function syncNativeBounds() {
    var np = nativePlayer();
    var b = stageBounds();
    if (np && b) { try { np.setBounds(b.x, b.y, b.w, b.h); } catch (e) {} }
  }

  // The native side reports retries, stalls and give-ups back to the UI.
  var nativeState = { position: 0, duration: -1, state: "idle", attempt: 0 };
  window.tvNativeEvent = function (type, payload) {
    if (type === "retry") {
      spinner.hidden = false;
      setOverlay(false);
      showOsd("Yayın açılmadı, tekrar deneniyor (" + payload + "/5)");
    } else if (type === "stopped") {
      spinner.hidden = true;
      setOverlay(true,
        "<h2>Yayın açılamadı</h2><p>5 deneme başarısız oldu, yayın durduruldu. " +
        "⟳ tuşuyla tekrar bağlanmayı deneyebilirsiniz.</p>");
    } else if (type === "state") {
      nativeState = payload || nativeState;
      if (payload && payload.state === "playing") {
        spinner.hidden = true;
        setOverlay(false);
      }
      syncCtl();
      syncSeek();
      // The native player does not fire DOM events, so the widget (radio only)
      // is refreshed from the state notification instead.
      pushWidgetState(payload && payload.state === "playing");
    }
  };

  // ---- last working mirror -------------------------------------------
  // A channel's mirrors are separate rows ("ATV", "ATV 2"). Remembering the
  // row that last played lets the channel open straight on it, instead of
  // always starting on a dead first mirror. The native player keeps its own
  // copy in SharedPreferences; this is the web/WebView equivalent.
  var MIRROR_KEY = "tv.mirror.v1";
  function siblings(ch) {
    var g = ch.streamGroup || ch.name;
    var l = ch.listName || "Canlı TV";
    return state.channels.filter(function (c) {
      return (c.streamGroup || c.name) === g && (c.listName || "Canlı TV") === l;
    });
  }
  function mirrorKey(ch) {
    return [ch.listName || "Canlı TV", ch.group || "Genel",
            ch.streamGroup || ch.name].join("|");
  }
  function rememberMirror(ch) {
    if (!ch || !ch.url) return;
    try {
      var m = JSON.parse(localStorage.getItem(MIRROR_KEY) || "{}");
      m[mirrorKey(ch)] = ch.url;
      localStorage.setItem(MIRROR_KEY, JSON.stringify(m));
    } catch (e) {}
  }
  function preferredChannel(ch) {
    var url;
    try {
      url = JSON.parse(localStorage.getItem(MIRROR_KEY) || "{}")[mirrorKey(ch)];
    } catch (e) { return ch; }
    if (!url || url === ch.url) return ch;
    var alts = siblings(ch);
    for (var i = 0; i < alts.length; i++) if (alts[i].url === url) return alts[i];
    return ch;
  }

  function play(ch, overrideUrl) {
    ch = preferredChannel(ch);
    state.current = ch;
    state.detail = null;
    showBrowse(false);
    pushRecent(ch);
    render();

    var streams = (ch.urls && ch.urls.length) ? ch.urls.slice() : [{ url: ch.url, label: "" }];
    var start = 0;
    if (overrideUrl) {
      var idx = streams.map(function (s) { return s.url; }).indexOf(overrideUrl);
      if (idx > 0) { streams.splice(0, 0, streams.splice(idx, 1)[0]); }
    }
    state.queue = streams;
    state.streamIdx = start;

    $("#npName").textContent = ch.name;
    $("#npMeta").textContent = (ch.group || "Genel") + " · " + (ch.source || "");
    updateFavBtn();
    updatePlayerInfo();
    var layer = $("#player");
    if (layer) layer.classList.remove("hide");
    showVodInfo(ch);
    loadEpgFor(ch);
    if (window.innerWidth <= 900) $("#sidebar").classList.remove("open");
    if (window.tvOpenPlayer) window.tvOpenPlayer();
    loadStream(0);
  }

  function loadStream(i) {
    clearBlackTimer();
    var ch = state.current;
    if (!ch) return;
    var streams = state.queue || [];
    if (i >= streams.length) {
      spinner.hidden = true;
      destroyHls();
      setOverlay(true,
        "<h2>Yayın açılamadı</h2><p>Denenen tüm yayın adresleri yanıt vermedi. " +
        "Kaynak coğrafi olarak engelli olabilir.</p>");
      return;
    }
    state.streamIdx = i;
    var stream = streams[i];
    var url = stream.url;

    var urlEl = $("#iUrl");
    if (urlEl) urlEl.textContent = url;
    $("#npMeta").textContent = (ch.group || "Genel") +
      (i > 0 ? " · " + ch.name + " " + (i + 1) : " · " + (ch.source || ""));
    if (i > 0) showOsd(ch.name + " " + (i + 1) + " deneniyor");

    // APK: hand the stream to the native ExoPlayer, which retries on its own.
    var np = nativePlayer();
    if (np) {
      destroyHls();
      spinner.hidden = false;
      setOverlay(true, null);
      syncNativeBounds();
      showRadioArt(ch);
      finishInit();
      try {
        np.play(url, ch.name || "", (ch.group || "Genel") + " · " + (ch.source || ""),
                ch.headers ? JSON.stringify(ch.headers) : "");
        // On the phone the player takes the whole screen, so the picture is
        // never a small box in the corner of the list.
        np.setFullscreenMode(true);
      } catch (e) { showFatal(e); }
      return;
    }

    destroyHls();
    spinner.hidden = false;
    setOverlay(true, null);
    video.removeAttribute("src");
    video.load();
    armBlackTimer(stream, i);

    // MAC/portal streams need the session headers, so always go via the proxy.
    var src = ch.headers ? proxyFor(ch, url) : url;
    var lower = url.split("?")[0].toLowerCase();

    // Radio and other plain audio streams: play them directly. They have no
    // picture, so the stage shows the station logo instead of a black frame.
    // HLS audio (.m3u8) still goes through the HLS engine below.
    if (!/\.m3u8(\?|$)/.test(lower) &&
        (contentKind(ch) === "radio" ||
         /\.(mp3|aac|ogg|oga|m4a|opus|wav|flac|pls)(\?|$)/.test(url.toLowerCase()) ||
         /icecast|\.str$|\/stream(\?|$|;)/.test(lower))) {
      showRadioArt(ch);
      // The page is https while many stations are http-only, and .pls files
      // are not playable as-is. Both are handled by the server-side stream
      // relay, so the audio always comes from a same-origin URL.
      video.removeAttribute("crossorigin");
      video.src = "/api/stream?url=" + encodeURIComponent(url) +
        (ch.headers ? "&u=" + encodeURIComponent(ch.headers["User-Agent"] || "") : "");
      finishInit();
      video.onerror = function () { loadStream(i + 1); };
      // A live radio stream has no duration, so the HLS manifest handler never
      // runs and nothing else would start playback; it has to be kicked off
      // here. The rejection is harmless when autoplay is blocked.
      video.play().catch(function () { showOsd("Oynatmak için ▶ tuşuna basın"); });
      return;
    }

    if (/\.(mp4|webm|ogv|m4v|mov)$/.test(lower)) {
      video.src = src;
      finishInit();
      video.onerror = function () { loadStream(i + 1); };
      return;
    }

    if (window.Hls && Hls.isSupported()) {
      startHls(src, i);
      return;
    }

    if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = src; // native HLS (Safari / iOS)
      finishInit();
      video.onerror = function () { loadStream(i + 1); };
      return;
    }

    setOverlay(true, "<h2>Oynatılamıyor</h2><p>Bu tarayıcı HLS akışını desteklemiyor.</p>");
    spinner.hidden = true;
  }

  function startHls(url, index) {
    hls = new Hls({
      lowLatencyMode: true,
      enableWorker: true,
      backBufferLength: 30,
      manifestLoadingTimeOut: 15000,
      fragLoadingTimeOut: 30000,
      xhrSetup: function (xhr) { xhr.withCredentials = false; }
    });

    var triedProxy = false;

    hls.on(Hls.Events.ERROR, function (evt, data) {
      if (!data || !data.fatal) return;
      // Any fatal load problem (CORS, network, bad manifest): retry once
      // through the server-side proxy, which fetches the stream itself.
      if (!triedProxy) {
        triedProxy = true;
        showOsd("Sunucu üzerinden yeniden deneniyor...");
        hls.loadSource(proxied(url, { h: originOf(url) }));
        hls.startLoad();
        return;
      }
      // Still fatal: fall through to the next stream alternative.
      spinner.hidden = true;
      destroyHls();
      loadStream(index + 1);
    });

    hls.on(Hls.Events.MANIFEST_PARSED, function () {
      finishInit();
      video.play().catch(function () { showOsd("Oynatmak için ▶ tuşuna basın"); });
    });

    hls.loadSource(url);
    hls.attachMedia(video);
  }

  function originOf(url) {
    try { return new URL(url).origin + "/"; } catch (e) { return ""; }
  }

  // Edit a channel's name and stream URL, and check that the URL responds.
  // Saved edits are persisted as overrides so a fixed address survives the
  // next playlist refresh.
  function inspect(ch) {
    var dlg = $("#inspectDlg");
    $("#inspectTitle").textContent = ch.name + " — düzenle";
    $("#inspectHint").textContent =
      "Adres zamanla ölürse buradan düzeltin; kaydettiğiniz adres listede kalıcı olur.";
    var body = $("#inspectBody");
    body.innerHTML = "";
    body.innerHTML =
      '<label class="field"><span>Kanal adı</span>' +
        '<input id="editName" type="text" /></label>' +
      '<label class="field"><span>Yayın adresi (m3u8 / mp4 / mpd)</span>' +
        '<input id="editUrl" type="url" /></label>' +
      '<div class="inspect-row pending" id="insp-0">' +
        '<span class="st">kontrol ediliyor…</span><span class="u"></span></div>' +
      '<div class="dlg-actions">' +
        '<button type="button" class="btn" id="editSave">Kaydet</button>' +
        '<button type="button" class="btn ghost" id="editProbe">Adresi kontrol et</button>' +
        '<button type="button" class="btn danger" id="editDelete">🗑 Kanalı sil</button>' +
      '</div>';
    $("#editName").value = ch.name;
    $("#editUrl").value = ch.url;
    if (!dlg.open) dlg.showModal();

    function probe() {
      var row = $("#insp-0");
      var url = $("#editUrl").value.trim();
      row.className = "inspect-row pending";
      row.querySelector(".st").textContent = "kontrol ediliyor…";
      row.querySelector(".u").textContent = url;
      api("/api/probe?url=" + encodeURIComponent(url)).then(function (data) {
        var r = ((data && data.results) || [])[0] || {};
        row.classList.remove("pending");
        row.classList.add(r.ok ? "ok" : "bad");
        var detail = r.ok ? "✔ çalışıyor" : "✘ açılmıyor";
        if (r.kind === "playlist" && r.variants) {
          detail += " (HLS liste, " + r.variants + " varyant)";
        } else if (r.kind) {
          detail += " (" + r.kind + ")";
        }
        if (r.error) detail += " — " + r.error;
        else if (r.status && r.status !== 200) detail += " — HTTP " + r.status;
        row.querySelector(".st").textContent = detail;
      }).catch(function (err) {
        row.className = "inspect-row bad";
        row.querySelector(".st").textContent = "İnceleme başarısız: " + err.message;
      });
    }

    $("#editProbe").onclick = probe;
    $("#editDelete").onclick = function () {
      dlg.close();
      deleteChannel(ch);
    };
    $("#editSave").onclick = function () {
      var nm = $("#editName").value.trim();
      var url = $("#editUrl").value.trim();
      if (!nm || !url) { $("#inspectHint").textContent = "Ad ve adres gerekli"; return; }
      var wasPlaying = state.current === ch;
      ch.name = nm;
      ch.url = url;
      var key = chKey(ch);
      state.overrides[key] = { name: nm, url: url, logo: ch.logo || "" };
      persist();
      buildSources();
      buildGroups();
      render();
      $("#inspectHint").textContent = "Kaydedildi — " + nm;
      if (wasPlaying) play(ch);
    };
    probe();
  }

  // Jump to the next/previous channel in the visible list. Used from the
  // remote's channel and left/right keys while watching, so the video
  // forward/back buttons change channel instead of seeking.
  function zap(delta) {
    var list = state.filtered.length ? state.filtered : state.channels;
    if (!list.length) return;
    var cur = state.current;
    var idx = 0;
    if (cur) {
      for (var i = 0; i < list.length; i++) {
        if (list[i] === cur || list[i].url === cur.url) { idx = i; break; }
      }
    }
    var next = (idx + delta + list.length) % list.length;
    play(list[next]);
  }
  window.tvZap = zap;

  /* ----------------------- player layer ------------------------------ */
  // A plain DOM layer over the picture (VLC / KMPlayer style) instead of the
  // WebView's built-in controls, which cannot zap channels and look different
  // on every device. It stays visible; only the big centre button fades out.
  var seeking = false;
  var hideTimer = null;

  // Show the layer, then fade it away after a few seconds of no interaction.
  function showUi(autoHide) {
    var layer = $("#player");
    if (!layer) return;
    layer.classList.remove("hidden-ui");
    clearTimeout(hideTimer);
    if (autoHide) hideTimer = setTimeout(hideUi, 4000);
  }

  function hideUi() {
    var layer = $("#player");
    if (!layer) return;
    // Never hide while paused: the play button has to stay reachable.
    if (video.paused) return;
    layer.classList.add("hidden-ui");
  }

  function toggleUi() {
    var layer = $("#player");
    if (!layer) return;
    if (layer.classList.contains("hidden-ui")) showUi(true);
    else hideUi();
  }

  // Fill the screen with the picture: hides the browser/system bars and the
  // control layer, so only the video is left.
  function toggleFill() {
    var stage = $("#stage");
    var btn = $("#ctlFull");
    var on = document.fullscreenElement || stage.classList.contains("fill");
    var np = nativePlayer();
    if (on) {
      if (document.fullscreenElement) { document.exitFullscreen(); }
      stage.classList.remove("fill");
      if (btn) btn.classList.remove("on");
      if (np) { try { np.setFullscreenMode(false); } catch (e) {} syncNativeBounds(); }
      showUi(true);
      return;
    }
    stage.classList.add("fill");
    if (btn) btn.classList.add("on");
    if (np) { try { np.setFullscreenMode(true); } catch (e) {} }
    hideUi();
    if (stage.requestFullscreen) {
      stage.requestFullscreen().catch(function () {});
    }
  }

  function fmtTime(sec) {
    if (!isFinite(sec) || sec < 0) return "--:--";
    sec = Math.floor(sec);
    var h = Math.floor(sec / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    function p(n) { return (n < 10 ? "0" : "") + n; }
    return (h > 0 ? h + ":" : "") + p(m) + ":" + p(s);
  }

  function setLive(on) {
    var el = $("#plLive");
    if (el) el.classList.toggle("off", !on);
  }

  function updatePlayerInfo() {
    var ch = state.current;
    var t = $("#plTitle"), g = $("#plGroup");
    if (t) t.textContent = ch ? ch.name : "Kanal seçin";
    if (g) g.textContent = ch ? (ch.group || "Genel") : "";
    var logo = $("#plLogo");
    if (logo) {
      if (ch && ch.logo) { logo.src = ch.logo; }
      else { logo.removeAttribute("src"); }
    }
    var song = $("#plSong");
    if (song) song.textContent = ch && isRadioNow() ? icyTitle : "";
  }

  function ctlSeek(delta) {
    var np = nativePlayer();
    if (np) { try { np.seekBy(delta); } catch (e) {} return; }
    var info = seekInfo();
    if (info.end <= info.start) {
      showOsd("Canlı yayında atlama yok");
      return;
    }
    var target = info.pos + delta;
    video.currentTime = Math.max(info.start, Math.min(info.end - 0.5, target));
    showOsd(delta > 0 ? "10 sn ileri" : "10 sn geri");
  }

  function ctlToggle() {
    var np = nativePlayer();
    if (np) { try { np.toggle(); } catch (e) {} showUi(true); return; }
    if (video.paused) video.play().catch(function () {});
    else video.pause();
    syncCtl();
  }

  function syncCtl() {
    var np = nativePlayer();
    var paused = np ? (nativeState.state !== "playing") : video.paused;
    var icon = paused ? "▶" : "❚❚";
    ["#ctlPlay", "#plCenter"].forEach(function (sel) {
      var b = $(sel);
      if (b) b.textContent = icon;
    });
    var center = $("#plCenter");
    if (center) center.classList.toggle("show", paused);
    var mute = $("#ctlMute");
    if (mute) {
      var muted = np ? !!nativeState.muted : (video.muted || !video.volume);
      mute.textContent = muted ? "🔇" : "🔊";
      mute.classList.toggle("on", muted);
    }
  }

  // Live HLS still exposes a seekable window (the DVR buffer), so the bar can
  // scrub inside it. Only a stream with no window at all is "true live".
  function seekInfo() {
    var np = nativePlayer();
    if (np) {
      var dur = nativeState.duration;
      if (dur && dur > 0) {
        return { live: false, start: 0, end: dur, pos: nativeState.position };
      }
      return { live: true, start: 0, end: 0, pos: 0 };
    }
    var dur2 = video.duration;
    if (isFinite(dur2) && dur2 > 0) {
      return { live: false, start: 0, end: dur2, pos: video.currentTime };
    }
    var s = video.seekable;
    if (s && s.length) {
      var start = s.start(s.length - 1);
      var end = s.end(s.length - 1);
      if (end > start) {
        return { live: true, start: start, end: end, pos: video.currentTime };
      }
    }
    return { live: true, start: 0, end: 0, pos: 0 };
  }

  function syncSeek() {
    if (seeking) return;
    var info = seekInfo();
    var canSeek = info.end > info.start;
    setLive(!canSeek || info.live);
    var range = $("#plRange");
    var cur = $("#plCur"), d = $("#plDur");
    if (range) {
      range.disabled = !canSeek;
      if (canSeek) {
        range.value = Math.round(((info.pos - info.start) / (info.end - info.start)) * 1000);
      }
    }
    if (!canSeek) {
      if (cur) cur.textContent = "CANLI";
      if (d) d.textContent = "";
      return;
    }
    // A live window shows how far behind the edge we are instead of a clock.
    var behind = Math.max(0, info.end - info.pos);
    if (cur) cur.textContent = info.live ? "-" + fmtTime(behind) : fmtTime(info.pos);
    if (d) d.textContent = info.live ? "CANLI" : fmtTime(info.end);
  }

  function bindControls() {
    var layer = $("#player");
    if (!layer) return;
    var stage = $("#stage");

    on("#ctlPrev", function () { zap(-1); });
    on("#ctlNext", function () { zap(1); });
    on("#ctlPlay", function () { ctlToggle(); });
    on("#plCenter", function () { ctlToggle(); });
    on("#ctlBack", function () { ctlSeek(-10); });
    on("#ctlFwd", function () { ctlSeek(10); });
    on("#ctlMute", function () {
      var np = nativePlayer();
      if (np) {
        var m = !(nativeState.muted);
        try { np.setMuted(m); } catch (e) {}
        nativeState.muted = m;
      } else {
        video.muted = !video.muted;
        if (!video.muted && video.volume === 0) video.volume = 1;
      }
      syncCtl();
    });
    on("#plReload", function () { if (state.current) play(state.current); });
    on("#ctlFull", function () { toggleFill(); });

    var range = $("#plRange");
    if (range) {
      range.addEventListener("input", function () {
        seeking = true;
        var np = nativePlayer();
        if (np) {
          var info = seekInfo();
          if (info.end > info.start) {
            try { np.seekTo(info.start + (range.value / 1000) * (info.end - info.start)); }
            catch (e) {}
          }
          return;
        }
        var info2 = seekInfo();
        if (info2.end > info2.start) {
          video.currentTime = info2.start +
            (range.value / 1000) * (info2.end - info2.start);
        }
      });
      range.addEventListener("change", function () {
        seeking = false;
        syncSeek();
      });
    }

    // Tapping the picture toggles play/pause, like a phone video player.
    if (stage) {
      stage.addEventListener("click", function (e) {
        if (e.target.closest && e.target.closest(".player")) return;
        // While the layer is showing, a tap on the picture hides it (fill
        // screen); once hidden, a tap toggles play/pause.
        var layer = $("#player");
        if (layer && !layer.classList.contains("hidden-ui")) { hideUi(); return; }
        ctlToggle();
      });
    }

    ["play", "pause", "volumechange", "playing"].forEach(function (evt) {
      video.addEventListener(evt, syncCtl);
    });
    video.addEventListener("play", function () { showUi(true); });
    video.addEventListener("pause", function () { showUi(false); });
    video.addEventListener("timeupdate", syncSeek);
    video.addEventListener("durationchange", syncSeek);
    video.addEventListener("loadedmetadata", syncSeek);
    // Live streams do not always fire timeupdate, so keep the bar moving.
    setInterval(function () { if (!video.paused) syncSeek(); }, 1000);

    updatePlayerInfo();
    syncCtl();
    syncSeek();
  }

  function finishInit() {
    spinner.hidden = true;
    setOverlay(false);
    video.addEventListener("playing", onPlaying, { once: true });
    video.addEventListener("waiting", function () { spinner.hidden = false; }, { once: true });
  }

  function onPlaying() {
    spinner.hidden = true;
    setOverlay(false);
    syncCtl();
    syncSeek();
    showUi(true);
    updatePlayerInfo();
    var cur = state.current;
    // This mirror actually produced a picture, so remember it for next time.
    rememberMirror(cur);
    clearBlackTimer();
    // Radio keeps its logo on stage: there is no video frame to show instead.
    if (!cur || contentKind(cur) !== "radio") hideVodInfo();
    var t = video.duration;
    if (t && isFinite(t)) {
      showOsd("▶ " + (cur ? cur.name : ""));
    } else {
      showOsd("🔴 CANLI · " + (cur ? cur.name : ""));
    }
    pushWidgetState(true);
    if (isRadioNow()) startNowPolling();
  }

  /* ------------------------- widget / background --------------------- */
  // The Android home-screen widgets and the lock-screen / car media controls
  // are fed from here. On the web build there is no bridge, so this is a
  // no-op; a widget press is turned back into a command by the native side.
  var widgetPlaying = false;
  var icyTitle = "";
  var nowTimer = null;

  function widgetBridge() {
    return (typeof window.AndroidWidget !== "undefined") ? window.AndroidWidget : null;
  }

  function isRadioNow() {
    return !!(state.current && contentKind(state.current) === "radio");
  }

  function pushWidgetState(playing) {
    // The widget reflects the player that is actually running: the native
    // ExoPlayer in the APK, the <video> element in the browser.
    var np = nativePlayer();
    if (np) playing = nativeState.state === "playing";
    widgetPlaying = !!playing;
    var b = widgetBridge();
    if (!b) return;
    var cur = state.current;
    if (!cur || !isRadioNow()) {
      b.setRadio(false);
      b.nowPlaying("", "", false);
      return;
    }
    b.setRadio(true);
    var sub = (cur.group || "Radyo") + " · " + (cur.source || "Radyo");
    // Metadata from the stream (ICY) replaces the station name once known.
    b.nowPlaying(icyTitle || cur.name, sub, !!playing);
  }

  function widgetCmd(what) {
    var np = nativePlayer();
    if (!isRadioNow()) {
      // Widgets only drive radio; if a TV channel is up, jump to the last
      // radio station so a widget press always does something useful.
      var r = lastRadio();
      if (!r) return;
      play(r);
      if (what === "TOGGLE") setTimeout(function () { toggleRadio(); }, 300);
      return;
    }
    if (what === "TOGGLE") toggleRadio();
    else if (what === "PLAY") { if (np) np.toggle(); else video.play().catch(function () {}); }
    else if (what === "PAUSE") { if (np) { if (nativeState.state === "playing") np.toggle(); } else video.pause(); }
    else if (what === "STOP") { if (np) np.stop(); else video.pause(); }
    else if (what === "NEXT") zap(1);
    else if (what === "PREV") zap(-1);
  }

  function toggleRadio() {
    var np = nativePlayer();
    if (np) { np.toggle(); return; }
    if (video.paused) video.play().catch(function () {}); else video.pause();
  }

  function lastRadio() {
    var list = state.channels.filter(function (c) { return contentKind(c) === "radio"; });
    if (!list.length) return null;
    var recent = (state.recent || []).filter(function (c) {
      return contentKind(c) === "radio";
    });
    return recent.length ? recent[0] : list[0];
  }

  window.tvWidgetCmd = widgetCmd;
  window.tvKeepAlive = function () { pushWidgetState(!video.paused); };

  function startNowPolling() {
    if (nowTimer) { clearInterval(nowTimer); nowTimer = null; }
    pollNowPlaying();
    // Every 20 s is often enough for a song title and keeps the stream server
    // from being hammered with metadata probes.
    nowTimer = setInterval(pollNowPlaying, 20000);
  }

  // Keep the widget and the media notification in step with the player.
  video.addEventListener("play", function () { pushWidgetState(true); });
  video.addEventListener("playing", function () { pushWidgetState(true); });
  video.addEventListener("pause", function () { pushWidgetState(false); });
  video.addEventListener("ended", function () { pushWidgetState(false); });

  function updateFavBtn() {
    var on = state.current && isFavOf(state.current);
    var b = $("#favBtn");
    b.textContent = on ? "★" : "☆";
    b.classList.toggle("on", !!on);
  }

  function escapeHtml(s) {
    return s.replace(/[&<>"']/g, function (c) {
      return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c];
    });
  }

  /* -------------------------------- EPG ------------------------------ */
  // EPG is parsed and kept in state so it stays available for the channel
  // info panel, but nothing renders below the player any more.
  function loadEpgFor(ch) {
    if (!state.epgUrl) return;
    var key = state.epgUrl;
    if (state.epg[key]) return;

    get("/api/fetch?url=" + encodeURIComponent(key))
      .then(function (r) { return r.text(); })
      .then(function (xml) { state.epg[key] = parseXmltv(xml); })
      .catch(function () {});
  }

  function parseXmltv(xml) {
    var byId = {};
    var doc = new DOMParser().parseFromString(xml, "text/xml");
    var progs = doc.getElementsByTagName("programme");
    for (var i = 0; i < progs.length; i++) {
      var p = progs[i];
      var chId = p.getAttribute("channel");
      var start = parseXmlTime(p.getAttribute("start"));
      var stop = parseXmlTime(p.getAttribute("stop"));
      var titleEl = p.getElementsByTagName("title")[0];
      var descEl = p.getElementsByTagName("desc")[0];
      if (!chId || !start) continue;
      (byId[chId] = byId[chId] || []).push({
        start: start,
        stop: stop || start + 3600,
        title: titleEl ? titleEl.textContent : "(başlıksız)",
        desc: descEl ? descEl.textContent : ""
      });
    }
    Object.keys(byId).forEach(function (k) {
      byId[k].sort(function (a, b) { return a.start - b.start; });
    });
    return byId;
  }

  function parseXmlTime(s) {
    if (!s) return 0;
    var m = s.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})/);
    if (!m) return 0;
    var d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]));
    return d.getTime();
  }

  function fmtTime(ms) {
    var d = new Date(ms);
    return ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2);
  }

  function renderEpg(ch) {
    // EPG panel removed from the layout; parsing stays for future use.
    return;
  }

  /* ---------------------------- playlists ---------------------------- */
  /* ------------------------------ events ----------------------------- */
  // $ returns null for an element this build does not render, so every
  // handler is attached through on() to keep one missing node from aborting
  // the whole boot (the old WebView has no dev console to explain the stop).
  function on(sel, handler) {
    var el = $(sel);
    if (el) el.onclick = handler;
  }

  function bind() {
    // On a phone the tree is the list: the flat channel pane only appears for
    // favourites or an active search, so it never duplicates the tree.
    function syncListMode() {
      var sb = $("#sidebar");
      if (!sb) return;
      var flat = state.tab === "fav" || !!state.query;
      sb.classList.toggle("flat-list", flat);
      var h = $("#listHead");
      if (h) h.hidden = !flat;
      if (h) h.textContent = state.query
        ? "Arama sonuçları (" + state.filtered.length + ")"
        : "Favoriler (" + state.filtered.length + ")";
    }
    window.tvSyncListMode = syncListMode;

    // Load more rows as the user nears the bottom, so the first paint stays
    // small but the whole list is still reachable by scrolling.
    var listEl = $("#channels");
    if (listEl) {
      var scrollTimer = null;
      listEl.addEventListener("scroll", function () {
        if (scrollTimer) return;
        scrollTimer = setTimeout(function () {
          scrollTimer = null;
          if (renderedCount >= state.filtered.length) return;
          if (listEl.scrollTop + listEl.clientHeight >= listEl.scrollHeight - 320) {
            appendChannels(listEl, renderedCount, PAGE_MORE);
          }
        }, 120);
      });
    }

    // ---- bottom navigation (phone layout) ----
    function activateTab(name) {
      $$(".tabbtn", $("#tabbar")).forEach(function (b) {
        b.classList.toggle("active", b.dataset.tab === name);
      });
      var settings = $("#settings");
      if (settings) settings.classList.toggle("hide", name !== "settings");
      if (name === "live" || name === "radio") {
        var list = name === "live" ? "Canlı TV" : "Radyo";
        state.tab = "all";
        state.source = list;
        state.kind = name === "live" ? "live" : "radio";
        state.group = "all";
        clearSearch();
        state.expanded[list] = true;
        renderTree();
        buildGroups();
        buildKindTabs();
        render();
      } else if (name === "fav") {
        state.tab = "fav";
        state.source = "all";
        state.kind = "all";
        renderTree();
        buildGroups();
        render();
      }
      syncListMode();
      var sb = $("#sidebar");
      if (sb) sb.classList.remove("open");
    }
    $$(".tabbtn", $("#tabbar")).forEach(function (b) {
      b.addEventListener("click", function () { activateTab(b.dataset.tab); });
    });

    // ---- settings overlay (gear button) ----
    function openSettings(on) {
      var s = $("#settings");
      if (s) s.classList.toggle("hide", !on);
      syncThemeSeg();
    }
    on("#setBtn", function () { openSettings(true); });
    on("#settingsBack", function () { openSettings(false); });

    function syncThemeSeg() {
      var cur = document.documentElement.getAttribute("data-theme") === "light"
        ? "light" : "dark";
      $$("#setTheme button").forEach(function (b) {
        b.classList.toggle("active", b.dataset.theme === cur);
      });
    }
    $$("#setTheme button").forEach(function (b) {
      b.addEventListener("click", function () {
        document.documentElement.setAttribute("data-theme", b.dataset.theme);
        try { localStorage.setItem("tv.theme", b.dataset.theme); } catch (e) {}
        syncThemeSeg();
      });
    });

    function setSwitch(sel, on) {
      var el = $(sel);
      if (el) el.setAttribute("aria-checked", on ? "true" : "false");
    }
    var repeatOn = false;
    var muteOn = false;
    on("#setRepeat", function () {
      repeatOn = !repeatOn;
      setSwitch("#setRepeat", repeatOn);
      var np = nativePlayer();
      if (np) { try { np.setRepeatMode(repeatOn); } catch (e) {} }
      showOsd(repeatOn ? "Tekrar açık" : "Tekrar kapalı");
    });
    on("#setMute", function () {
      muteOn = !muteOn;
      setSwitch("#setMute", muteOn);
      var np = nativePlayer();
      if (np) { try { np.setMuted(muteOn); } catch (e) {} }
      else video.muted = muteOn;
      syncCtl();
    });
    on("#setAdd", function () { openSettings(false); $("#addDlg").showModal(); });
    on("#setClearRecent", function () {
      state.recent = [];
      state.recentsByList = {};
      persist();
      showOsd("Son izlenenler temizlendi");
    });

    // ---- backup download / restore ------------------------------------
    // One JSON carries everything a user cannot get back from a fresh
    // install: favourites, added channels, edits and deletions. Restoring it
    // on a new box brings those back before the store round-trip finishes.
    on("#setExport", function () {
      try {
        var payload = snapshot();
        payload.app = "TV Player";
        payload.version = APP_VERSION;
        payload.exportedAt = new Date().toISOString();
        var blob = new Blob([JSON.stringify(payload, null, 1)],
          { type: "application/json" });
        var a = document.createElement("a");
        a.href = URL.createObjectURL(blob);
        a.download = "tv-yedek.json";
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
        showOsd("Yedek indirildi");
      } catch (e) { showOsd("Yedek alınamadı"); }
    });
    on("#setImport", function () {
      var input = $("#setImportFile");
      if (input) input.click();
    });
    on("#setImportFile", function (e) {
      var file = e.target.files && e.target.files[0];
      if (!file) return;
      var reader = new FileReader();
      reader.onload = function () {
        try {
          var raw = JSON.parse(String(reader.result));
          if (!applySnapshot(raw)) throw new Error("bad");
          persist();
          applyOverrides();
          applyAdded();
          applyDeleted();
          buildSources();
          buildGroups();
          buildKindTabs();
          render();
          showOsd("Yedek geri yüklendi");
        } catch (err) {
          showOsd("Geçersiz yedek dosyası");
        }
        e.target.value = "";
      };
      reader.onerror = function () { showOsd("Dosya okunamadı"); };
      reader.readAsText(file);
    });
    try {
      var about = $("#setAbout");
      if (about) about.textContent = "TV Player · " + state.channels.length + " kanal";
    } catch (e) {}

    // ---- open the player when a channel is chosen, close it with back ----
    function closePlayer() {
      var app = $("#app");
      if (app) app.classList.remove("playing");
      var stage = $("#stage");
      if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen();
      if (stage) stage.classList.remove("fill");
      if (nativePlayer()) { try { nativePlayer().stop(); } catch (e) {} }
      else { try { video.pause(); } catch (e) {} }
      clearBlackTimer();
    }
    window.tvOpenPlayer = function () {
      var app = $("#app");
      if (app) app.classList.add("playing");
      syncNativeBounds();
      setTimeout(syncNativeBounds, 120);
    };
    window.tvClosePlayer = closePlayer;
    on("#playerBack", closePlayer);

    var searchTimer = null;
    var onSearch = function (e) {
      var value = e.target.value.trim();
      var clr = $("#clearSearch");
      if (clr) clr.hidden = !value;
      // Typing fires input+keyup+change+search for every keystroke; without
      // this each character re-filtered and re-rendered the whole list.
      clearTimeout(searchTimer);
      searchTimer = setTimeout(function () {
        state.query = value;
        if (window.tvSyncListMode) window.tvSyncListMode();
        render();
      }, 200);
    };
    var search = $("#search");
    if (search) {
      ["input", "keyup", "change", "search"].forEach(function (evt) {
        search.addEventListener(evt, onSearch);
      });
    }
    on("#clearSearch", function () {
      if (search) search.value = "";
      state.query = "";
      $("#clearSearch").hidden = true;
      if (window.tvSyncListMode) window.tvSyncListMode();
      render();
    });

    on("#favBtn", function () {
      if (state.current) toggleFav(state.current);
    });
    on("#reloadBtn", function () {
      if (state.current) play(state.current);
    });
    on("#fullBtn", function () {
      var stage = $("#stage");
      if (document.fullscreenElement) document.exitFullscreen();
      else if (stage && stage.requestFullscreen) stage.requestFullscreen();
    });
    on("#pipBtn", function () {
      if (document.pictureInPictureElement) document.exitPictureInPicture();
      else if (video.requestPictureInPicture) {
        video.requestPictureInPicture().catch(function () { showOsd("PiP desteklenmiyor"); });
      }
    });
    on("#themeBtn", function () {
      var cur = document.documentElement.getAttribute("data-theme") === "light" ? "dark" : "light";
      document.documentElement.setAttribute("data-theme", cur);
      try { localStorage.setItem("tv.theme", cur); } catch (e) {}
    });
    on("#menuBtn", function () {
      var sb = $("#sidebar");
      if (sb) sb.classList.toggle("open");
    });

    // dialogs
    on("#openAdd", function () { $("#addDlg").showModal(); });
    on("#inspectClose", function () { $("#inspectDlg").close(); });

    on("#saveChannel", function () {
      var name = $("#addName").value.trim();
      var url = $("#addUrl").value.trim();
      if (!name || !url) { showOsd("Ad ve adres gerekli"); return; }
      var ch = normalize({
        name: name, url: url,
        group: $("#addGroup").value.trim() || "Özel",
        logo: $("#addLogo").value.trim(),
        source: "Özel"
      });
      ch.listName = "Özel kanallar";
      // Remember the raw record so it is restored on the next load and survives
      // a box reset. Only the fields the add dialog owns are stored.
      state.added = state.added || [];
      state.added.unshift({ name: ch.name, url: ch.url, group: ch.group,
                            logo: ch.logo, listName: ch.listName });
      state.channels.unshift(ch);
      state.loaded["Özel kanallar"] = true;
      persist();
      buildSources();
      buildGroups(); render();
      $("#addDlg").close();
      play(ch);
    });

    // keyboard shortcuts
    document.addEventListener("keydown", function (e) {
      if (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA") return;
      // Android TV / box remotes send these raw key codes rather than names.
      // 13=OK, 427=CH+, 428=CH-, 8/461=BACK.
      var code = e.keyCode || 0;
      if (code === 13) {
        var focused = document.activeElement;
        if (focused && focused.classList && focused.classList.contains("ch")) {
          e.preventDefault();
          focused.click();
          return;
        }
      }
      if (code === 427) { e.preventDefault(); zap(1); return; }
      if (code === 428) { e.preventDefault(); zap(-1); return; }
      // Channel zapping: the remote's left/right and channel buttons switch
      // channel instead of seeking the video.
      if (e.key === "ArrowRight" || e.key === "MediaTrackNext" ||
          e.key === "ChannelUp") { e.preventDefault(); zap(1); return; }
      if (e.key === "ArrowLeft" || e.key === "MediaTrackPrevious" ||
          e.key === "ChannelDown") { e.preventDefault(); zap(-1); return; }
      if (e.key === "/") { e.preventDefault(); $("#search").focus(); }
      if (e.key === "f") $("#fullBtn").click();
      if (e.key === "p") $("#pipBtn").click();
      if (e.key === "m" && state.current) { video.muted = !video.muted; showOsd(video.muted ? "🔇" : "🔊"); }
    });
  }

  /* ------------------------------- boot ------------------------------ */
  function boot() {
    try {
      var theme = localStorage.getItem("tv.theme");
      if (theme) document.documentElement.setAttribute("data-theme", theme);
    } catch (e) {}

    // A new build must not be served through an old service-worker cache.
    try { enforceVersion(); } catch (e) {}

    loadLocal();
    // In the APK the native ExoPlayer draws the video above the WebView and
    // brings its own auto-hiding controller, so the web layer is not used.
    if (nativePlayer()) {
      var layer = $("#player");
      if (layer) layer.classList.add("hide");
      document.documentElement.classList.add("native-player");
    }
    // A broken binding must never stop the channel list from loading.
    try { bind(); bindControls(); } catch (e) { showFatal(e); }

    // Paint the embedded list first: it ships inside the APK, so the user sees
    // channels with no server round-trip. The shared server store (favourites,
    // overrides, extra channels) is merged afterwards in the background.
    loadChannels().then(function () {
      if (state.channels.length) {
        setOverlay(true,
          "<h2>" + state.channels.length + " kanal hazır</h2>" +
          "<p>Başlamak için listeden bir kanal seçin.</p>");
      }
    }).catch(function (err) {
      setOverlay(true, "<h2>Liste yüklenemedi</h2><p>" +
        escapeHtml(String(err && err.message || err)) + "</p>");
      showFatal(err);
    }).then(function () {
      // Best effort: a slow `/api/store` must never delay the first frame.
      loadServerStore().then(function () {
        var epg = $("#epgUrl");
        if (state.epgUrl && epg) epg.value = state.epgUrl;
        applyOverrides();
        applyAdded();
        applyDeleted();
        buildSources();
        buildGroups();
        buildKindTabs();
        render();
      });
    });
  }

  // Surface errors on screen: on a TV box there is no dev console, so a
  // screenshot of this banner is the only way to diagnose a blank list.
  function showFatal(err) {
    try {
      var box = document.getElementById("fatal");
      if (!box) {
        box = document.createElement("div");
        box.id = "fatal";
        box.style.cssText = "position:fixed;left:0;right:0;bottom:0;z-index:99999;" +
          "background:#7f1d1d;color:#fff;font:13px/1.4 monospace;padding:8px 10px;" +
          "white-space:pre-wrap;max-height:45%;overflow:auto";
        (document.body || document.documentElement).appendChild(box);
      }
      box.textContent = "TV Player hata: " + String(err && err.message || err) +
        (err && err.stack ? "\n" + err.stack : "");
    } catch (e) {}
  }
  window.__tvFatal = showFatal;

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
  // Tell the native side the page is live so a widget press that started the
  // app is not lost while the WebView was still loading.
  window.addEventListener("load", function () {
    var b = widgetBridge();
    if (b && b.ready) { try { b.ready(); } catch (e) {} }
  });
})();
