/* IPTV Player - front-end logic */
(function () {
  "use strict";

  var $ = function (sel, root) { return (root || document).querySelector(sel); };
  var $$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  var state = {
    channels: [],
    filtered: [],
    favorites: [],
    recent: [],
    playlists: [],
    epgUrl: "",
    epg: {},
    current: null,
    group: "all",
    tab: "all",
    kind: "all",
    source: "all",
    sources: [],
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
      recent: state.recent,
      playlists: state.playlists,
      epgUrl: state.epgUrl
    };
  }

  function applySnapshot(raw) {
    if (!raw || typeof raw !== "object") return false;
    state.favorites = raw.favorites || [];
    state.recent = raw.recent || [];
    state.playlists = raw.playlists || [];
    state.epgUrl = raw.epgUrl || "";
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
        recent: data.recent,
        playlists: data.playlists,
        epgUrl: settings.epgUrl
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
          recent: state.recent,
          playlists: state.playlists,
          settings: { epgUrl: state.epgUrl }
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

  function parseM3UFile(file, source) {
    return new Promise(function (resolve) {
      var fr = new FileReader();
      fr.onload = function () { resolve(parseM3U(String(fr.result), source || file.name)); };
      fr.readAsText(file);
    });
  }

  /* ----------------------------- channels ---------------------------- */
  function loadChannels() {
    var defaults = api("/api/channels").then(function (data) {
      return (data.channels || []).map(function (c) {
        c.listName = "Yerleşik liste";
        return c;
      });
    }).catch(function () { return []; });

    var fromLists = Promise.all(state.playlists.map(function (pl) {
      var label = pl.name || "Playlist";
      if (pl.local) {
        return Promise.resolve((pl.channels || []).map(function (c) {
          c.listName = label; return c;
        }));
      }
      if (pl.xc) {
        return api("/api/xtream?portal=" + encodeURIComponent(pl.portal) +
                   "&user=" + encodeURIComponent(pl.user) +
                   "&pass=" + encodeURIComponent(pl.pass))
          .then(function (d) {
            return (d.channels || []).map(function (c) { c.listName = label; return c; });
          })
          .catch(function () { return []; });
      }
      if (pl.mac) {
        return api("/api/stalker?portal=" + encodeURIComponent(pl.portal) +
                   "&mac=" + encodeURIComponent(pl.macAddr))
          .then(function (d) {
            return (d.channels || []).map(function (c) { c.listName = label; return c; });
          })
          .catch(function () { return []; });
      }
      return fetch("/api/fetch?url=" + encodeURIComponent(pl.url))
        .then(function (r) { return r.text(); })
        .then(function (t) {
          return parseM3U(t, label).map(function (c) {
            c.listName = label; return c;
          });
        })
        .catch(function () { return []; });
    }));

    return Promise.all([defaults, fromLists]).then(function (res) {
      var all = res[0].slice();
      res[1].forEach(function (arr) { all = all.concat(arr); });
      all = dedupe(all);
      state.channels = all;
      buildSources();
      buildGroups();
      render();
      return all;
    });
  }

  // Distinct lists (bundled + each playlist / portal) the user can switch
  // between, so channels from different sources are not one big pile.
  function buildSources() {
    var counts = {};
    state.channels.forEach(function (ch) {
      var n = ch.listName || "Yerleşik liste";
      counts[n] = (counts[n] || 0) + 1;
    });
    state.sources = Object.keys(counts).map(function (n) {
      return { name: n, count: counts[n] };
    }).sort(function (a, b) {
      // Bundled list always first, the rest alphabetically.
      var ab = a.name === "Yerleşik liste", bb = b.name === "Yerleşik liste";
      if (ab !== bb) return ab ? -1 : 1;
      return a.name.localeCompare(b.name, "tr");
    });
    if (state.source !== "all" &&
        !state.sources.some(function (s) { return s.name === state.source; })) {
      state.source = "all";
    }
    renderSources();
  }

  function renderSources() {
    var box = $("#sources");
    box.innerHTML = "";
    var total = state.channels.length;
    var allChip = document.createElement("button");
    allChip.className = "schip" + (state.source === "all" ? " active" : "");
    allChip.innerHTML = "Tüm listeler<span class='n'>" + total + "</span>";
    allChip.onclick = function () { selectSource("all"); };
    box.appendChild(allChip);

    state.sources.forEach(function (s) {
      var b = document.createElement("button");
      b.className = "schip" + (state.source === s.name ? " active" : "");
      b.title = s.name;
      b.innerHTML = escapeHtml(s.name) + "<span class='n'>" + s.count + "</span>";
      b.onclick = function () { selectSource(s.name); };
      box.appendChild(b);
    });
  }

  function selectSource(name) {
    state.source = name;
    state.group = "all";
    state.tab = "all";
    $$("#tabs .tab").forEach(function (t) {
      t.classList.toggle("active", t.dataset.filter === "all");
    });
    renderSources();
    buildGroups();
    render();
  }

  // Step through lists with the ‹ › buttons.
  function cycleSource(dir) {
    var names = ["all"].concat(state.sources.map(function (s) { return s.name; }));
    var i = names.indexOf(state.source);
    if (i < 0) i = 0;
    i = (i + dir + names.length) % names.length;
    selectSource(names[i]);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function dedupe(list) {
    var seen = {};
    var out = [];
    list.forEach(function (ch) {
      var key = (ch.name || "") + "|" + (ch.url || "");
      if (seen[key]) return;
      seen[key] = 1;
      out.push(ch);
    });
    return out;
  }

  function buildGroups() {
    var counts = {};
    var order = [];
    var scope = state.channels.filter(function (ch) {
      if (!inSource(ch)) return false;
      if (state.kind !== "all" && contentKind(ch) !== state.kind) return false;
      return true;
    });
    scope.forEach(function (ch) {
      var g = ch.group || "Genel";
      if (!counts[g]) { counts[g] = 0; order.push(g); }
      counts[g] += 1;
    });
    var groups = order;
    var box = $("#groups");
    box.innerHTML = "";
    var allChip = document.createElement("button");
    allChip.className = "gchip" + (state.group === "all" ? " active" : "");
    allChip.textContent = "Tüm gruplar (" + scope.length + ")";
    allChip.onclick = function () { state.group = "all"; buildGroups(); render(); };
    box.appendChild(allChip);

    groups.forEach(function (g) {
      var b = document.createElement("button");
      b.className = "gchip" + (state.group === g ? " active" : "");
      b.textContent = g + " (" + counts[g] + ")";
      b.onclick = function () { state.group = g; buildGroups(); render(); };
      box.appendChild(b);
    });
  }

  function inSource(ch) {
    if (state.source === "all") return true;
    return (ch.listName || "Yerleşik liste") === state.source;
  }

  function isFav(url) { return state.favorites.indexOf(url) >= 0; }

  function applyFilter() {
    var q = state.query.toLowerCase();
    var list = state.channels.filter(function (ch) {
      if (!inSource(ch)) return false;
      if (state.kind !== "all" && contentKind(ch) !== state.kind) return false;
      if (state.tab === "fav" && !isFav(ch.url)) return false;
      if (state.tab === "recent" && state.recent.indexOf(ch.url) < 0) return false;
      if (state.group !== "all" && (ch.group || "Genel") !== state.group) return false;
      if (q && ch.name.toLowerCase().indexOf(q) < 0) return false;
      return true;
    });
    if (state.tab === "recent") {
      list.sort(function (a, b) {
        return state.recent.indexOf(a.url) - state.recent.indexOf(b.url);
      });
    }
    state.filtered = list;
  }

  function logoHue(name) {
    var h = 0;
    for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
    return h;
  }

  function render() {
    applyFilter();
    var box = $("#channels");
    box.innerHTML = "";
    var frag = document.createDocumentFragment();

    state.filtered.slice(0, 1500).forEach(function (ch) {
      var node = document.createElement("div");
      node.className = "ch" + (ch.urls && ch.urls.length > 1 ? " ch-multi" : "");
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
      favBtn.className = "ch-fav" + (isFav(ch.url) ? " on" : "");
      favBtn.textContent = isFav(ch.url) ? "★" : "☆";
      favBtn.title = "Favori";
      favBtn.onclick = function (ev) { ev.stopPropagation(); toggleFav(ch.url); };

      var head = document.createElement("div");
      head.className = "ch-head";
      head.appendChild(logo);
      head.appendChild(text);
      head.appendChild(favBtn);

      var inspectBtn = document.createElement("button");
      inspectBtn.className = "ch-inspect";
      inspectBtn.type = "button";
      inspectBtn.textContent = "İncele";
      inspectBtn.title = "Yayın adreslerini kontrol et";
      inspectBtn.onclick = function (ev) { ev.stopPropagation(); inspect(ch); };
      head.appendChild(inspectBtn);

      head.onclick = function () { play(ch); };
      node.appendChild(head);

      // Stream alternatives: the bundled list first, the site's stream below.
      if (ch.urls && ch.urls.length > 1) {
        var alts = document.createElement("div");
        alts.className = "ch-alts";
        ch.urls.forEach(function (a, idx) {
          var b = document.createElement("button");
          b.className = "alt" + (idx === 0 ? " primary" : "");
          b.textContent = (idx + 1) + ". " + (a.label || "yayın");
          b.onclick = function (ev) {
            ev.stopPropagation();
            play(ch, a.url);
          };
          alts.appendChild(b);
        });
        node.appendChild(alts);
      }

      node.onkeydown = function (ev) {
        if (ev.key === "Enter" || ev.key === " ") { ev.preventDefault(); play(ch); }
      };
      frag.appendChild(node);
    });

    box.appendChild(frag);
    $("#chCount").textContent = state.filtered.length + " / " + state.channels.length + " kanal";
  }

  function toggleFav(url) {
    var i = state.favorites.indexOf(url);
    if (i >= 0) state.favorites.splice(i, 1);
    else state.favorites.push(url);
    persist();
    render();
    updateFavBtn();
  }

  function pushRecent(url) {
    var i = state.recent.indexOf(url);
    if (i >= 0) state.recent.splice(i, 1);
    state.recent.unshift(url);
    state.recent = state.recent.slice(0, 50);
    persist();
  }

  /* ------------------------------ player ----------------------------- */
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
    pushRecent(ch.url);
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
    $("#iName").textContent = ch.name;
    $("#iGroup").textContent = ch.group || "Genel";
    $("#iSource").textContent = ch.source || "—";
    updateFavBtn();
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

    $("#iUrl").textContent = url;
    $("#npMeta").textContent = (ch.group || "Genel") + " · " + (stream.label || ch.source || "");
    if (i > 0) showOsd("Yedek yayın " + i + " deneniyor: " + (stream.label || ""));

    destroyHls();
    spinner.hidden = false;
    setOverlay(true, null);
    video.removeAttribute("src");
    video.load();

    // MAC/portal streams need the session headers, so always go via the proxy.
    var src = ch.headers ? proxyFor(ch, url) : url;
    var lower = url.split("?")[0].toLowerCase();

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

  // Inspect a channel's stream alternatives: ask the server to fetch each one
  // and report which respond, so a user can see why a channel won't open.
  function inspect(ch) {
    var streams = (ch.urls && ch.urls.length) ? ch.urls.slice()
                                              : [{ url: ch.url, label: "" }];
    var dlg = $("#inspectDlg");
    $("#inspectTitle").textContent = ch.name + " — yayın incelemesi";
    $("#inspectHint").textContent = streams.length + " yayın adresi kontrol ediliyor…";
    var body = $("#inspectBody");
    body.innerHTML = "";
    streams.forEach(function (s, i) {
      var row = document.createElement("div");
      row.className = "inspect-row pending";
      row.id = "insp-" + i;
      row.innerHTML = '<span class="st">kontrol ediliyor…</span>' +
        '<span class="u"></span>';
      row.querySelector(".u").textContent = (s.label || ("yayın " + (i + 1))) +
        " — " + s.url;
      body.appendChild(row);
    });
    if (!dlg.open) dlg.showModal();

    api("/api/probe?url=" + encodeURIComponent(streams.map(function (s) {
      return s.url;
    }).join("|"))).then(function (data) {
      var results = (data && data.results) || [];
      var working = 0;
      results.forEach(function (r, i) {
        var row = $("#insp-" + i);
        if (!row) return;
        row.classList.remove("pending");
        row.classList.add(r.ok ? "ok" : "bad");
        if (r.ok) working++;
        var detail = r.ok ? "✔ çalışıyor" : "✘ açılmıyor";
        if (r.kind === "playlist" && r.variants) {
          detail += " (HLS liste, " + r.variants + " varyant)";
        } else if (r.kind) {
          detail += " (" + r.kind + ")";
        }
        if (r.error) detail += " — " + r.error;
        else if (r.status && r.status !== 200) detail += " — HTTP " + r.status;
        row.querySelector(".st").textContent = detail;
      });
      $("#inspectHint").textContent = working + " / " + results.length +
        " yayın çalışıyor. Çalışanı oynatmak için listedeki numaraya tıkla.";
    }).catch(function (err) {
      $("#inspectHint").textContent = "İnceleme başarısız: " + err.message;
    });
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
    var t = video.duration;
    if (t && isFinite(t)) {
      showOsd("▶ " + (state.current ? state.current.name : ""));
    } else {
      showOsd("🔴 CANLI · " + (state.current ? state.current.name : ""));
    }
  }

  function updateFavBtn() {
    var on = state.current && isFav(state.current.url);
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
  function loadEpgFor(ch) {
    var box = $("#epg");
    if (!state.epgUrl) {
      box.className = "epg-body muted";
      box.textContent = "EPG yüklenmedi. Playlistler bölümünden bir XMLTV adresi ekleyin.";
      $("#epgSource").textContent = "";
      return;
    }
    box.className = "epg-body muted";
    box.textContent = "EPG yükleniyor...";

    var key = state.epgUrl;
    var doRender = function () { renderEpg(ch); };
    if (state.epg[key]) { doRender(); return; }

    fetch("/api/fetch?url=" + encodeURIComponent(key))
      .then(function (r) { return r.text(); })
      .then(function (xml) {
        state.epg[key] = parseXmltv(xml);
        doRender();
      })
      .catch(function () {
        box.textContent = "EPG alınamadı.";
      });
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
    var box = $("#epg");
    var key = state.epgUrl;
    var table = state.epg[key] || {};
    var list = table[ch.tvgId] || table[ch.name] || null;
    if (!list) {
      box.className = "epg-body muted";
      box.textContent = "Bu kanal için program bilgisi bulunamadı.";
      return;
    }
    var now = Date.now();
    box.className = "epg-body";
    box.innerHTML = "";
    var shown = list.filter(function (p) { return p.stop > now - 3600000; }).slice(0, 12);
    shown.forEach(function (p) {
      var row = document.createElement("div");
      row.className = "epg-item" + (p.start <= now && now < p.stop ? " now" : "");
      row.innerHTML =
        '<span class="t">' + fmtTime(p.start) + " – " + fmtTime(p.stop) + "</span>" +
        '<span class="d"><b>' + escapeHtml(p.title) + "</b>" +
        (p.desc ? "<br><span class='muted'>" + escapeHtml(p.desc.slice(0, 160)) + "</span>" : "") +
        "</span>";
      box.appendChild(row);
    });
    $("#epgSource").textContent = "XMLTV";
  }

  /* ---------------------------- playlists ---------------------------- */
  function renderPlaylists() {
    var ul = $("#plList");
    ul.innerHTML = "";
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
        state.playlists.splice(idx, 1);
        persist();
        renderPlaylists();
        loadChannels();
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

    $$("#tabs .tab").forEach(function (t) {
      t.onclick = function () {
        $$("#tabs .tab").forEach(function (x) { x.classList.remove("active"); });
        t.classList.add("active");
        state.tab = t.dataset.filter;
        render();
      };
    });

    $("#srcPrev").onclick = function () { cycleSource(-1); };
    $("#srcNext").onclick = function () { cycleSource(1); };

    $$("#kinds .tab").forEach(function (t) {
      t.onclick = function () {
        $$("#kinds .tab").forEach(function (x) { x.classList.remove("active"); });
        t.classList.add("active");
        state.kind = t.dataset.kind;
        state.group = "all";
        buildGroups();
        render();
      };
    });

    $("#favBtn").onclick = function () {
      if (state.current) toggleFav(state.current.url);
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
    $("#openPlaylists").onclick = function () { renderPlaylists(); $("#playlistsDlg").showModal(); };
    $("#openAdd").onclick = function () { $("#addDlg").showModal(); };
    $("#inspectClose").onclick = function () { $("#inspectDlg").close(); };

    $("#addPlaylist").onclick = function () {
      var url = $("#plUrl").value.trim();
      if (!url) return;
      state.playlists.push({ url: url, name: $("#plName").value.trim() || "Playlist" });
      persist();
      $("#plUrl").value = ""; $("#plName").value = "";
      renderPlaylists();
      loadChannels();
    };

    $("#addXc").onclick = function () {
      var portal = $("#xcPortal").value.trim();
      var user = $("#xcUser").value.trim();
      var pass = $("#xcPass").value.trim();
      var name = $("#xcName").value.trim() || "XC " + (user || portal);
      if (!portal || !user || !pass) {
        $("#xcStatus").textContent = "Portal, kullanıcı adı ve şifre gerekli";
        return;
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
        loadChannels();
      }).catch(function (err) {
        $("#xcStatus").textContent = "Hata: " + (err.message || "bağlanamadı");
      });
    };

    $("#addMac").onclick = function () {
      var portal = $("#macPortal").value.trim();
      var mac = $("#macAddr").value.trim();
      var name = $("#macName").value.trim() ||
        (mac ? "MAC " + mac.slice(-5) : "MAC portal");
      if (!portal || !mac) { $("#macStatus").textContent = "Portal ve MAC gerekli"; return; }
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
        loadChannels();
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
            persist();
            renderPlaylists();
            loadChannels();
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
    });
  }

  document.addEventListener("DOMContentLoaded", boot);
})();
