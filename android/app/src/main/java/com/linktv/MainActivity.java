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
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkTV v1.3.0 —— 电视专用版外壳
 *
 * v1.2.0 在电视失效的根因（已修复）：
 *  1) onPageStarted 就注入，此时 location.hostname 为空，注入层把自己判成 demo 模式，
 *     且 __LINKTV_BOOTED__ 开头即锁死 → clean()/看门狗全不执行，露出官网原貌+黑屏。
 *  2) screenOrientation="sensorLandscape"：电视没有重力传感器，系统按竖屏兼容模式
 *     给 Activity 分配居中窗口 → 白色竖块 + 四周大黑边。
 *  3) 桌面 UA 虚报 Chrome 120，电视真实内核远低于此，官方播放器按新能力初始化后崩溃。
 *  4) 注入失败无任何提示；20 秒切备用线路只在注入层里，注入没成功就永远不会触发。
 *
 * v1.3.0 对策：
 *  - 只在 onPageFinished 注入，且明确传 __LINKTV_MODE__="tv"，不再靠 hostname 猜。
 *  - 原生自检覆盖层：即使网页全黑/注入全挂，屏幕上也有状态文字。
 *  - 原生轮询确认注入（读 __LINKTV_DIAG__），未 ready 自动重注入最多 8 次。
 *  - 原生看门狗：25 秒无画面，不依赖注入层，直接切央视网备用线路。
 *  - 真实能力探测（MSE / WASM / 真实 Chrome 版本），不再虚报 UA。
 *  - 强制 landscape + resizeableActivity=false，画面铺满。
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://www.yangshipin.cn/tv/home";
    private static final String BACKUP = "https://tv.cctv.com/live/";

    /** 单次读取注入层诊断，返回短字符串，避免 JSON 转义问题 */
    private static final String PROBE =
            "(function(){try{" +
            "var d=window.__LINKTV_DIAG__?JSON.parse(window.__LINKTV_DIAG__()):null;" +
            "if(!d)return 'NO|0|-|-|-|-';" +
            "var v=d.video;return (v&&v.w>0&&!v.paused?'PIC':'NOPIC')+'|'+d.clean+'|'+d.stage+" +
            "'|'+(d.mse?'1':'0')+'|'+(d.wasm?'1':'0')+'|'+d.mode;" +
            "}catch(e){return 'ERR|0|-|-|-|-'}})()";

    private WebView web;
    private FrameLayout root;
    private TextView diag;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String channelsJs = "", injectJs = "", rawUa = "", chromeVer = "未知";
    private long lastBackAt = 0;

    // 自检状态
    private boolean bootOk = false, everPlaying = false, didBackup = false, diagHidden = false;
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
                // 关键：这里绝不注入。新页面刚开始时 hostname 尚未提交，注入会被误判。
                if (url != null && url.contains("yangshipin.cn")) didBackup = false;
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
                showDiag(true);
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

        setContentView(root);
        loadHome();
        ui.postDelayed(ticker, 1500);
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
        didBackup = false;
        resetForNewPage(HOME);
        web.loadUrl(HOME + (pid.isEmpty() ? "" : "?pid=" + pid));
    }

    private void resetForNewPage(String url) {
        bootOk = false;
        everPlaying = false;
        injectCount = 0;
        pageSince = System.currentTimeMillis();
        hostText = hostOf(url);
        bootText = "未注入";
        picText = "等待中";
        stageText = "正在加载网页…";
        errText = "";
        showDiag(true);
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

            if (!bootOk && el > 2500 && injectCount < 8
                    && System.currentTimeMillis() - lastInjectAt > 1800) {
                inject();   // 注入没成功 → 重试（SPA 渲染晚、脚本被中断等）
            }
            if (!didBackup && el > 25000 && !everPlaying) {
                doBackupNative();
            } else if (didBackup && el > 25000 && !everPlaying) {
                stageText = "连接失败";
                errText = "央视频 / 央视网均无法出画面，按确定键重试，或检查电视网络";
                showDiag(true);
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
        // 若注入层被误判成 demo 模式（早注入），视为未就绪，让 ticker 继续重注入救回
        if (p.length > 5 && "demo".equals(p[5])) {
            bootOk = false;
            bootText = "模式异常 · 重注入中";
            stageText = "正在重新初始化…";
            updateDiag();
            return;
        }
        if (!"NO".equals(p[0]) && !"ERR".equals(p[0])) {
            bootOk = true;
            bootText = "已就绪 · 清理×" + p[1];
            capText = "Chrome " + chromeVer + " | MSE " + ("1".equals(p[3]) ? "支持" : "不支持")
                    + " | WASM " + ("1".equals(p[4]) ? "支持" : "不支持");
            if ("0".equals(p[3]) || "0".equals(p[4])) {
                capText += "（官方播放器可能不可用）";
            }
            if ("PIC".equals(p[0])) {
                everPlaying = true;
                picText = "已出画面";
                stageText = "播放中";
                scheduleDiagFade();
            } else if (!everPlaying) {
                picText = "等待画面";
            }
        } else if (!bootOk) {
            bootText = "未就绪" + ("ERR".equals(p[0]) ? "（脚本报错）" : "");
        }
        updateDiag();
    }

    /** 原生切备用线路：央视网直播页，不依赖注入层 */
    private void doBackupNative() {
        if (didBackup) return;
        didBackup = true;
        stageText = "已切换央视网备用线路";
        errText = "央视频 25 秒未出画面，已自动切换；若仍黑屏，多为电视网页内核过旧";
        bootOk = false;
        everPlaying = false;
        injectCount = 0;
        pageSince = System.currentTimeMillis();
        hostText = hostOf(BACKUP + backupSlug + "/");
        bootText = "未注入";
        picText = "等待中";
        showDiag(true);
        web.loadUrl(BACKUP + backupSlug + "/");
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
                stageText = "播放中"; scheduleDiagFade();
                break;
            case "backup": stageText = "正在切换备用线路…"; break;
            case "needBackup": doBackupNative(); return;
            case "failed":
                stageText = "连接失败"; errText = String.valueOf(detail); showDiag(true);
                break;
            case "error":
                stageText = "脚本异常"; errText = String.valueOf(detail); showDiag(true);
                break;
            default: stageText = stage; break;
        }
        updateDiag();
    }

    private void scheduleDiagFade() {
        ui.removeCallbacks(fadeDiag);
        ui.postDelayed(fadeDiag, 6000);
    }
    private final Runnable fadeDiag = new Runnable() {
        @Override public void run() { if (everPlaying && !diagHidden) diag.setAlpha(0.30f); }
    };

    private void showDiag(boolean forceVisible) {
        if (forceVisible) { diagHidden = false; diag.setAlpha(1f); }
        updateDiag();
    }

    private void updateDiag() {
        if (diag == null) return;
        diag.setVisibility(diagHidden ? View.GONE : View.VISIBLE);
        StringBuilder sb = new StringBuilder();
        sb.append("LinkTV v1.3.0 · 电视版自检\n");
        sb.append("状态：").append(stageText).append("\n");
        sb.append("网页：").append(hostText)
          .append("   注入：").append(bootText)
          .append("   画面：").append(picText).append("\n");
        sb.append("内核：").append(capText).append("\n");
        if (!errText.isEmpty()) sb.append("提示：").append(errText).append("\n");
        sb.append(everPlaying ? "状态正常 · 按 [0] 隐藏/显示自检" : "[0] 隐藏自检 · 按确定键可重试");
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
            updateDiag();
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
                web.evaluateJavascript("window.__LINKTV_NATIVE_KEYS__=true;"
                        + "(function(){try{window.__LINKTV_KEY__&&window.__LINKTV_KEY__('" + name + "')}catch(e){}})()", null);
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
                        showDiag(true);
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
