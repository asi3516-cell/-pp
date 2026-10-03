/* IPTV Player - front-end logic */
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
    epgUrl: "",
    epg: {},
    current: null,
    group: "all",
    tab: "all",
    kind: "all",
    source: "Canlı TV",
    sources: [],
    expanded: { "Canlı TV": true },
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
  var LS_KEY = "iptv.state.v1";

  function snapshot() {
    return {
      favorites: state.favorites,
      favoritesByList: state.favoritesByList,
      recent: state.recent,
      recentsByList: state.recentsByList,
      playlists: state.playlists,
      overrides: state.overrides,
      deleted: state.deleted,
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
      fetch("/api/store", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          favorites: state.favorites,
          favoritesByList: state.favoritesByList,
          recent: state.recent,
          recentsByList: state.recentsByList,
          playlists: state.playlists,
          overrides: state.overrides,
          deleted: state.deleted,
          settings: { epgUrl: state.epgUrl, ui: { source: state.source, kind: state.kind } }
        })
      }).catch(function () { /* offline: localStorage still has it */ });
    }, 400);
  }

  /* ------------------------------ api -------------------------------- */
  function api(path) {
    return fetch(path).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
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
  function contentKind(ch) {
    var u = (ch.url || "").toLowerCase();
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
      fetch("/api/fetch?url=" + encodeURIComponent(pl.url))
        .then(function (r) { return r.text(); })
        .then(function (t) { done(parseM3U(t, label)); })
        .catch(function (e) { fail(e.message || "liste indirilemedi"); });
    }
  }

  function loadChannels() {
    return api("/api/channels").then(function (data) {
      state.channels = (data.channels || []).map(function (c) {
        // Radios are bundled alongside TV but are their own top-level source,
        // so they get their own list name and never mix into Canlı TV.
        c.listName = contentKind(c) === "radio" ? "Radyo" : "Canlı TV";
        return c;
      });
      state.channels = normalizeLogos(dedupe(state.channels));
      state.channels = flattenStreams(state.channels);
      applyOverrides();
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

  // List picker shown in Settings. The bundled list is the default so the app
  // always opens with something; personal lists are picked from here.
  function renderListPicker() {
    var sel = $("#setList");
    if (!sel) return;
    var opts = [
      { v: "Canlı TV", t: "📌 Canlı TV" },
      { v: "Radyo", t: "📻 Radyo" },
      { v: "fav:Canlı TV", t: "⭐ Canlı TV favorileri" },
      { v: "fav:Radyo", t: "📻 Radyo favorileri" }
    ];
    state.sources.forEach(function (s) {
      if (s.name === "Canlı TV" || s.name === "Radyo") return;
      opts.push({ v: s.name, t: s.name });
      opts.push({ v: "fav:" + s.name, t: "⭐ " + s.name + " favorileri" });
    });
    sel.innerHTML = "";
    opts.forEach(function (o) {
      var op = document.createElement("option");
      op.value = o.v;
      op.textContent = o.t;
      if (o.v === (state.source || "Canlı TV")) op.selected = true;
      sel.appendChild(op);
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

  // List picker shown from the sidebar's "Liste Seç" button. Selecting a list
  // makes it the active one and opens it, so you land straight on its channels.
  function renderListsDialog() {
    var box = $("#listsBody");
    if (!box) return;
    box.innerHTML = "";
    // The list picker shows one favorites row per list, so opening a list's
    // favorites is a single tap and its count is that list's own count.
    var rows = state.sources.slice();
    if (!rows.some(function (s) { return s.name === "Canlı TV"; })) {
      rows.unshift({ name: "Canlı TV", count: 0 });
    }
    rows.forEach(function (s) {
      var fav = document.createElement("button");
      fav.type = "button";
      fav.className = "list-row" + (state.tab === "fav" && state.source === s.name ? " active" : "");
      var n = favList(s.name).length;
      fav.innerHTML = '<span class="ln">⭐ ' + escapeHtml(s.name) + " favorileri</span>" +
        '<span class="lc muted">' + n + " kanal</span>";
      fav.onclick = function () {
        selectTab("fav", "all");
        state.source = s.name;
        buildGroups();
        render();
        $("#listsDlg").close();
      };
      box.appendChild(fav);

      var b = document.createElement("button");
      b.type = "button";
      b.className = "list-row" + (state.tab === "all" && state.source === s.name ? " active" : "");
      b.innerHTML = '<span class="ln">' + escapeHtml(s.name) + "</span>" +
        '<span class="lc muted">' + s.count + " kanal</span>";
      b.onclick = function () {
        selectList(s.name);
        $("#listsDlg").close();
      };
      box.appendChild(b);
    });
  }

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
            gkids.appendChild(treeLeaf({
              label: g.name, count: g.count,
              active: state.group === g.name,
              onPick: function () { selectGroup(o.key, activeKind, g.name); }
            }));
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

  function logoHue(name) {
    var h = 0;
    for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
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
    box.className = "channel-list";
    box.innerHTML = "";
    var frag = document.createDocumentFragment();

    state.filtered.slice(0, 1500).forEach(function (ch) {
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
      frag.appendChild(node);
    });

    box.appendChild(frag);
    $("#chCount").textContent = state.filtered.length + " / " + state.channels.length + " kanal";
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

  function play(ch, overrideUrl) {
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
    showVodInfo(ch);
    loadEpgFor(ch);
    if (window.innerWidth <= 900) $("#sidebar").classList.remove("open");
    loadStream(0);
  }

  function loadStream(i) {
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

    destroyHls();
    spinner.hidden = false;
    setOverlay(true, null);
    video.removeAttribute("src");
    video.load();

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
  window.iptvZap = zap;

  function finishInit() {
    spinner.hidden = true;
    setOverlay(false);
    video.addEventListener("playing", onPlaying, { once: true });
    video.addEventListener("waiting", function () { spinner.hidden = false; }, { once: true });
  }

  function onPlaying() {
    spinner.hidden = true;
    setOverlay(false);
    var cur = state.current;
    // Radio keeps its logo on stage: there is no video frame to show instead.
    if (!cur || contentKind(cur) !== "radio") hideVodInfo();
    var t = video.duration;
    if (t && isFinite(t)) {
      showOsd("▶ " + (cur ? cur.name : ""));
    } else {
      showOsd("🔴 CANLI · " + (cur ? cur.name : ""));
    }
  }

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

    fetch("/api/fetch?url=" + encodeURIComponent(key))
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
  function renderPlaylists() {
    var ul = $("#plList");
    ul.innerHTML = "";
    renderListPicker();
    var visible = state.playlists.filter(function (pl) { return !pl.hidden; });
    if (!visible.length) {
      ul.innerHTML = "<li class='muted'>Henüz playlist yok. Varsayılan liste kullanılıyor.</li>";
      return;
    }
    state.playlists.forEach(function (pl, idx) {
      if (pl.hidden) return;
      var li = document.createElement("li");
      var subtitle = pl.local ? "(yerel dosya)"
        : pl.mac ? ("MAC " + escapeHtml(pl.macAddr || "") + " · " + escapeHtml(pl.portal || ""))
        : escapeHtml(pl.url || "");
      li.innerHTML = "<span>" + escapeHtml(pl.name || "Playlist") + "</span>" +
        "<span class='u'>" + subtitle + "</span>";
      var del = document.createElement("button");
      del.className = "del";
      del.textContent = "✕";
      del.title = "Sil";
      del.onclick = function () {
        var removed = state.playlists[idx];
        state.playlists.splice(idx, 1);
        // Drop the removed list's channels too, otherwise they linger in the
        // sidebar under a name the user just deleted.
        if (removed && removed.name) {
          state.channels = state.channels.filter(function (c) {
            return (c.listName || "Canlı TV") !== removed.name;
          });
          delete state.loaded[removed.name];
          if (state.source === removed.name) state.source = "Canlı TV";
        }
        persist();
        buildSources();
        buildGroups();
        render();
        renderPlaylists();
      };
      li.appendChild(del);
      ul.appendChild(li);
    });
  }

  /* ------------------------------ events ----------------------------- */
  function bind() {
    var onSearch = function (e) {
      state.query = e.target.value.trim();
      $("#clearSearch").hidden = !state.query;
      render();
    };
    ["input", "keyup", "change", "search"].forEach(function (evt) {
      $("#search").addEventListener(evt, onSearch);
    });
    $("#clearSearch").onclick = function () {
      $("#search").value = "";
      state.query = "";
      $("#clearSearch").hidden = true;
      render();
    };

    $("#favBtn").onclick = function () {
      if (state.current) toggleFav(state.current);
    };
    $("#reloadBtn").onclick = function () {
      if (state.current) play(state.current);
    };
    $("#fullBtn").onclick = function () {
      var stage = $("#stage");
      if (document.fullscreenElement) document.exitFullscreen();
      else if (stage.requestFullscreen) stage.requestFullscreen();
    };
    $("#pipBtn").onclick = function () {
      if (document.pictureInPictureElement) document.exitPictureInPicture();
      else if (video.requestPictureInPicture) {
        video.requestPictureInPicture().catch(function () { showOsd("PiP desteklenmiyor"); });
      }
    };
    $("#themeBtn").onclick = function () {
      var cur = document.documentElement.getAttribute("data-theme") === "light" ? "dark" : "light";
      document.documentElement.setAttribute("data-theme", cur);
      try { localStorage.setItem("iptv.theme", cur); } catch (e) {}
    };
    $("#menuBtn").onclick = function () { $("#sidebar").classList.toggle("open"); };

    // dialogs
    $("#openLists").onclick = function () { renderListsDialog(); $("#listsDlg").showModal(); };
    $("#openPlaylists").onclick = function () { renderPlaylists(); $("#playlistsDlg").showModal(); };
    $("#setList").onchange = function () { selectList($("#setList").value); };
    $("#openAdd").onclick = function () { $("#addDlg").showModal(); };
    $("#inspectClose").onclick = function () { $("#inspectDlg").close(); };

    $("#addPlaylist").onclick = function () {
      var url = $("#plUrl").value.trim();
      var name = $("#plName").value.trim();
      // The name is what the list is called in "Liste Seç", so it is required.
      if (!url) { $("#plStatus").textContent = "Playlist adresi gerekli"; return; }
      if (!name) { $("#plStatus").textContent = "Listeye bir ad verin"; return; }
      if (state.playlists.some(function (p) { return p.name === name; })) {
        $("#plStatus").textContent = "Bu ad zaten kullanılıyor"; return;
      }
      state.playlists.push({ url: url, name: name });
      persist();
      $("#plUrl").value = ""; $("#plName").value = "";
      $("#plStatus").textContent = name + " eklendi";
      renderPlaylists();
      buildSources();
      selectList(name);
    };

    $("#addXc").onclick = function () {
      var portal = $("#xcPortal").value.trim();
      var user = $("#xcUser").value.trim();
      var pass = $("#xcPass").value.trim();
      var name = $("#xcName").value.trim();
      if (!portal || !user || !pass) {
        $("#xcStatus").textContent = "Portal, kullanıcı adı ve şifre gerekli";
        return;
      }
      // The name is what the list is called in "Liste Seç", so it is required.
      if (!name) { $("#xcStatus").textContent = "Listeye bir ad verin"; return; }
      if (state.playlists.some(function (p) { return p.name === name; })) {
        $("#xcStatus").textContent = "Bu ad zaten kullanılıyor"; return;
      }
      $("#xcStatus").textContent = "Bağlanılıyor...";
      api("/api/xtream?portal=" + encodeURIComponent(portal) +
          "&user=" + encodeURIComponent(user) +
          "&pass=" + encodeURIComponent(pass)).then(function (data) {
        var count = (data && data.count) || 0;
        if (!count) { $("#xcStatus").textContent = "Kanal bulunamadı (bilgiler hatalı olabilir)"; return; }
        state.playlists.push({ xc: true, portal: portal, user: user, pass: pass, name: name });
        persist();
        $("#xcStatus").textContent = count + " kanal eklendi";
        $("#xcPortal").value = ""; $("#xcUser").value = "";
        $("#xcPass").value = ""; $("#xcName").value = "";
        renderPlaylists();
        buildSources();
        selectList(name);
      }).catch(function (err) {
        $("#xcStatus").textContent = "Hata: " + (err.message || "bağlanamadı");
      });
    };

    $("#addMac").onclick = function () {
      var portal = $("#macPortal").value.trim();
      var mac = $("#macAddr").value.trim();
      var name = $("#macName").value.trim();
      if (!portal || !mac) { $("#macStatus").textContent = "Portal ve MAC gerekli"; return; }
      // The name is what the list is called in "Liste Seç", so it is required.
      if (!name) { $("#macStatus").textContent = "Listeye bir ad verin"; return; }
      if (state.playlists.some(function (p) { return p.name === name; })) {
        $("#macStatus").textContent = "Bu ad zaten kullanılıyor"; return;
      }
      $("#macStatus").textContent = "Bağlanılıyor...";
      api("/api/stalker?portal=" + encodeURIComponent(portal) +
          "&mac=" + encodeURIComponent(mac)).then(function (data) {
        var count = (data && data.count) || 0;
        if (!count) { $("#macStatus").textContent = "Kanal bulunamadı (MAC yetkili olmayabilir)"; return; }
        state.playlists.push({ mac: true, portal: portal, macAddr: mac, name: name });
        persist();
        $("#macStatus").textContent = count + " kanal eklendi";
        $("#macPortal").value = ""; $("#macAddr").value = ""; $("#macName").value = "";
        renderPlaylists();
        buildSources();
        selectList(name);
      }).catch(function (err) {
        $("#macStatus").textContent = "Hata: " + (err.message || "bağlanamadı");
      });
    };

    $("#plFile").addEventListener("change", function (e) {
      var file = e.target.files[0];
      if (!file) return;
      parseM3UFile(file).then(function (chans) {
        // Store inline channels as a local playlist.
        state.playlists.push({
          local: true,
          name: file.name,
          channels: chans
        });
        persist();
        state.channels = dedupe(state.channels.concat(chans));
        state.loaded[file.name] = true;
        buildSources();
        buildGroups(); render(); renderPlaylists();
        showOsd(chans.length + " kanal yüklendi");
      });
      e.target.value = "";
    });

    $("#saveEpg").onclick = function () {
      state.epgUrl = $("#epgUrl").value.trim();
      persist();
      if (state.current) loadEpgFor(state.current);
      showOsd(state.epgUrl ? "EPG kaydedildi" : "EPG kaldırıldı");
    };

    $("#exportPl").onclick = function () {
      var data = JSON.stringify(snapshot(), null, 2);
      var blob = new Blob([data], { type: "application/json" });
      var a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = "iptv-ayarlar.json";
      a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
      showOsd("Ayarlar indirildi");
    };

    $("#importPl").addEventListener("change", function (e) {
      var file = e.target.files[0];
      if (!file) return;
      var fr = new FileReader();
      fr.onload = function () {
        try {
          var raw = JSON.parse(String(fr.result));
          // Accept both our export format and a bare M3U text file.
          if (typeof raw === "string" || !applySnapshot(raw)) {
            throw new Error("bad");
          }
          persist();
          renderPlaylists();
          loadChannels();
          showOsd("Ayarlar yüklendi");
        } catch (err) {
          parseM3UFile(file).then(function (chans) {
            if (!chans.length) { showOsd("Dosya okunamadı"); return; }
            state.playlists.push({ local: true, name: file.name, channels: chans });
            state.channels = dedupe(state.channels.concat(chans));
            state.loaded[file.name] = true;
            persist();
            renderPlaylists();
            buildSources();
            buildGroups();
            render();
            showOsd(chans.length + " kanal yüklendi");
          });
        }
      };
      fr.readAsText(file);
      e.target.value = "";
    });

    $("#saveChannel").onclick = function () {
      var name = $("#addName").value.trim();
      var url = $("#addUrl").value.trim();
      if (!name || !url) { showOsd("Ad ve adres gerekli"); return; }
      var ch = normalize({
        name: name, url: url,
        group: $("#addGroup").value.trim() || "Özel",
        logo: $("#addLogo").value.trim(),
        source: "Özel"
      });
      state.channels.unshift(ch);
      state.playlists.push({ local: true, name: "Özel kanallar", channels: [ch], hidden: true });
      buildGroups(); render();
      $("#addDlg").close();
      play(ch);
    };

    // keyboard shortcuts
    document.addEventListener("keydown", function (e) {
      if (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA") return;
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
      var theme = localStorage.getItem("iptv.theme");
      if (theme) document.documentElement.setAttribute("data-theme", theme);
    } catch (e) {}

    loadLocal();
    bind();

    // Prefer the shared server copy (lets you open your list from any device).
    loadServerStore().then(function () {
      if (state.epgUrl) $("#epgUrl").value = state.epgUrl;
      return loadChannels();
    }).then(function () {
      if (state.channels.length) {
        setOverlay(true,
          "<h2>" + state.channels.length + " kanal hazır</h2>" +
          "<p>Başlamak için listeden bir kanal seçin.</p>");
      }
    }).catch(function (err) {
      setOverlay(true, "<h2>Liste yüklenemedi</h2><p>" +
        escapeHtml(String(err && err.message || err)) + "</p>");
    });
  }

  document.addEventListener("DOMContentLoaded", boot);
})();
