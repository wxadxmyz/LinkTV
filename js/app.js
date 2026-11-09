/* =========================================================================
 * LinkTV —— 核心逻辑
 *  - 频道分组渲染 / 搜索 / 收藏
 *  - 播放器：hls.js（m3u8） + 原生 HLS 回退（安卓 WebView 原生支持）
 *  - 电视遥控器（D-pad）方向键导航 + 鼠标点击双支持
 *  - M3U 导入（文本 / 远程 URL）
 * ========================================================================= */
(function () {
  "use strict";

  var groups = (window.CHANNEL_GROUPS || []).map(function (g) {
    return { name: g.name, channels: g.channels.slice() };
  });
  var remoteM3u = window.REMOTE_M3U_URL || "";

  // 展平所有频道，便于收藏/搜索
  function allChannels() {
    var out = [];
    groups.forEach(function (g) {
      g.channels.forEach(function (c) { out.push(c); });
    });
    return out;
  }

  // ---------- 状态 ----------
  var state = {
    activeGroup: 0,
    listIndex: 0,
    zone: "list",        // tabs | list | controls
    tabsIndex: 0,
    ctrlIndex: 0,
    favMode: false,      // 是否处于收藏夹视图
    searchMode: false,
    current: null,       // 当前播放频道
    hls: null
  };
  var FAV_KEY = "tv_live_fav_v1";

  // ---------- DOM ----------
  var $ = function (id) { return document.getElementById(id); };
  var tabsEl = $("tabs");
  var listEl = $("channel-list");
  var video = $("player");
  var overlay = $("overlay");
  var npName = $("np-name");
  var npStatus = $("np-status");
  var searchInput = $("search");
  var controls = $("controls");
  var ctrlBtns = [
    $("btn-playpause"), $("btn-full"), $("btn-fav-toggle")
  ];

  // ---------- 收藏 ----------
  function getFav() {
    try { return JSON.parse(localStorage.getItem(FAV_KEY) || "[]"); }
    catch (e) { return []; }
  }
  function setFav(arr) { localStorage.setItem(FAV_KEY, JSON.stringify(arr)); }
  function isFav(ch) {
    return getFav().some(function (f) { return f.name === ch.name && f.url === ch.url; });
  }
  function toggleFav(ch) {
    var f = getFav();
    var i = f.findIndex(function (x) { return x.name === ch.name && x.url === ch.url; });
    if (i >= 0) f.splice(i, 1); else f.push({ name: ch.name, url: ch.url });
    setFav(f);
    renderList();
    updateFavToggle();
  }

  // ---------- 渲染：分类标签 ----------
  function renderTabs() {
    tabsEl.innerHTML = "";
    groups.forEach(function (g, i) {
      var t = document.createElement("div");
      t.className = "tab" + (i === state.activeGroup && !state.favMode ? " active" : "");
      t.textContent = g.name;
      t.tabIndex = 0;
      t.addEventListener("click", function () { selectGroup(i); });
      tabsEl.appendChild(t);
    });
  }

  // ---------- 当前可见频道列表 ----------
  function currentViewChannels() {
    if (state.favMode) return getFav();
    var kw = searchInput.value.trim().toLowerCase();
    var list = groups[state.activeGroup].channels;
    if (kw) {
      list = list.filter(function (c) {
        return (c.name || "").toLowerCase().indexOf(kw) >= 0;
      });
    }
    return list;
  }

  // ---------- 渲染：频道列表 ----------
  function renderList() {
    listEl.innerHTML = "";
    var list = currentViewChannels();
    if (!list.length) {
      var empty = document.createElement("li");
      empty.className = "channel";
      empty.style.justifyContent = "center";
      empty.style.color = "var(--text-dim)";
      empty.textContent = state.favMode ? "暂无收藏" : "无匹配频道";
      listEl.appendChild(empty);
      return;
    }
    if (state.listIndex >= list.length) state.listIndex = list.length - 1;
    if (state.listIndex < 0) state.listIndex = 0;

    list.forEach(function (c, i) {
      var li = document.createElement("li");
      li.className = "channel";
      li.tabIndex = 0;
      if (state.favMode && state.zone === "list" && i === state.listIndex) li.classList.add("focus");
      if (state.current && c.name === state.current.name && c.url === state.current.url) {
        li.classList.add("active", "playing");
      }
      var idx = document.createElement("span");
      idx.className = "idx"; idx.textContent = (i + 1);
      var name = document.createElement("span");
      name.className = "cname"; name.textContent = c.name || "(未命名)";
      li.appendChild(idx); li.appendChild(name);
      if (isFav(c)) {
        var star = document.createElement("span");
        star.className = "star"; star.textContent = "★";
        li.appendChild(star);
      }
      li.addEventListener("click", function () { state.listIndex = i; playChannel(c); });
      li.addEventListener("focus", function () { state.listIndex = i; });
      listEl.appendChild(li);
    });
    highlightListFocus();
  }

  function highlightListFocus() {
    var items = listEl.querySelectorAll(".channel");
    items.forEach(function (el, i) {
      el.classList.toggle("focus", state.zone === "list" && i === state.listIndex && !el.textContent.match(/暂无|无匹配/));
    });
    var cur = items[state.listIndex];
    if (cur) cur.scrollIntoView({ block: "nearest" });
  }

  // ---------- 播放 ----------
  function playChannel(ch) {
    if (!ch || !ch.url) {
      setStatus(ch ? ch.name : "", "该频道暂未配置可用源（请在 channels.js 或导入中补充）");
      return;
    }
    state.current = ch;
    renderList();
    setStatus(ch.name, "正在连接直播源…");
    overlay.classList.remove("hidden");

    if (state.hls) { state.hls.destroy(); state.hls = null; }
    video.pause();

    var url = ch.url;
    var canNative = video.canPlayType("application/vnd.apple.mpegurl");
    if (window.Hls && window.Hls.isSupported() && !canNative) {
      var hls = new window.Hls({ lowLatencyMode: false, liveDurationInfinity: true });
      state.hls = hls;
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(window.Hls.Events.MANIFEST_PARSED, function () {
        video.play().catch(function () {});
      });
      hls.on(window.Hls.Events.ERROR, function (evt, data) {
        if (data && data.fatal) {
          setStatus(ch.name, "直播源加载失败（源可能失效或网络不可达）");
        }
      });
    } else if (canNative) {
      // 安卓 WebView 原生支持 HLS
      video.src = url;
      video.play().catch(function () {});
      video.addEventListener("error", function () {
        setStatus(ch.name, "直播源加载失败（源可能失效或网络不可达）");
      }, { once: true });
    } else {
      setStatus(ch.name, "当前环境不支持 HLS 播放");
      return;
    }
    video.addEventListener("playing", function () {
      overlay.classList.add("hidden");
      setStatus(ch.name, "直播中");
    }, { once: true });
    updateFavToggle();
  }

  function setStatus(name, status) {
    if (name) npName.textContent = name;
    npStatus.textContent = status;
  }

  function updateFavToggle() {
    if (!state.current) { $("btn-fav-toggle").textContent = "☆ 收藏"; return; }
    $("btn-fav-toggle").textContent = isFav(state.current) ? "★ 已收藏" : "☆ 收藏";
  }

  // ---------- 分类切换 ----------
  function selectGroup(i) {
    state.activeGroup = i;
    state.favMode = false;
    state.searchMode = false;
    state.listIndex = 0;
    state.zone = "list";
    renderTabs();
    renderList();
    focusZone("list");
  }

  // ---------- 焦点管理（电视遥控） ----------
  function focusZone(zone) {
    state.zone = zone;
    renderTabs(); highlightListFocus();
    if (zone === "tabs") {
      var t = tabsEl.children[state.tabsIndex]; if (t) t.classList.add("focus");
    } else if (zone === "controls") {
      ctrlBtns.forEach(function (b, i) { b.classList.toggle("focus", i === state.ctrlIndex); });
    }
  }

  function moveZone(dir) {
    // dir: -1 上, +1 下
    if (state.zone === "tabs" && dir > 0) { state.zone = "list"; highlightListFocus(); }
    else if (state.zone === "list" && dir < 0) { state.zone = "tabs"; focusZone("tabs"); }
    else if (state.zone === "list" && dir > 0) { state.zone = "controls"; focusZone("controls"); }
    else if (state.zone === "controls" && dir < 0) { state.zone = "list"; highlightListFocus(); }
  }

  function moveHorizontal(dir) {
    if (state.zone === "tabs") {
      state.tabsIndex = (state.tabsIndex + dir + groups.length) % groups.length;
      selectGroup(state.tabsIndex);
    } else if (state.zone === "list") {
      // 左右切换分类，体验更贴近电视
      var g = (state.activeGroup + dir + groups.length) % groups.length;
      selectGroup(g); state.tabsIndex = g;
    } else if (state.zone === "controls") {
      state.ctrlIndex = (state.ctrlIndex + dir + ctrlBtns.length) % ctrlBtns.length;
      focusZone("controls");
    }
  }

  function moveVertical(dir) {
    if (state.zone === "list") {
      var n = currentViewChannels().length;
      state.listIndex = Math.max(0, Math.min(n - 1, state.listIndex + dir));
      highlightListFocus();
    } else if (state.zone === "controls") {
      // 控制栏只有一行，上下回到列表
      moveZone(-1);
    }
  }

  function activate() {
    if (state.zone === "tabs") { selectGroup(state.tabsIndex); }
    else if (state.zone === "list") {
      var c = currentViewChannels()[state.listIndex];
      if (c) playChannel(c);
    } else if (state.zone === "controls") {
      ctrlBtns[state.ctrlIndex].click();
    }
  }

  // ---------- 键盘（含电视 D-pad：方向键=方向，确定=Enter） ----------
  document.addEventListener("keydown", function (e) {
    if (!$("modal").classList.contains("hidden")) return; // 弹窗时不拦截
    switch (e.key) {
      case "ArrowUp": e.preventDefault(); moveZone(-1); break;
      case "ArrowDown": e.preventDefault(); moveZone(1); break;
      case "ArrowLeft": e.preventDefault(); moveHorizontal(-1); break;
      case "ArrowRight": e.preventDefault(); moveHorizontal(1); break;
      case "Enter": e.preventDefault(); activate(); break;
      case " ": e.preventDefault(); togglePlay(); break;
      case "f": case "F": toggleFull(); break;
      case "Menu": e.preventDefault(); openImport(); break;
      default: break;
    }
  });

  function togglePlay() {
    if (video.paused) video.play().catch(function () {}); else video.pause();
  }
  function toggleFull() {
    if (!document.fullscreenElement) {
      $("stage").requestFullscreen && $("stage").requestFullscreen();
    } else { document.exitFullscreen && document.exitFullscreen(); }
  }

  // ---------- 控制栏按钮 ----------
  $("btn-playpause").addEventListener("click", togglePlay);
  $("btn-full").addEventListener("click", toggleFull);
  $("btn-fav-toggle").addEventListener("click", function () {
    if (state.current) toggleFav(state.current);
  });
  $("btn-fav").addEventListener("click", function () {
    state.favMode = !state.favMode;
    state.listIndex = 0; state.zone = "list";
    renderTabs(); renderList(); focusZone("list");
  });
  searchInput.addEventListener("input", function () {
    state.searchMode = true; state.favMode = false; state.listIndex = 0;
    renderList();
  });

  // ---------- 导入 M3U ----------
  function parseM3U(text) {
    var lines = text.split(/\r?\n/);
    var out = [], name = "", pending = null;
    lines.forEach(function (raw) {
      var line = raw.trim();
      if (!line) return;
      if (line.indexOf("#EXTINF") === 0) {
        var m = line.match(/,([^,]*)$/);
        name = m ? m[1].trim() : "频道";
        pending = { name: name, url: "" };
      } else if (line.charAt(0) !== "#") {
        if (pending) { pending.url = line; out.push(pending); pending = null; }
        else out.push({ name: line, url: line });
      }
    });
    return out;
  }

  function openImport() { $("modal").classList.remove("hidden"); $("m3u-msg").textContent = ""; }
  function closeImport() { $("modal").classList.add("hidden"); }
  function applyM3U(text) {
    var parsed = parseM3U(text);
    if (!parsed.length) { $("m3u-msg").style.color = "var(--accent)"; $("m3u-msg").textContent = "未解析到频道，请检查格式"; return; }
    groups.push({ name: "导入-" + (groups.length), channels: parsed });
    renderTabs(); renderList();
    $("m3u-msg").style.color = "var(--ok)";
    $("m3u-msg").textContent = "成功导入 " + parsed.length + " 个频道";
    setTimeout(closeImport, 900);
  }

  $("btn-import").addEventListener("click", openImport);
  $("m3u-close").addEventListener("click", closeImport);
  $("m3u-apply").addEventListener("click", function () { applyM3U($("m3u-input").value); });
  $("m3u-remote").addEventListener("click", function () {
    var url = prompt("输入 M3U 直链 URL：", remoteM3u || "https://example.com/cn.m3u");
    if (!url) return;
    $("m3u-msg").textContent = "正在拉取…";
    fetch(url).then(function (r) { return r.text(); }).then(function (t) { applyM3U(t); })
      .catch(function () { $("m3u-msg").style.color = "var(--accent)"; $("m3u-msg").textContent = "拉取失败（可能跨域或被墙）"; });
  });

  // 显示控制栏（鼠标移动时）
  $("stage").addEventListener("mousemove", function () { controls.classList.add("show"); });

  // ---------- 初始化 ----------
  renderTabs();
  renderList();
  focusZone("list");
  updateFavToggle();
  // 默认自动播放第一个频道（CCTV-1）
  if (groups.length && groups[0].channels.length) {
    playChannel(groups[0].channels[0]);
  }
})();
