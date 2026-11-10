/* =========================================================================
 * LinkTV v1.1.0 —— 全屏优先 TV 直播逻辑
 *  · 启动即全屏播放；OSD 3 秒自动隐藏，任意键重新亮起
 *  · 扁平播放队列：央视 → 卫视 → 地方台 → 收藏夹，上下键 ±1 换台、自动跨分类续播
 *  · 确定键呼出「左侧分类 + 右侧频道列表」面板；左右键切分类；返回/Esc 收起
 *  · 菜单键（或点星标）收藏当前频道；收藏夹为独立分类
 *  · 直播源由程序内置 + 云端清单自动维护，界面不暴露任何「源」概念
 *  · 节目单尽力取自官方 EPG，取不到降级为「频道名 + LIVE + 时段进度」
 * ========================================================================= */
(function () {
  "use strict";

  var $ = function (id) { return document.getElementById(id); };
  var RAW = (window.CHANNEL_GROUPS || []).map(function (g) {
    return { name: g.name, channels: g.channels.slice() };
  });
  var BUILTIN = window.BUILTIN_SOURCES || {};
  var REMOTE_SRC = window.OFFICIAL_SOURCES_URL || "";
  var CATS = [
    { name: "央视", ic: "📺" }, { name: "卫视", ic: "🛰" },
    { name: "地方台", ic: "🏙" }, { name: "收藏夹", ic: "★" }
  ];
  var FAV_KEY = "linktv_fav_v11", LAST_KEY = "linktv_last_v11";

  // 频道名 → 频道对象（收藏夹还原用，引用同一对象，源更新会自动生效）
  var byName = {};
  RAW.forEach(function (g) { g.channels.forEach(function (c) { if (!byName[c.name]) byName[c.name] = c; }); });

  var video = $("player"), idle = $("idle"), osd = $("osd"), panel = $("panel");
  var toast = $("toast");

  var state = {
    gi: 0,           // 当前分类
    pos: 0,          // 在扁平队列 flat 中的位置（当前播放 / 面板高亮）
    current: null,   // 当前播放频道
    panel: false,
    hls: null,
    retry: false,
    prog: null,      // 当前节目 {title, st, et}
    osdTimer: null, toastTimer: null
  };
  var groups = [], flat = [];
  var fav = loadFav();

  // ---------------- 收藏 ----------------
  function loadFav() { try { return JSON.parse(localStorage.getItem(FAV_KEY) || "[]"); } catch (e) { return []; } }
  function saveFav() { try { localStorage.setItem(FAV_KEY, JSON.stringify(fav)); } catch (e) {} }
  function isFav(name) { return fav.some(function (f) { return f.name === name; }); }

  // ---------------- 数据结构 ----------------
  function buildGroups() {
    groups = RAW.map(function (g, i) {
      return { name: g.name, ic: CATS[i].ic, channels: g.channels.slice(), byProv: g.name === "地方台" };
    });
    var favCh = fav.map(function (f) { return byName[f.name]; }).filter(Boolean);
    groups.push({ name: "收藏夹", ic: "★", channels: favCh, byProv: false });
    flat = [];
    groups.forEach(function (g, gi) {
      g.channels.forEach(function (ch, li) { flat.push({ ch: ch, gi: gi, li: li, catName: g.name }); });
    });
  }

  // 合并云端/内置源清单（按频道名覆盖 url）
  function applySources(map) {
    if (!map) return;
    var cur = state.current, had = cur && !!cur.url;
    RAW.forEach(function (g) {
      g.channels.forEach(function (c) { if (map[c.name]) c.url = map[c.name]; });
    });
    if (cur && !had && cur.url) { playChannel(cur); updateRes(0); } // 原本无直链、清单补齐后自动续播
  }

  function wrap(n, len) { if (!len) return 0; return ((n % len) + len) % len; }
  function pad(x) { return x < 10 ? "0" + x : "" + x; }
  function hhmm(d) { return pad(d.getHours()) + ":" + pad(d.getMinutes()); }

  // ---------------- 播放 ----------------
  function destroyHls() { if (state.hls) { try { state.hls.destroy(); } catch (e) {} state.hls = null; } }
  function stopVideo() { try { video.pause(); video.removeAttribute("src"); video.load(); } catch (e) {} }
  function nativeHls() { try { return !!video.canPlayType("application/vnd.apple.mpegurl"); } catch (e) { return false; } }

  function showIdle(ch) {
    $("idle-name").textContent = ch ? ch.name : "LinkTV";
    idle.classList.remove("hidden");
    updateRes(0);
  }
  function hideIdle() { idle.classList.add("hidden"); }

  function playChannel(ch) {
    destroyHls(); stopVideo(); state.retry = false;
    if (!ch || !ch.url) { showIdle(ch); return; }
    hideIdle();
    _play(ch, nativeHls());
  }

  function _play(ch, native) {
    var url = ch.url;
    if (native) {
      video.src = url;
      video.play().catch(function () {});
      video.addEventListener("loadedmetadata", function () { readResFromVideo(); }, { once: true });
      video.addEventListener("error", function () { if (state.current === ch) fallback(ch, true); }, { once: true });
    } else if (window.Hls && window.Hls.isSupported()) {
      var hls = new window.Hls({ lowLatencyMode: true, liveDurationInfinity: true, enableWorker: true, maxBufferLength: 30 });
      state.hls = hls;
      hls.loadSource(url); hls.attachMedia(video);
      hls.on(window.Hls.Events.MANIFEST_PARSED, function (e, d) {
        video.play().catch(function () {});
        readResFromLevels(d && d.levels);
      });
      hls.on(window.Hls.Events.ERROR, function (evt, d) {
        if (d && d.fatal && state.current === ch) fallback(ch, false);
      });
    } else if (nativeHls()) {
      _play(ch, true);
    } else {
      showIdle(ch);
    }
  }

  function fallback(ch, fromNative) {
    if (state.retry) { showIdle(ch); return; }
    state.retry = true;
    destroyHls(); stopVideo();
    if (fromNative && window.Hls && window.Hls.isSupported()) _play(ch, false);
    else if (!fromNative && nativeHls()) _play(ch, true);
    else showIdle(ch);
  }

  function readResFromVideo() { if (video.videoHeight) updateRes(video.videoHeight); }
  function readResFromLevels(levels) {
    if (!levels || !levels.length) return;
    var h = 0; levels.forEach(function (l) { if (l && l.height && l.height > h) h = l.height; });
    if (h) updateRes(h);
  }
  function updateRes(h) {
    var t = "HD";
    if (h >= 2000) t = "4K"; else if (h >= 1000) t = "1080P";
    else if (h >= 700) t = "720P"; else if (h >= 560) t = "576P";
    else if (h > 0) t = "480P";
    $("osd-res").textContent = t;
  }

  // ---------------- OSD ----------------
  function updateOSD(ch, item) {
    $("osd-ch").textContent = ch ? ch.name : "LinkTV";
    if (item) $("osd-cat").textContent = item.catName + " · " + pad(item.li + 1) + " / " + pad(groups[item.gi].channels.length);
    state.prog = null;
    paintProgram(ch);
    fetchEpg(ch);
  }

  // 直播无时长：进度条表示「当前节目/时段」的播出进度，带 LIVE 红点，不可拖动
  function segInfo() {
    var now = new Date(), st = new Date(now);
    st.setMinutes(now.getMinutes() < 30 ? 0 : 30, 0, 0);
    var et = new Date(st.getTime() + 30 * 60000);
    return { p: (now - st) / (et - st), st: hhmm(st), et: hhmm(et) };
  }
  function paintProgram(ch) {
    ch = ch || state.current;
    if (!ch) return;
    var p, s;
    if (state.prog) {
      var now = Date.now();
      p = Math.max(0, Math.min(1, (now - state.prog.st) / (state.prog.et - state.prog.st)));
      $("osd-prog").textContent = state.prog.title;
      $("osd-ptime").textContent = hhmm(new Date(state.prog.st)) + " - " + hhmm(new Date(state.prog.et));
    } else {
      s = segInfo(); p = s.p;
      $("osd-prog").textContent = "正在直播 · " + ch.name;
      $("osd-ptime").textContent = s.st + " - " + s.et;
    }
    $("osd-barfill").style.width = (p * 100).toFixed(1) + "%";
  }

  // 尽力取央视官方节目单；CORS/失败一律静默降级，不影响播放与界面
  function cctvCode(name) {
    var m = name.match(/^CCTV-(\d+)/); if (!m) return null;
    if (name.indexOf("5+") >= 0) return "cctv5plus";
    if (name.indexOf("欧洲") >= 0 || name.indexOf("美洲") >= 0) return "cctv4";
    return "cctv" + m[1];
  }
  function fetchEpg(ch) {
    if (!ch || !window.fetch) return;
    var code = cctvCode(ch.name); if (!code) return;
    var d = new Date();
    var ds = "" + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate());
    var url = "https://api.cntv.cn/epg/getEpgInfoByChannelCode?c=" + code + "&d=" + ds;
    var ctrl = window.AbortController ? new AbortController() : null;
    var to = setTimeout(function () { if (ctrl) ctrl.abort(); }, 2500);
    fetch(url, ctrl ? { signal: ctrl.signal } : {})
      .then(function (r) { return r.json(); })
      .then(function (j) { clearTimeout(to); var p = parseEpg(j); if (p && state.current === ch) { state.prog = p; paintProgram(ch); } })
      .catch(function () { clearTimeout(to); });
  }
  function parseEpg(j) {
    try {
      var arr = j && (j.data || (j.result && j.result.data) || j.program || j.list);
      if (!Array.isArray(arr)) return null;
      var now = Date.now(), best = null;
      arr.forEach(function (it) {
        var t = it.title || it.name || it.programName;
        var s = it.startTime || it.start || it.st, e = it.endTime || it.end || it.et;
        if (!t || !s || !e) return;
        var st = new Date(String(s).replace(/-/g, "/")).getTime();
        var et = new Date(String(e).replace(/-/g, "/")).getTime();
        if (!isNaN(st) && !isNaN(et) && now >= st && now <= et) best = { title: t, st: st, et: et };
      });
      return best;
    } catch (e) { return null; }
  }

  function showOSD() {
    if (state.panel) return;
    osd.classList.remove("hidden");
    clearTimeout(state.osdTimer);
    state.osdTimer = setTimeout(function () { osd.classList.add("hidden"); }, 3000);
  }
  function showToast(txt) {
    toast.textContent = txt; toast.classList.remove("hidden");
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(function () { toast.classList.add("hidden"); }, 1300);
  }

  // ---------------- 播放调度 ----------------
  function playAt(pos) {
    if (!flat.length) return;
    state.pos = wrap(pos, flat.length);
    var item = flat[state.pos];
    state.gi = item.gi;
    state.current = item.ch;
    playChannel(item.ch);
    updateOSD(item.ch, item);
    updateRes(0);
    try { localStorage.setItem(LAST_KEY, item.ch.name); } catch (e) {}
  }
  function step(dir) { playAt(state.pos + dir); }

  function setCat(gi) {
    state.gi = wrap(gi, CATS.length);
    var p = -1;
    for (var i = 0; i < flat.length; i++) { if (flat[i].gi === state.gi) { p = i; break; } }
    if (p >= 0) state.pos = p;
    if (state.panel) renderPanel();
  }
  function switchCatAndPlay(dir) {
    // 全屏左右键：切到下一个「非空」分类并播放其首个频道（跳过空收藏夹）
    if (!flat.length) return;
    var gi = state.gi;
    for (var n = 0; n < CATS.length; n++) {
      gi = wrap(gi + dir, CATS.length);
      if (groups[gi] && groups[gi].channels.length) break;
    }
    if (!groups[gi] || !groups[gi].channels.length) return;
    for (var i = 0; i < flat.length; i++) {
      if (flat[i].gi === gi) { playAt(i); return; }
    }
  }

  // ---------------- 面板 ----------------
  function openPanel() {
    state.panel = true;
    panel.classList.remove("hidden");
    osd.classList.add("hidden"); clearTimeout(state.osdTimer);
    // 定位到当前播放频道所在分类
    if (flat[state.pos]) state.gi = flat[state.pos].gi;
    renderPanel();
  }
  function closePanel() {
    state.panel = false;
    panel.classList.add("hidden");
    showOSD();
  }
  function confirmPanel() { playAt(state.pos); closePanel(); }
  function moveList(dir) {
    if (!flat.length) return;
    // 整个扁平队列循环：上下键连续换台，到分组边界自动跨到相邻分类
    state.pos = wrap(state.pos + dir, flat.length);
    state.gi = flat[state.pos].gi;
    renderPanel();
  }

  function renderPanel() {
    // 左侧分类列
    var cats = $("cats"); cats.innerHTML = "";
    var title = document.createElement("div"); title.className = "cats-title"; title.textContent = "频道分类";
    cats.appendChild(title);
    CATS.forEach(function (c, i) {
      var el = document.createElement("div");
      el.className = "cat" + (i === state.gi ? " active" : "");
      var cnt = groups[i].channels.length;
      el.innerHTML = '<span class="ic">' + c.ic + '</span><span>' + c.name + '</span><span class="cnt">' + cnt + '</span>';
      el.addEventListener("click", function () { setCat(i); });
      cats.appendChild(el);
    });

    // 右侧频道列表
    var g = groups[state.gi];
    $("plist-title").textContent = g.name;
    var provs = g.byProv ? (function () { var s = {}; g.channels.forEach(function (c) { if (c.prov) s[c.prov] = 1; }); return Object.keys(s).length; })() : 0;
    $("plist-count").textContent = "共 " + g.channels.length + " 个频道" + (provs ? " · 覆盖 " + provs + " 个省级行政区" : "");

    var ul = $("plist"); ul.innerHTML = "";
    if (!g.channels.length) {
      var em = document.createElement("div"); em.className = "plist-empty";
      em.textContent = "暂无收藏频道 · 选中频道按菜单键或点星标即可收藏";
      ul.appendChild(em); return;
    }

    var lastProv = null, focusEl = null;
    g.channels.forEach(function (ch, li) {
      if (g.byProv && ch.prov && ch.prov !== lastProv) {
        lastProv = ch.prov;
        var cnt = g.channels.filter(function (x) { return x.prov === ch.prov; }).length;
        var head = document.createElement("div"); head.className = "prov-head";
        head.innerHTML = '<span>' + (ch.region ? ch.region + " · " : "") + ch.prov + '</span><span class="pc">' + cnt + ' 个频道</span>';
        ul.appendChild(head);
      }
      // 找到该频道在 flat 中的位置
      var fi = -1;
      for (var i = 0; i < flat.length; i++) { if (flat[i].gi === state.gi && flat[i].ch === ch) { fi = i; break; } }
      var liEl = document.createElement("li");
      liEl.className = "ch" + (fi === state.pos ? " focus" : "") + (state.current === ch ? " playing" : "");
      liEl.innerHTML = '<span class="num">' + pad(li + 1) + '</span><span class="nm">' + ch.name + '</span>'
        + '<span class="star' + (isFav(ch.name) ? " on" : "") + '">' + (isFav(ch.name) ? "★" : "☆") + '</span>';
      liEl.querySelector(".star").addEventListener("click", function (e) { e.stopPropagation(); toggleFav(ch); });
      liEl.addEventListener("click", function () { if (fi >= 0) { state.pos = fi; } confirmPanel(); });
      ul.appendChild(liEl);
      if (fi === state.pos) focusEl = liEl;
    });
    if (focusEl && focusEl.scrollIntoView) focusEl.scrollIntoView({ block: "nearest" });
  }

  // ---------------- 收藏切换 ----------------
  function toggleFav(ch) {
    if (!ch) return;
    var i = -1; fav.forEach(function (f, k) { if (f.name === ch.name) i = k; });
    var added = i < 0;
    if (added) fav.push({ name: ch.name, url: ch.url || "", prov: ch.prov || "" });
    else fav.splice(i, 1);
    saveFav();

    // 以「当前高亮项」为锚点重建后重新定位，避免焦点跳回正在播放的频道
    var anchor = flat[state.pos];
    var anchorName = anchor && anchor.ch.name, anchorGi = anchor && anchor.gi;
    buildGroups();
    var p = -1;
    if (anchorName) {
      for (var k = 0; k < flat.length; k++) {
        if (flat[k].ch.name === anchorName && flat[k].gi === anchorGi) { p = k; break; }
      }
      if (p < 0) for (var k2 = 0; k2 < flat.length; k2++) {
        if (flat[k2].ch.name === anchorName) { p = k2; break; }
      }
    }
    if (p < 0) p = Math.min(state.pos, flat.length - 1);
    state.pos = p < 0 ? 0 : p;
    if (flat[state.pos]) state.gi = flat[state.pos].gi;
    if (state.panel) renderPanel();
    showToast(added ? "★ 已收藏 · " + ch.name : "☆ 已取消收藏 · " + ch.name);
    if (!state.panel) showOSD();
  }
  function currentTarget() {
    if (state.panel && flat[state.pos]) return flat[state.pos].ch;
    return state.current;
  }

  // ---------------- 键盘 / 遥控 ----------------
  function onKey(e) {
    var k = e.key || "", kc = e.keyCode || 0;
    var up = k === "ArrowUp" || kc === 38, down = k === "ArrowDown" || kc === 40;
    var left = k === "ArrowLeft" || kc === 37, right = k === "ArrowRight" || kc === 39;
    var enter = k === "Enter" || k === "NumpadEnter" || kc === 13 || kc === 23;
    var back = k === "Escape" || k === "Backspace" || k === "BrowserBack" || kc === 27 || kc === 461;
    var menu = k === "Menu" || k === "ContextMenu" || kc === 93;
    if (!(up || down || left || right || enter || back || menu)) return;
    e.preventDefault();
    if (menu) toggleFav(currentTarget());
    else if (back) { if (state.panel) closePanel(); }
    else if (enter) { state.panel ? confirmPanel() : openPanel(); }
    else if (up) { state.panel ? moveList(-1) : step(-1); }
    else if (down) { state.panel ? moveList(1) : step(1); }
    else if (left) { state.panel ? setCat(state.gi - 1) : switchCatAndPlay(-1); }
    else if (right) { state.panel ? setCat(state.gi + 1) : switchCatAndPlay(1); }
    if (!state.panel) showOSD();
  }

  // 供安卓外壳转发 Back / Menu 键（WebView 默认不派发这两个键）
  window.__linktvBack = function () { if (state.panel) { closePanel(); return true; } return false; };
  window.__linktvMenu = function () { toggleFav(currentTarget()); };

  // ---------------- 触屏 / 鼠标 ----------------
  $("stage").addEventListener("click", function () { if (!state.panel) openPanel(); else closePanel(); });
  panel.addEventListener("click", function (e) { if (e.target === panel) closePanel(); });
  document.addEventListener("mousemove", function () { if (!state.panel) showOSD(); });

  // ---------------- 云端清单（script 加载，避开 file:// 的 CORS） ----------------
  window.__LINKTV_SOURCES_READY__ = function (map) { applySources(map); };
  function loadScript(src) {
    var s = document.createElement("script"); s.src = src;
    s.onerror = function () {}; document.head.appendChild(s);
  }

  // ---------------- 启动 ----------------
  applySources(BUILTIN);
  buildGroups();
  var last = null; try { last = localStorage.getItem(LAST_KEY); } catch (e) {}
  var start = 0;
  if (last) for (var i = 0; i < flat.length; i++) { if (flat[i].ch.name === last) { start = i; break; } }
  playAt(start);
  showOSD();

  document.addEventListener("keydown", onKey);
  setInterval(function () {
    var d = new Date(); $("osd-clock").textContent = pad(d.getHours()) + ":" + pad(d.getMinutes());
    paintProgram(state.current);
  }, 1000);

  // 先内置清单、再云端清单（真机联网时自动更新，失败静默用内置）
  loadScript("data/sources.js");
  if (REMOTE_SRC) setTimeout(function () { loadScript(REMOTE_SRC); }, 400);
})();
