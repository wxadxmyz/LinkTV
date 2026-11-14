package com.linktv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkTV v1.5.0 —— 电视专用版外壳
 *
 * v1.4.0 在电视（Chrome 83）真机录像里暴露的问题：
 *  A) 打开后 ~20 秒里先露官网原貌（顶部白导航 / 右侧 CCTV 侧栏 / 底部白条）、
 *     再露一个占满屏的巨大白色播放按钮，之后才出画面。用户感知＝"不能打开就播"。
 *     根因：官方播放器在电视 WebView 上不自动起播，老内核还会自己重载一次；
 *     v1.4.0 又定死「绝不自动导航/reload」，只能干等。
 *  B) 那段窗口里按确定键调不出频道列表：网页重载时 __LINKTV_KEY__ 尚未挂载，
 *     按键被原生返回 NOTREADY 后丢掉；官网侧栏还抢走遥控器焦点。
 *  C) 自检条常驻在画面左下角，碍眼。
 *
 * v1.5.0 对策（核心：把"启动期"从网页手里收回到原生层）：
 *  - 新增【原生启动遮罩】：不透明品牌页盖住 WebView，网页在底下加载/重载/露浮层
 *    都看不见；只有 PROBE 检测到真正出画面（video 非暂停且有尺寸）才淡出揭开。
 *    因此观感上"打开即全屏、直接进播放"，中间态一律不露出。
 *  - 自检条默认隐藏（按 0 才显隐），不再开机常驻。
 *  - 确定键双保险：注入层把 __LINKTV_KEY__ 提前到脚本开头并排队，原生层在
 *    NOTREADY 时也把 ok 排队（pendingOk），注入层一就绪立即补发，绝不丢键。
 *  - 注入层 kickPlay() 主动 video.play() + 合成点击播放按钮，把起播提前；
 *    sweepOverlays() 持续压掉官网重载后重新长出的浮层。
 *  - 仍然坚决不做任何自动 loadUrl / 自动切源 / 自动 reload；唯一重载入口是数字键 9。
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://www.yangshipin.cn/tv/home";
    private static final String BACKUP = "https://tv.cctv.com/live/";

    /** 单次读取注入层诊断，返回短字符串，避免 JSON 转义问题。
     *  第 7 段 booted：1=注入层已 boot（按键可用），0=脚本在但还没 boot。
     *  第 8 段 ui：#ltv-ui 是否还在文档里（网页重载会把它冲掉）。 */
    private static final String PROBE =
            "(function(){try{" +
            "var d=window.__LINKTV_DIAG__?JSON.parse(window.__LINKTV_DIAG__()):null;" +
            "if(!d)return (window.__LINKTV_PRESENT__?'PRESENT':'NO')+'|0|-|-|-|-|-|0|0';" +
            "var v=d.video;return (v&&v.w>0&&!v.paused?'PIC':'NOPIC')+'|'+d.clean+'|'+d.stage+" +
            "'|'+(d.mse?'1':'0')+'|'+(d.wasm?'1':'0')+'|'+d.mode+'|'+(d.booted?'1':'0')+" +
            "'|'+(document.getElementById('ltv-ui')?'1':'0');" +
            "}catch(e){return 'ERR|0|-|-|-|-|-|0|0'}})()";

    /** 补发排队的确定键：注入层 boot 完成后即可开面板 */
    private static final String FLUSH_OK =
            "(function(){try{if(typeof window.__LINKTV_KEY__==='function'){" +
            "window.__LINKTV_KEY__('ok');return 'OK'}return 'NO'}catch(e){return 'ERR'}})()";

    private WebView web;
    private FrameLayout root;
    private TextView diag;
    private FrameLayout bootCover;       // v1.5.0：原生启动遮罩（盖住网页启动期全部中间态）
    private TextView coverTip;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String channelsJs = "", injectJs = "", rawUa = "", chromeVer = "未知";
    private String verName = "1.5.0";   // 从包信息动态读取，保证与 build.gradle 一致
    private long lastBackAt = 0;

    // 自检状态
    private boolean bootOk = false, everPlaying = false, diagHidden = true;  // v1.5.0：自检默认隐藏
    private boolean pendingOk = false;   // v1.5.0：确定键排队，注入层就绪后补发
    private boolean scriptPresent = false; // 注入脚本是否已挂载（避免打断 boot 的重复注入）
    private boolean coverShown = true;   // 启动遮罩是否仍显示
    private int picStreak = 0;           // 连续探测到出画面的次数
    private int injectCount = 0;
    private long pageSince = 0, lastInjectAt = 0;
    private String stageText = "启动中", hostText = "-", bootText = "未注入",
            picText = "等待中", capText = "检测中…", errText = "";
    private String backupSlug = "cctv1";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        prefs = getSharedPreferences("linktv", Context.MODE_PRIVATE);
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (v != null && !v.isEmpty()) verName = v;
        } catch (Exception ignore) {}
        channelsJs = readAsset("js/channels.js");
        injectJs = readAsset("js/inject.js");

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        // 电视上让网页按设备宽度布局，避免 PC 页被缩成中间一条带
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        rawUa = ws.getUserAgentString();
        Matcher cm = Pattern.compile("Chrome/([0-9.]+)").matcher(rawUa == null ? "" : rawUa);
        if (cm.find()) chromeVer = cm.group(1);
        ws.setUserAgentString(buildDesktopUa(rawUa));
        web.setBackgroundColor(0xFF000000);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                // 只更新文字，绝不在这里注入；也不重置 everPlaying。
                // v1.3.0 在此重置 everPlaying，导致每次页面重载后 25s 看门狗
                // 又切一次源 → 无限重载循环。
                resetForNewPage(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                hostText = hostOf(url);
                inject();
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                stageText = "网络异常";
                errText = (desc == null ? String.valueOf(code) : desc) + " · 请检查电视网络";
                setCoverTip("网络连接失败 · 按 [9] 重试");
                updateDiag();   // v1.5.0：不自动弹自检条
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "LinkTVStore");

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        diag = new TextView(this);
        diag.setTextColor(0xFFEAF0FF);
        diag.setTextSize(15f);
        diag.setTypeface(Typeface.MONOSPACE);
        diag.setBackgroundColor(0xDD05070D);
        diag.setPadding(26, 16, 26, 16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        lp.leftMargin = 26;
        lp.bottomMargin = 26;
        root.addView(diag, lp);
        diag.setVisibility(View.GONE);   // v1.5.0：默认隐藏，按 0 才显隐

        buildBootCover();
        root.addView(bootCover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        loadHome();
        ui.postDelayed(ticker, 1500);
    }

    /**
     * v1.5.0 启动遮罩：不透明品牌页，盖在 WebView 之上。
     * 官方网页在底下加载 / 重载 / 长浮层 / 显示白色大播放按钮，用户一律看不见。
     * 只有真正检测到出画面才淡出揭开，实现"打开即全屏、直接进播放"。
     */
    private void buildBootCover() {
        bootCover = new FrameLayout(this);
        bootCover.setBackgroundColor(0xFF05070D);
        bootCover.setClickable(true);   // 吃掉启动期的点击，避免点到网页浮层

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.CENTER;

        TextView brand = new TextView(this);
        brand.setText("LinkTV");
        brand.setTextColor(0xFF6C8CFF);
        brand.setTextSize(52f);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.12f);
        brand.setGravity(Gravity.CENTER);
        box.addView(brand);

        TextView sub = new TextView(this);
        sub.setText("官方直播 · 正在连接 CCTV");
        sub.setTextColor(0xFF98A4BD);
        sub.setTextSize(20f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 18, 0, 0);
        box.addView(sub);

        coverTip = new TextView(this);
        coverTip.setText("正在准备播放…");
        coverTip.setTextColor(0xFFEAF0FF);
        coverTip.setTextSize(24f);
        coverTip.setGravity(Gravity.CENTER);
        coverTip.setPadding(0, 46, 0, 0);
        box.addView(coverTip);

        TextView hint = new TextView(this);
        hint.setText("首次启动需等待官方直播加载，请稍候");
        hint.setTextColor(0xFF5A6785);
        hint.setTextSize(16f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 20, 0, 0);
        box.addView(hint);

        TextView ver = new TextView(this);
        ver.setText("v" + verName);
        ver.setTextColor(0xFF414C66);
        ver.setTextSize(14f);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, 34, 0, 0);
        box.addView(ver);

        bootCover.addView(box, bp);
    }

    /** 出画面后揭开启动遮罩：淡出 400ms 后移除，只执行一次 */
    private void revealCover() {
        if (!coverShown) return;
        coverShown = false;
        bootCover.animate().alpha(0f).setDuration(420).withEndAction(new Runnable() {
            @Override public void run() { bootCover.setVisibility(View.GONE); }
        }).start();
    }

    private void setCoverTip(String s) {
        if (coverShown && coverTip != null) coverTip.setText(s);
    }

    /** 桌面 UA 只替换平台标识，Chrome 版本用系统真实值；取不到用保守下限，绝不虚报高版本 */
    private String buildDesktopUa(String raw) {
        String chrome = null;
        if (raw != null) {
            Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(raw);
            if (m.find()) chrome = m.group(1);
        }
        if (chrome == null || chrome.isEmpty()) chrome = "70.0.0.0";
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + chrome + " Safari/537.36";
    }

    private void loadHome() {
        String last = prefs.getString("last", "");
        String pid = findPid(last);
        backupSlug = findBackupSlug(last);
        resetForNewPage(HOME);
        web.loadUrl(HOME + (pid.isEmpty() ? "" : "?pid=" + pid));
    }

    private void resetForNewPage(String url) {
        bootOk = false;
        // v1.4.0：everPlaying 表示"本次 App 会话曾出过画面"，一旦为 true 不再回退。
        // 页面因换台/手动重载而重开时保留它，任何计时器都不会被重新点燃。
        injectCount = 0;
        // v1.5.0：整页重载会清空 JS 上下文，探测状态必须一起归零，
        // 否则 ticker 误以为脚本还在、不再重注入。pendingOk 保留：用户在
        // 重载窗口按过的确定键仍要在新页面补发。
        scriptPresent = false;
        picStreak = 0;
        pageSince = System.currentTimeMillis();
        hostText = hostOf(url);
        bootText = "未注入";
        picText = "等待中";
        stageText = "正在加载网页…";
        errText = "";
        // v1.5.0：不再自动弹出自检条（默认隐藏由 diagHidden 控制）。
        // 启动期状态改由原生启动遮罩承载，用户按 0 才查看自检详情。
        updateDiag();
    }

    /** 注入频道表 + 注入层；幂等由 JS 侧 ready 标记保证，失败可重入 */
    private void inject() {
        if (web == null || injectJs.isEmpty()) return;
        injectCount++;
        lastInjectAt = System.currentTimeMillis();
        String script = "window.__LINKTV_MODE__='tv';"
                + "window.__LINKTV_FIT__='cover';"
                + "window.__LINKTV_NATIVE_KEYS__=true;\n"
                + channelsJs + "\n" + injectJs;
        web.evaluateJavascript(script, null);
        bootText = "注入中";
        updateDiag();
    }

    /** 原生看门狗：不依赖注入层是否存活 */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (web == null) return;
            long el = System.currentTimeMillis() - pageSince;

            web.evaluateJavascript(PROBE, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    applyProbe(value);
                }
            });

            // 脚本完全没挂载（NO）才重注入；PRESENT（已挂载正在 boot）不要打断它。
            // 但 PRESENT 卡死超过 8 秒仍要兜底重试，避免 boot 中途抛错后再也起不来。
            boolean stuck = scriptPresent && !bootOk && el > 8000;
            if ((!scriptPresent || stuck) && el > 2500 && injectCount < 8
                    && System.currentTimeMillis() - lastInjectAt > 1800) {
                inject();
            }
            // v1.4.0：看门狗只观察、只提示，绝不自动 loadUrl。
            // 自动切源会触发 onPageStarted，与 everPlaying 重置互相点火 → 重载死循环。
            if (el > 30000 && !everPlaying && !"连接较慢".equals(stageText)) {
                stageText = "连接较慢";
                errText = "正在等待官方直播；如长时间无画面，按 [9] 重新加载";
                setCoverTip("连接较慢，仍在等待官方直播…");
                updateDiag();
            }
            ui.postDelayed(this, 2000);
        }
    };

    private void applyProbe(String value) {
        String s = value == null ? "" : value;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        String[] p = s.split("\\|", -1);
        if (p.length < 6) return;
        String head = p[0];
        // PRESENT：注入脚本已挂载、按键入口已就绪，但 DIAG 还没定义（boot 中）。
        // 此时绝不重注入（会打断 boot），但允许补发排队的确定键。
        scriptPresent = "PRESENT".equals(head)
                || (!"NO".equals(head) && !"ERR".equals(head));
        if ("PRESENT".equals(head)) {
            bootText = "初始化中…";
            flushPendingOk();
            updateDiag();
            return;
        }
        // 若注入层被误判成 demo 模式（早注入），视为未就绪，让 ticker 继续重注入救回
        if (p.length > 5 && "demo".equals(p[5])) {
            bootOk = false;
            bootText = "模式异常 · 重注入中";
            stageText = "正在重新初始化…";
            updateDiag();
            return;
        }
        if (!"NO".equals(head) && !"ERR".equals(head)) {
            bootOk = true;
            bootText = "已就绪 · 清理×" + p[1];
            capText = "Chrome " + chromeVer + " | MSE " + ("1".equals(p[3]) ? "支持" : "不支持")
                    + " | WASM " + ("1".equals(p[4]) ? "支持" : "不支持");
            if ("0".equals(p[3]) || "0".equals(p[4])) {
                capText += "（官方播放器可能不可用）";
            }
            if ("PIC".equals(head)) {
                everPlaying = true;
                picText = "已出画面";
                stageText = "播放中";
                // 连续两次探测到出画面才揭开启动遮罩，避免视频刚 attach 就露出网页浮层
                if (++picStreak >= 2) revealCover();
            } else {
                if (!everPlaying) { picText = "等待画面"; picStreak = 0; }
            }
            flushPendingOk();
        } else if (!bootOk) {
            bootText = "未就绪" + ("ERR".equals(head) ? "（脚本报错）" : "");
        }
        updateDiag();
    }

    /** v1.5.0：注入层一就绪，就把启动期用户按过的确定键补发一次，绝不丢键 */
    private void flushPendingOk() {
        if (!pendingOk || !scriptPresent || web == null) return;
        pendingOk = false;
        web.evaluateJavascript(FLUSH_OK, null);
    }

    /** v1.4.0：唯一的整页重载入口，只由用户主动按数字键 9 触发，绝不自动调用 */
    private void reloadCurrent() {
        String last = prefs.getString("last", "");
        String pid = findPid(last);
        stageText = "正在重新加载…";
        errText = "";
        // v1.5.0：重新盖上启动遮罩，重载期间的官网原貌/浮层一律不露出；
        // 自检条保持用户的显隐选择，不强制弹出。
        showCoverAgain();
        scriptPresent = false; bootOk = false; picStreak = 0; pendingOk = false;
        web.loadUrl(HOME + (pid.isEmpty() ? "" : "?pid=" + pid));
    }

    private void showCoverAgain() {
        if (bootCover == null) return;
        coverShown = true;
        bootCover.animate().cancel();
        bootCover.setAlpha(1f);
        bootCover.setVisibility(View.VISIBLE);
        bootCover.bringToFront();
        setCoverTip("正在重新加载频道…");
    }

    private void onReport(String stage, String detail) {
        if (stage == null) return;
        if (detail == null) detail = "";
        switch (stage) {
            case "ui":
                bootOk = true; bootText = "已就绪";
                if (detail.contains("host=")) hostText = detail.split("host=", 2)[1];
                stageText = "界面已挂载，正在连接直播…";
                break;
            case "loaded": stageText = "官方网页已加载"; break;
            case "connecting": stageText = "正在连接官方直播…"; break;
            case "playing":
                everPlaying = true; picText = String.valueOf(detail);
                stageText = "播放中";
                // 注入层主动确认出画面（videoOK 更严格），立即揭开启动遮罩
                revealCover();
                break;
            case "backup": stageText = "正在切换备用线路…"; break;
            case "needBackup": return;   // v1.4.0：注入层已不再上报，防御性忽略
            case "failed":
                stageText = "连接失败"; errText = String.valueOf(detail);
                setCoverTip("连接失败 · 按 [9] 重新加载");
                break;   // v1.5.0：不自动弹自检条
            case "error":
                // v1.4.0：注入层自身异常只记一行小字，不把主状态刷成"脚本异常"。
                errText = String.valueOf(detail);
                break;
            case "notready":
                // v1.5.0：注入层还没 boot 完时用户按了键 —— 由原生 pendingOk 排队补发，
                // 不再弹自检条，只把状态写到启动遮罩上。
                stageText = String.valueOf(detail);
                setCoverTip("正在准备选台…");
                break;
            default: stageText = stage; break;
        }
        updateDiag();
    }

    /** v1.5.0：轻量反馈，2 秒自动消失，绝不常驻在画面上 */
    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); }
                catch (Exception ignore) {}
            }
        });
    }

    private void updateDiag() {
        if (diag == null) return;
        diag.setVisibility(diagHidden ? View.GONE : View.VISIBLE);
        StringBuilder sb = new StringBuilder();
        sb.append("LinkTV v").append(verName).append(" · 电视版自检\n");
        sb.append("状态：").append(stageText).append("\n");
        sb.append("网页：").append(hostText)
          .append("   注入：").append(bootText)
          .append("   画面：").append(picText).append("\n");
        sb.append("内核：").append(capText).append("\n");
        if (!errText.isEmpty()) sb.append("提示：").append(errText).append("\n");
        sb.append(everPlaying
                ? "状态正常 · 按 [0] 隐藏/显示自检 · [9] 重新加载"
                : "[0] 隐藏自检 · [9] 重新加载当前频道");
        diag.setText(sb.toString());
        diag.bringToFront();
    }

    private String hostOf(String url) {
        if (url == null) return "-";
        Matcher m = Pattern.compile("https?://([^/]+)").matcher(url);
        return m.find() ? m.group(1) : url;
    }

    private String readAsset(String path) {
        try (InputStream is = getAssets().open(path); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String findPid(String name) {
        if (name == null || name.isEmpty()) return "";
        Matcher m = Pattern.compile("n:\\s*\"" + Pattern.quote(name) + "\"[^}]*?p:\\s*\"(\\d+)\"").matcher(channelsJs);
        return m.find() ? m.group(1) : "";
    }

    private String findBackupSlug(String name) {
        if (name == null || name.isEmpty()) return "cctv1";
        Matcher m = Pattern.compile("n:\\s*\"" + Pattern.quote(name) + "\"[^}]*?b:\\s*\"([a-z0-9]+)\"").matcher(channelsJs);
        return m.find() ? m.group(1) : "cctv1";
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    /** 前置拦截遥控器按键，避免被 WebView / 网页吞掉 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_0) {
            diagHidden = !diagHidden;
            diag.setAlpha(diagHidden ? 0f : 1f);
            diag.bringToFront();       // 确保在启动遮罩之上可见
            updateDiag();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_9) {
            reloadCurrent();   // 用户主动重新加载当前频道
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            String name = null;
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: name = "up"; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: name = "down"; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: name = "left"; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: name = "right"; break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER: name = "ok"; break;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE: name = "back"; break;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_INFO:
                case KeyEvent.KEYCODE_TV_CONTENTS_MENU: name = "menu"; break;
                default: break;
            }
            if (name != null) {
                final String keyName = name;
                // v1.5.0：注入脚本开头就挂载了 __LINKTV_KEY__（带排队），所以只要脚本
                // 已注入，按键一定收得到。只有"连脚本都还没注入"的极早期才 NOTREADY，
                // 此时把确定键排队，注入层一就绪就补发，并用轻提示反馈，绝不静默吞掉。
                String js = "(function(){try{"
                        + "if(typeof window.__LINKTV_KEY__==='function'){window.__LINKTV_KEY__('" + keyName + "');return 'OK'}"
                        + "return 'NOTREADY'}catch(e){return 'ERR'}})()";
                web.evaluateJavascript("window.__LINKTV_NATIVE_KEYS__=true;" + js,
                        new ValueCallback<String>() {
                            @Override public void onReceiveValue(String value) {
                                if (value != null && value.contains("NOTREADY")) {
                                    if ("ok".equals(keyName)) {
                                        pendingOk = true;   // 注入层就绪后自动补发开面板
                                        setCoverTip("正在准备选台…");
                                    }
                                    stageText = "界面加载中，请稍候…";
                                    toast("界面加载中，稍候自动打开选台");
                                }
                            }
                        });
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** 存储桥 + 自检上报 */
    private class Bridge {
        @JavascriptInterface
        public String get(String k) { return prefs.getString(k, null); }

        @JavascriptInterface
        public void set(String k, String v) { prefs.edit().putString(k, v).apply(); }

        @JavascriptInterface
        public void report(final String stage, final String detail) {
            runOnUiThread(new Runnable() {
                @Override public void run() { onReport(stage, detail); }
            });
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    long now = System.currentTimeMillis();
                    if (now - lastBackAt < 2200) finish();
                    else {
                        lastBackAt = now;
                        stageText = "再按一次返回键退出";
                        toast("再按一次返回键退出");   // v1.5.0：轻提示，不弹自检条
                    }
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
