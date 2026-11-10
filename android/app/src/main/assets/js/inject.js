/**
 * LinkTV v1.2.0 —— 官方直播页注入层（核心）
 *
 * 由 MainActivity 在页面加载完成后注入到「央视频官方直播页」的 WebView 上下文中。
 *  1. clean()：只保留官方页面自己的 <video>，隐藏浮层/导航/节目单，把画面钉成全屏
 *  2. 叠加 LinkTV 的 UI（OSD / 选台面板 / 右上角收藏提示 / 换台遮罩 / 错误提示）
 *  3. 换台：优先点击页面内频道元素（不刷新、快），失败降级 ?pid= 整页导航
 *  4. 20 秒看门狗：无画面自动切央视网备用线路，全程有可见状态文字
 *  5. 按键由 Android dispatchKeyEvent 转发为 __LINKTV_KEY__(name)
 *
 * 不写死网页 class；不含任何 m3u8 直链，播放完全交给官方网页。
 * 本地 file:// 打开时进入 demo 模式（不清理、不导航），用于界面回归验证。
 */
(function () {
  "use strict";
  if (window.__LINKTV_BOOTED__) return;
  window.__LINKTV_BOOTED__ = true;

  var CFG = window.LINKTV_CHANNELS || { GROUPS: [], flatten: function () { return []; } };
  var HOME = CFG.HOME || "https://www.yangshipin.cn/tv/home";
  var BACKUP = CFG.BACKUP_HOME || "https://tv.cctv.com/live/";
  var DEMO = !/yangshipin\.cn|cctv\.com/.test(location.hostname);

  // ---------------- 存储桥（Java SharedPreferences，降级 localStorage，跨页面/跨域） ----------------
  // 约定：字符串原样存（Java 侧也能直接读频道名）；数组/对象存 JSON 字符串，读回时解析
  function sGet(k, d) {
    var v = null;
    try { if (window.LinkTVStore && window.LinkTVStore.get) v = window.LinkTVStore.get(k); } catch (e) {}
    if (v == null) { try { v = localStorage.getItem("ltv_" + k); } catch (e) {} }
    if (v == null || v === "") return d;
    if (typeof v === "string" && /^[\[{]/.test(v)) {
      try { return JSON.parse(v); } catch (e2) { return d; }
    }
    return v;
  }
  function sSet(k, v) {
    var s = (typeof v === "string") ? v : JSON.stringify(v);
    try { if (window.LinkTVStore && window.LinkTVStore.set) { window.LinkTVStore.set(k, s); return; } } catch (e) {}
    try { localStorage.setItem("ltv_" + k, s); } catch (e) {}
  }
  function sExit() { try { if (window.LinkTVStore && window.LinkTVStore.exit) window.LinkTVStore.exit(); } catch (e) {} }

  // ---------------- CSS ----------------
  var CSS = [
    "#ltv-ui{position:fixed;inset:0;z-index:2147483600;pointer-events:none;font-family:'PingFang SC','Microsoft YaHei','Noto Sans CJK SC',system-ui,sans-serif;color:#f2f5fc;user-select:none;-webkit-user-select:none;overflow:hidden}",
    "#ltv-ui *{box-sizing:border-box}",
    "#ltv-mask{position:absolute;inset:0;background:#05070d;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;opacity:0;visibility:hidden;transition:opacity .18s;z-index:40}",
    "#ltv-mask.on{opacity:1;visibility:visible}",
    "#ltv-mnum{font-size:clamp(60px,13vh,150px);font-weight:900;color:#6c8cff;letter-spacing:2px;line-height:1}",
    "#ltv-mname{font-size:clamp(26px,4.4vh,54px);font-weight:800}",
    "#ltv-mtip{font-size:clamp(13px,1.9vh,22px);color:#98a4bd;letter-spacing:1px}",
    "#ltv-mdots{display:flex;gap:10px;margin-top:6px}",
    "#ltv-mdots i{width:9px;height:9px;border-radius:50%;background:#6c8cff;animation:ltvblink 1.2s infinite}",
    "#ltv-mdots i:nth-child(2){animation-delay:.2s}#ltv-mdots i:nth-child(3){animation-delay:.4s}",
    "@keyframes ltvblink{0%,80%,100%{opacity:.25;transform:scale(.8)}40%{opacity:1;transform:scale(1.15)}}",
    "#ltv-osd{position:absolute;inset:0;opacity:1;transition:opacity .45s;z-index:20;background:linear-gradient(180deg,rgba(0,0,0,.62) 0%,transparent 26%,transparent 62%,rgba(0,0,0,.78) 100%)}",
    "#ltv-osd.hide{opacity:0}",
    ".ltv-top{position:absolute;top:0;left:0;right:0;display:flex;justify-content:space-between;align-items:flex-start;padding:clamp(22px,4.2vh,52px) clamp(28px,4vw,68px)}",
    ".ltv-tl{display:flex;flex-direction:column;gap:6px}",
    ".ltv-brand{font-size:clamp(18px,2.1vh,27px);font-weight:800;letter-spacing:2px;opacity:.92}.ltv-brand span{color:#6c8cff}",
    ".ltv-ch{font-size:clamp(30px,4.6vh,58px);font-weight:800;line-height:1.12;text-shadow:0 3px 22px rgba(0,0,0,.6)}",
    ".ltv-cat{font-size:clamp(13px,1.7vh,19px);color:#98a4bd;letter-spacing:1px;margin-top:2px}",
    ".ltv-tr{display:flex;flex-direction:column;align-items:flex-end;gap:8px}",
    ".ltv-res{font-size:clamp(13px,1.7vh,19px);font-weight:700;letter-spacing:1px;padding:7px 15px;border-radius:9px;background:rgba(108,140,255,.18);border:1px solid rgba(108,140,255,.45);color:#cdd9ff}",
    ".ltv-clock{font-size:clamp(15px,2vh,23px);font-weight:600;color:#98a4bd;font-variant-numeric:tabular-nums}",
    ".ltv-bottom{position:absolute;left:0;right:0;bottom:0;padding:0 clamp(28px,4vw,68px) clamp(22px,4vh,50px)}",
    ".ltv-progrow{display:flex;align-items:center;gap:16px;margin-bottom:12px}",
    ".ltv-live{display:inline-flex;align-items:center;gap:8px;flex:none;font-size:clamp(12px,1.5vh,17px);font-weight:800;letter-spacing:1.5px;background:#ff4d5e;padding:5px 12px;border-radius:7px}",
    ".ltv-live i{width:8px;height:8px;border-radius:50%;background:#fff;animation:ltvblink 1.4s infinite}",
    ".ltv-prog{font-size:clamp(17px,2.5vh,30px);font-weight:600;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-shadow:0 2px 14px rgba(0,0,0,.7)}",
    ".ltv-ptime{font-size:clamp(13px,1.7vh,19px);color:#98a4bd;font-variant-numeric:tabular-nums;flex:none}",
    ".ltv-bar{height:6px;border-radius:6px;background:rgba(255,255,255,.2);overflow:hidden}",
    ".ltv-barfill{height:100%;width:0%;border-radius:6px;background:linear-gradient(90deg,#6c8cff,#a06bff);transition:width .8s linear}",
    ".ltv-hint{margin-top:14px;font-size:clamp(12px,1.5vh,17px);color:#98a4bd;letter-spacing:1px}",
    "#ltv-toast{position:absolute;top:clamp(22px,4vh,52px);right:clamp(28px,4vw,68px);z-index:60;padding:16px 30px;border-radius:14px;font-size:clamp(19px,2.8vh,32px);font-weight:800;background:rgba(8,12,24,.9);border:1px solid rgba(255,207,77,.5);color:#ffcf4d;box-shadow:0 16px 50px rgba(0,0,0,.55);opacity:0;transform:translateY(-12px);transition:opacity .25s,transform .25s;pointer-events:none}",
    "#ltv-toast.on{opacity:1;transform:translateY(0)}",
    "#ltv-panel{position:absolute;inset:0;z-index:30;display:none;background:#080c17}",
    "#ltv-panel.on{display:flex}",
    "#ltv-cats{flex:0 0 clamp(190px,20vw,300px);padding:clamp(26px,5vh,60px) clamp(16px,2vw,34px);display:flex;flex-direction:column;gap:clamp(12px,2vh,22px);border-right:1px solid rgba(255,255,255,.09);overflow:hidden}",
    ".ltv-ctitle{font-size:clamp(13px,1.6vh,18px);color:#98a4bd;letter-spacing:3px;margin-bottom:6px}",
    ".ltv-cat{display:flex;align-items:center;gap:14px;padding:clamp(14px,2.2vh,24px) clamp(14px,1.6vw,24px);font-size:clamp(19px,2.7vh,32px);font-weight:700;border-radius:13px;border:2px solid transparent;background:rgba(255,255,255,.055);color:#98a4bd;transition:all .13s;cursor:pointer;pointer-events:auto}",
    ".ltv-cat .ic{font-size:clamp(19px,2.5vh,30px)}.ltv-cat .cnt{margin-left:auto;font-size:clamp(12px,1.5vh,17px);opacity:.7;font-weight:500}",
    ".ltv-cat.active{color:#fff;background:linear-gradient(135deg,rgba(108,140,255,.3),rgba(160,107,255,.2));border-color:#6c8cff}",
    ".ltv-cat.focus{outline:3px solid #ffcf4d;outline-offset:2px}",
    ".ltv-pwrap{flex:1;min-width:0;display:flex;flex-direction:column;padding:clamp(24px,4.5vh,54px) clamp(20px,2.6vw,46px)}",
    ".ltv-phead{display:flex;align-items:baseline;gap:18px;margin-bottom:clamp(14px,2.4vh,26px);flex:none}",
    ".ltv-ptitle{font-size:clamp(24px,3.4vh,40px);font-weight:800}.ltv-pcount{font-size:clamp(13px,1.7vh,19px);color:#98a4bd}",
    "#ltv-plist{list-style:none;flex:1;min-height:0;overflow-y:auto;overflow-x:hidden;padding-right:10px;scrollbar-width:thin;scrollbar-color:rgba(255,255,255,.2) transparent}",
    ".ltv-ch-row{display:flex;align-items:center;gap:16px;padding:clamp(11px,1.7vh,19px) clamp(14px,1.4vw,22px);margin-bottom:clamp(6px,1vh,11px);border-radius:11px;background:rgba(255,255,255,.055);border:2px solid transparent;transition:all .1s;cursor:pointer;pointer-events:auto}",
    ".ltv-ch-row .num{width:42px;flex:none;text-align:right;color:#98a4bd;font-size:clamp(13px,1.7vh,19px);font-variant-numeric:tabular-nums}",
    ".ltv-ch-row .nm{flex:1;font-size:clamp(17px,2.3vh,27px);font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}",
    ".ltv-ch-row .star{flex:none;width:34px;text-align:center;font-size:clamp(18px,2.4vh,28px);color:rgba(255,255,255,.25)}",
    ".ltv-ch-row .star.on{color:#ffcf4d}",
    ".ltv-ch-row.playing{box-shadow:inset 3px 0 0 #6c8cff}.ltv-ch-row.playing .nm{color:#cdd9ff}",
    ".ltv-ch-row.focus{border-color:#ffcf4d;background:rgba(108,140,255,.22);transform:translateX(4px)}",
    ".ltv-pfoot{flex:none;margin-top:clamp(10px,1.8vh,20px);font-size:clamp(12px,1.5vh,17px);color:#98a4bd;letter-spacing:1px}",
    ".ltv-empty{color:#98a4bd;font-size:clamp(16px,2.2vh,24px);padding:40px 8px;text-align:center}",
    "#ltv-err{position:absolute;left:50%;bottom:clamp(90px,14vh,160px);transform:translateX(-50%);z-index:35;max-width:80%;padding:14px 26px;border-radius:12px;background:rgba(255,77,94,.14);border:1px solid rgba(255,77,94,.5);color:#ffc9cf;font-size:clamp(14px,2vh,22px);text-align:center;display:none}",
    "@media (max-height:500px){#ltv-cats{padding-top:18px;padding-bottom:18px;gap:10px}.ltv-pwrap{padding-top:16px;padding-bottom:16px}.ltv-top{padding-top:18px}.ltv-bottom{padding-bottom:16px}}"
  ].join("");

  function $(id) { return document.getElementById(id); }
  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  // ---------------- 状态 ----------------
  // playGi/playCi：当前「正在播放」的频道；gi/ci：面板中的高亮位置（两者独立）
  var ST = {
    playGi: 0, playCi: 0,
    gi: 0, ci: 0,
    catFocus: 0,
    zone: "list",          // 'list' 右栏频道 / 'cats' 左栏分类
    panel: false,
    fav: sGet("fav", []) || [],
    prog: null,
    since: Date.now(),
    stage: "start",        // start / connecting / playing / backup / failed
    watchdog: null, switchTimer: null, osdTimer: null, toastTimer: null,
    lastSrc: "", pendingCh: null, suppressDocClick: 0
  };
  var GROUPS = [], UI = {};

  function rebuild() {
    var favCh = ST.fav.map(function (f) { return findIn(CFG.GROUPS, f.n); }).filter(Boolean);
    GROUPS = CFG.GROUPS.slice(0, 2).concat([{ name: "收藏夹", ic: "★", channels: favCh }]);
  }
  function findIn(groups, n) {
    for (var g = 0; g < groups.length; g++) {
      var arr = groups[g].channels;
      for (var j = 0; j < arr.length; j++) if (arr[j].n === n) return arr[j];
    }
    return null;
  }
  function isFav(n) { return ST.fav.some(function (f) { return f.n === n; }); }
  function playCh() { var g = GROUPS[ST.playGi]; return (g && g.channels[ST.playCi]) || GROUPS[0].channels[0]; }
  function panelCh() { var g = GROUPS[ST.gi]; return g && g.channels[ST.ci] ? g.channels[ST.ci] : null; }
  function locate(ch) {
    for (var g = 0; g < GROUPS.length; g++) for (var j = 0; j < GROUPS[g].channels.length; j++) {
      if (GROUPS[g].channels[j] === ch) return { gi: g, ci: j };
    }
    return null;
  }
  function wrap(n, len) { if (!len) return 0; return ((n % len) + len) % len; }
  function pad(x) { return x < 10 ? "0" + x : "" + x; }
  function hhmm(d) { return pad(d.getHours()) + ":" + pad(d.getMinutes()); }

  // ---------------- UI ----------------
  function buildUI() {
    var root = el("div");
    root.id = "ltv-ui";
    root.setAttribute("data-ltv-ui", "1");
    root.innerHTML = [
      '<style id="ltv-css"></style>',
      '<div id="ltv-osd">',
      '  <div class="ltv-top"><div class="ltv-tl">',
      '    <div class="ltv-brand">Link<span>TV</span></div>',
      '    <div id="ltv-chn" class="ltv-ch">CCTV-1 综合</div>',
      '    <div id="ltv-catt" class="ltv-cat">央视 · 01 / 18</div>',
      '  </div><div class="ltv-tr">',
      '    <div id="ltv-res" class="ltv-res">HD</div>',
      '    <div id="ltv-clock" class="ltv-clock">--:--</div>',
      '  </div></div>',
      '  <div class="ltv-bottom">',
      '    <div class="ltv-progrow"><span class="ltv-live"><i></i>LIVE</span>',
      '      <span id="ltv-prog" class="ltv-prog">正在直播</span>',
      '      <span id="ltv-ptime" class="ltv-ptime">--:-- / --:--</span></div>',
      '    <div class="ltv-bar"><div id="ltv-barfill" class="ltv-barfill"></div></div>',
      '    <div class="ltv-hint">▲▼ 换台　◀▶ 换分类　确定 选台　菜单 收藏　返回 收起</div>',
      '  </div>',
      '</div>',
      '<div id="ltv-panel">',
      '  <nav id="ltv-cats"></nav>',
      '  <div class="ltv-pwrap">',
      '    <div class="ltv-phead"><div id="ltv-ptitle" class="ltv-ptitle">央视</div><div id="ltv-pcount" class="ltv-pcount"></div></div>',
      '    <ul id="ltv-plist"></ul>',
      '    <div class="ltv-pfoot">▲▼ 选频道　◀ 焦点到分类　▶ 切分类　确定 播放　菜单 收藏　返回 收起</div>',
      '  </div>',
      '</div>',
      '<div id="ltv-toast"></div>',
      '<div id="ltv-err"></div>',
      '<div id="ltv-mask"><div id="ltv-mnum">01</div><div id="ltv-mname">CCTV-1 综合</div>',
      '  <div id="ltv-mtip">正在连接官方直播…</div>',
      '  <div id="ltv-mdots"><i></i><i></i><i></i></div></div>'
    ].join("");
    (document.documentElement || document.body).appendChild(root);
    $("ltv-css").textContent = CSS;
    ["osd", "panel", "cats", "plist", "ptitle", "pcount", "toast", "err", "mask",
     "mnum", "mname", "mtip", "chn", "catt", "res", "clock", "prog", "ptime", "barfill"
    ].forEach(function (k) { UI[k] = $("ltv-" + k); });

    // 面板背景点击收起
    UI.panel.addEventListener("click", function (e) { if (e.target === UI.panel) closePanel(); });
    // 全屏点击呼出面板（触屏 / demo）。换台时页面内合成点击需抑制，避免刚收起又弹出
    document.addEventListener("click", function (e) {
      if (Date.now() < ST.suppressDocClick) return;
      if (root.contains(e.target)) return;
      if (!ST.panel) openPanel();
    });
  }

  // ---------------- 页面清理：只留官方 video，钉全屏 ----------------
  function biggestVideo() {
    var vs = document.querySelectorAll("video"), best = null, area = 0;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i], r = v.getBoundingClientRect(), a = (r.width * r.height) || (v.clientWidth * v.clientHeight);
      if (a > area) { area = a; best = v; }
    }
    return best || vs[0] || null;
  }
  function clean() {
    if (DEMO) return false;
    var v = biggestVideo();
    if (!v) return false;
    var node = v, guard = 0;
    while (node && node !== document.body && node !== document.documentElement && guard++ < 30) {
      var p = node.parentElement;
      if (!p) break;
      for (var i = 0; i < p.children.length; i++) {
        var sib = p.children[i];
        if (sib === node) continue;
        if (sib.hasAttribute && sib.hasAttribute("data-ltv-ui")) continue;
        if (sib.tagName === "SCRIPT" || sib.tagName === "STYLE" || sib.tagName === "LINK") continue;
        try { sib.style.setProperty("display", "none", "important"); } catch (e) {}
      }
      node = p;
    }
    var s = v.style;
    s.setProperty("position", "fixed", "important");
    s.setProperty("top", "0", "important");
    s.setProperty("left", "0", "important");
    s.setProperty("width", "100vw", "important");
    s.setProperty("height", "100vh", "important");
    s.setProperty("max-width", "none", "important");
    s.setProperty("max-height", "none", "important");
    s.setProperty("object-fit", "contain", "important");
    s.setProperty("z-index", "2147483000", "important");
    s.setProperty("background", "#000", "important");
    try {
      document.documentElement.style.setProperty("overflow", "hidden", "important");
      document.documentElement.style.setProperty("background", "#000", "important");
      document.body.style.setProperty("overflow", "hidden", "important");
      document.body.style.setProperty("margin", "0", "important");
      document.body.style.setProperty("background", "#000", "important");
    } catch (e) {}
    return true;
  }

  // ---------------- 遮罩 / 提示 / OSD ----------------
  function mask(on, ch, tip) {
    if (!UI.mask) return;
    if (on) {
      var pos = locate(ch) || { ci: ST.ci };
      UI.mnum.textContent = pad(pos.ci + 1);
      UI.mname.textContent = ch ? ch.n : "LinkTV";
      UI.mtip.textContent = tip || "正在连接官方直播…";
      UI.mask.classList.add("on");
    } else UI.mask.classList.remove("on");
  }
  function showErr(txt) {
    if (!UI.err) return;
    if (!txt) { UI.err.style.display = "none"; return; }
    UI.err.textContent = txt; UI.err.style.display = "block";
  }
  function toast(txt) {
    UI.toast.textContent = txt; UI.toast.classList.add("on");
    clearTimeout(ST.toastTimer);
    ST.toastTimer = setTimeout(function () { UI.toast.classList.remove("on"); }, 2000);
  }
  function updateRes(h) {
    var t = "HD";
    if (h >= 2000) t = "4K"; else if (h >= 1000) t = "1080P";
    else if (h >= 700) t = "720P"; else if (h >= 560) t = "576P";
    else if (h > 0) t = "480P";
    UI.res.textContent = t;
  }
  function segInfo() {
    var now = new Date(), st = new Date(now);
    st.setMinutes(now.getMinutes() < 30 ? 0 : 30, 0, 0);
    var et = new Date(st.getTime() + 30 * 60000);
    return { p: (now - st) / (et - st), st: hhmm(st), et: hhmm(et) };
  }
  function paintProgram() {
    var ch = playCh(), p, s;
    if (ST.prog) {
      var now = Date.now();
      p = Math.max(0, Math.min(1, (now - ST.prog.st) / (ST.prog.et - ST.prog.st)));
      UI.prog.textContent = ST.prog.title;
      UI.ptime.textContent = hhmm(new Date(ST.prog.st)) + " - " + hhmm(new Date(ST.prog.et));
    } else {
      s = segInfo(); p = s.p;
      UI.prog.textContent = "正在直播 · " + ch.n;
      UI.ptime.textContent = s.st + " - " + s.et;
    }
    UI.barfill.style.width = (p * 100).toFixed(1) + "%";
  }
  function updateOSD() {
    var ch = playCh(), g = GROUPS[ST.playGi];
    UI.chn.textContent = ch.n;
    UI.catt.textContent = g.name + " · " + pad(ST.playCi + 1) + " / " + pad(g.channels.length);
    ST.prog = null;
    paintProgram();
    fetchEpg(ch);
  }
  function showOSD() {
    if (ST.panel) return;
    UI.osd.classList.remove("hide");
    clearTimeout(ST.osdTimer);
    ST.osdTimer = setTimeout(function () { UI.osd.classList.add("hide"); }, 3000);
  }

  // 官方 EPG（protobuf 文本，按节目条目切段解析；失败静默降级为「正在直播 · 频道名」）
  function fetchEpg(ch) {
    if (DEMO || !ch || !ch.p || !window.fetch) return;
    var d = new Date(), ds = "" + d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate());
    fetch("https://capi.yangshipin.cn/api/yspepg/program/" + ch.p + "/" + ds)
      .then(function (r) { return r.text(); })
      .then(function (t) {
        if (playCh() !== ch) return;
        var now = new Date(), cur = now.getHours() * 60 + now.getMinutes(), best = null;
        function mins(s) { var a = s.split(":"); return (+a[0]) * 60 + (+a[1]); }
        // 每个节目以「\n\b + 8 位节目 ID」开头
        var blocks = t.split(/\n\x08\d{8}/);
        for (var i = 1; i < blocks.length; i++) {
          var seg = blocks[i];
          var tm = /\*\x05(\d{2}:\d{2})2\x05(\d{2}:\d{2})/.exec(seg);
          if (!tm) continue;
          // 标题在 \x12<长度字节> 之后、\x18 之前，剔除一切控制字符
          var tt = /\x12[\s\S]([^\x18]*?)\x18/.exec(seg);
          var title = tt ? tt[1].replace(/[\x00-\x1f]/g, "").trim() : "";
          if (!title || title.length > 50) continue;
          var a = mins(tm[1]), b = mins(tm[2]);
          if (cur >= a && cur < b) {
            var sd = new Date(now), ed = new Date(now);
            sd.setHours(0, a, 0, 0); ed.setHours(0, b, 0, 0);
            if (b <= a) ed.setTime(ed.getTime() + 86400000);
            best = { title: title, st: sd.getTime(), et: ed.getTime() };
            break;
          }
        }
        if (best) { ST.prog = best; paintProgram(); }
      }).catch(function () {});
  }

  // ---------------- 播放检测 / 20 秒看门狗 ----------------
  function videoOK() {
    var v = biggestVideo();
    return !!(v && v.videoWidth > 0 && !v.paused && v.readyState >= 2);
  }
  function onPictureUp() {
    var v = biggestVideo();
    if (v && v.videoHeight) updateRes(v.videoHeight);
    ST.stage = "playing";
    showErr(""); mask(false);
    clearTimeout(ST.watchdog);
    showOSD();
  }
  function startWatchdog(ch) {
    clearTimeout(ST.watchdog);
    ST.since = Date.now();
    ST.stage = "connecting";
    var switched = false;
    function tick() {
      if (videoOK()) { onPictureUp(); return; }
      var elapsed = Date.now() - ST.since;
      if (!switched && elapsed > 20000) {
        switched = true; ST.stage = "backup";
        mask(true, ch, "央视频连接较慢，正在切换备用线路…");
        showErr("已切换到央视网备用线路");
        doBackup(ch);
        ST.since = Date.now();
        ST.watchdog = setTimeout(tick, 900);
        return;
      }
      if (switched && elapsed > 20000) {
        ST.stage = "failed";
        mask(true, ch, "无法连接官方直播，按确定键重试");
        showErr("网络无法访问央视频 / 央视网，请检查电视网络后按确定重试。");
        return;
      }
      ST.watchdog = setTimeout(tick, 700);
    }
    ST.watchdog = setTimeout(tick, 900);
  }
  function doBackup(ch) {
    if (DEMO) return;
    try { location.href = ch && ch.b ? "https://tv.cctv.com/live/" + ch.b + "/" : BACKUP; } catch (e) {}
  }

  // ---------------- 换台 ----------------
  function clickChannelInPage(ch) {
    if (DEMO || !ch || !ch.y) return false;
    ST.suppressDocClick = Date.now() + 3200;   // 页面内合成点击不触发「点击呼出面板」
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), hit = null;
    while (w.nextNode()) {
      if ((w.currentNode.textContent || "").trim() === ch.y) { hit = w.currentNode.parentElement; break; }
    }
    if (!hit) return false;
    var e = hit;
    for (var i = 0; i < 4 && e; i++) { try { e.click(); } catch (er) {} e = e.parentElement; }
    return true;
  }
  function switchTo(ch) {
    if (!ch) return;
    ST.pendingCh = ch;
    ST.prog = null;
    showErr("");
    sSet("last", ch.n);
    var pos = locate(ch);
    if (pos) { ST.playGi = pos.gi; ST.playCi = pos.ci; }
    if (ST.panel) closePanel(); else UI.osd.classList.add("hide");
    updateOSD();
    mask(true, ch, "正在连接官方直播…");
    ST.lastSrc = (function () { var v = biggestVideo(); return v ? (v.currentSrc || "") : ""; })();

    if (DEMO) { setTimeout(onPictureUp, 550); return; }

    var clicked = clickChannelInPage(ch);
    clearTimeout(ST.switchTimer);
    ST.switchTimer = setTimeout(function () {
      var v = biggestVideo(), src = v ? (v.currentSrc || "") : "";
      if (ST.stage === "playing") return;
      if (src && src !== ST.lastSrc && videoOK()) { onPictureUp(); return; }
      try { location.href = HOME + "?pid=" + ch.p; } catch (e) {}
    }, clicked ? 2400 : 300);
    startWatchdog(ch);
  }

  // ---------------- 面板 ----------------
  function openPanel() {
    ST.panel = true;
    ST.zone = "list";
    ST.gi = ST.playGi; ST.ci = ST.playCi; ST.catFocus = ST.playGi;
    UI.panel.classList.add("on");
    UI.osd.classList.add("hide");
    clearTimeout(ST.osdTimer);
    renderPanel();
  }
  function closePanel() {
    ST.panel = false;
    UI.panel.classList.remove("on");
    showOSD();
  }
  function setCat(gi) {
    ST.gi = wrap(gi, GROUPS.length);
    ST.ci = 0;
    renderPanel();
  }
  function renderPanel() {
    // 左栏分类固定不滚动（不使用 scrollIntoView）
    UI.cats.innerHTML = "";
    UI.cats.appendChild(el("div", "ltv-ctitle", "频道分类"));
    GROUPS.forEach(function (g, i) {
      var cls = "ltv-cat" + (i === ST.gi ? " active" : "") +
        (ST.zone === "cats" && i === ST.catFocus ? " focus" : "");
      var d = el("div", cls);
      d.innerHTML = '<span class="ic">' + g.ic + '</span><span>' + g.name + '</span><span class="cnt">' + g.channels.length + '</span>';
      d.addEventListener("click", (function (idx) { return function () { ST.catFocus = idx; setCat(idx); ST.zone = "cats"; renderPanel(); }; })(i));
      UI.cats.appendChild(d);
    });

    var g = GROUPS[ST.gi];
    UI.ptitle.textContent = g.name;
    UI.pcount.textContent = "共 " + g.channels.length + " 个频道";

    var ul = UI.plist;
    ul.innerHTML = "";
    if (!g.channels.length) {
      ul.appendChild(el("div", "ltv-empty", "暂无收藏频道 · 选中频道按菜单键或点星标即可收藏"));
      return;
    }
    var focusEl = null;
    g.channels.forEach(function (ch, li) {
      var isPlay = (ST.playGi === ST.gi && ST.playCi === li);
      var row = el("li", "ltv-ch-row" + (li === ST.ci && ST.zone === "list" ? " focus" : "") + (isPlay ? " playing" : ""));
      row.innerHTML = '<span class="num">' + pad(li + 1) + '</span><span class="nm">' + ch.n + '</span>' +
        '<span class="star' + (isFav(ch.n) ? " on" : "") + '">' + (isFav(ch.n) ? "★" : "☆") + '</span>';
      row.querySelector(".star").addEventListener("click", function (e) { e.stopPropagation(); toggleFav(ch); });
      row.addEventListener("click", (function (c) { return function () { switchTo(c); }; })(ch));
      ul.appendChild(row);
      if (li === ST.ci) focusEl = row;
    });
    // 只手动滚动右栏列表容器，绝不触发祖先滚动
    if (focusEl) ul.scrollTop = Math.max(0, focusEl.offsetTop - ul.clientHeight / 2 + focusEl.offsetHeight / 2);
    try { document.documentElement.scrollTop = 0; document.body.scrollTop = 0; } catch (e) {}
  }

  // ---------------- 收藏 ----------------
  function toggleFav(ch) {
    if (!ch) return;
    var i = -1;
    ST.fav.forEach(function (f, k) { if (f.n === ch.n) i = k; });
    var added = i < 0;
    if (added) ST.fav.push({ n: ch.n }); else ST.fav.splice(i, 1);
    sSet("fav", ST.fav);
    rebuild();
    // 以当前高亮项为锚点重建后重新定位
    var g = GROUPS[ST.gi], p = -1;
    for (var k = 0; k < g.channels.length; k++) if (g.channels[k].n === ch.n) { p = k; break; }
    if (p >= 0) ST.ci = p; else if (ST.ci >= g.channels.length) ST.ci = Math.max(0, g.channels.length - 1);
    var pp = locate(playCh());
    if (pp) { ST.playGi = pp.gi; ST.playCi = pp.ci; }
    if (ST.panel) renderPanel();
    toast(added ? "★ 已收藏 · " + ch.n : "☆ 已取消收藏 · " + ch.n);
    if (!ST.panel) showOSD();
  }

  // ---------------- 按键 ----------------
  function playStep(dir) {
    // 上下键：只在当前分类内循环，不跨分类
    var g = GROUPS[ST.playGi];
    if (!g || !g.channels.length) return;
    ST.playCi = wrap(ST.playCi + dir, g.channels.length);
    switchTo(g.channels[ST.playCi]);
  }
  function switchCat(dir) {
    // 左右键：切换分类（跳过空收藏夹），播该分类第一个台
    var n = GROUPS.length, gi = ST.playGi;
    for (var k = 0; k < n; k++) {
      gi = wrap(gi + dir, n);
      if (GROUPS[gi].channels.length) break;
    }
    switchTo(GROUPS[gi].channels[0]);
  }
  function movePanel(dir) {
    if (ST.zone === "cats") {
      ST.catFocus = wrap(ST.catFocus + dir, GROUPS.length);
      setCat(ST.catFocus);           // 左栏上下：切分类，右栏跟随
      ST.zone = "cats";
      renderPanel();
      return;
    }
    var g = GROUPS[ST.gi];
    if (!g.channels.length) return;
    ST.ci = wrap(ST.ci + dir, g.channels.length);   // 右栏上下：分类内循环，到底回顶
    renderPanel();
  }
  function onKey(name) {
    if (name === "menu") { toggleFav(ST.panel && ST.zone === "list" ? panelCh() : playCh()); return; }
    if (name === "back") { if (ST.panel) closePanel(); else sExit(); return; }
    if (name === "ok") {
      if (ST.stage === "failed") { try { location.href = HOME + "?pid=" + playCh().p; } catch (e) {} return; }
      if (!ST.panel) { openPanel(); return; }
      if (ST.zone === "cats") { ST.zone = "list"; setCat(ST.catFocus); return; }
      switchTo(panelCh() || playCh());
      return;
    }
    if (name === "left") {
      if (ST.panel) { ST.zone = "cats"; ST.catFocus = ST.gi; renderPanel(); }  // ← 焦点到频道分类
      else switchCat(-1);
      return;
    }
    if (name === "right") {
      if (ST.panel) {
        if (ST.zone === "cats") { ST.zone = "list"; setCat(ST.catFocus); return; }
        setCat(ST.gi + 1); return;
      }
      switchCat(1);
      return;
    }
    if (name === "up") { ST.panel ? movePanel(-1) : playStep(-1); return; }
    if (name === "down") { ST.panel ? movePanel(1) : playStep(1); return; }
  }
  window.__LINKTV_KEY__ = onKey;

  // 本地演示：键盘直接驱动；真机由 Android 转发，此处作为兜底去重
  document.addEventListener("keydown", function (e) {
    if (window.__LINKTV_NATIVE_KEYS__) return;
    var map = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right",
      Enter: "ok", NumpadEnter: "ok", Escape: "back", Backspace: "back", ContextMenu: "menu",
      " ": "ok", m: "menu", M: "menu" };
    var name = map[e.key] || ({ 38: "up", 40: "down", 37: "left", 39: "right", 13: "ok", 27: "back", 8: "back", 93: "menu" })[e.keyCode];
    if (!name) return;
    e.preventDefault(); e.stopPropagation();
    onKey(name);
  }, true);

  // ---------------- 启动 ----------------
  function boot() {
    rebuild();
    buildUI();
    var pid = (location.search.match(/[?&]pid=(\d+)/) || [])[1] || "";
    var lastName = sGet("last", "") || "";
    var target = null;
    if (pid) {
      for (var g = 0; g < GROUPS.length; g++) for (var j = 0; j < GROUPS[g].channels.length; j++) {
        if (GROUPS[g].channels[j].p === pid) { target = { gi: g, ci: j }; break; }
      }
    }
    if (!target && lastName) {
      var ch = findIn(CFG.GROUPS, lastName);
      if (ch) target = locate(ch);
    }
    if (target) { ST.playGi = target.gi; ST.playCi = target.ci; }
    ST.gi = ST.playGi; ST.ci = ST.playCi;

    if (!DEMO) {
      clean();
      setInterval(clean, 900);
      if (window.MutationObserver) {
        try { new MutationObserver(function () { clean(); }).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
      }
    }
    updateOSD();
    mask(true, playCh(), "正在连接官方直播…");
    if (DEMO) setTimeout(onPictureUp, 450);   // 本地回归：直接出画面
    else startWatchdog(playCh());
    setInterval(function () {
      var d = new Date();
      UI.clock.textContent = pad(d.getHours()) + ":" + pad(d.getMinutes());
      paintProgram();
    }, 1000);

    window.__LINKTV_STATE__ = function () {
      return JSON.stringify({ stage: ST.stage, channel: playCh().n, panel: ST.panel,
        zone: ST.zone, cat: GROUPS[ST.playGi].name, ci: ST.playCi + 1 });
    };
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
