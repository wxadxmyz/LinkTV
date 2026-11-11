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
 * LinkTV v1.4.0 —— 电视专用版外壳
 *
 * v1.3.0 在真机上暴露的问题（本版全部拆除）：
 *  1) 注入层 20s 切源 + 原生 25s 切源 + onPageStarted 重置 everPlaying，
 *     三层"保险"互相点火：切源触发 onPageStarted → 重置 everPlaying → 计时归零
 *     → 到点又切源，形成整页反复重载死循环（1 分钟内多次回到"正在连接"）。
 *  2) 确定键在 stage==='failed' 时被写成整页 reload，配合自检条"按确定键可重试"
 *     的提示，用户越按越重载，永远调不出频道面板。
 *  3) 全局 error 钩子把央视频网页自身的无害异常（a0b is not defined）
 *     误报成"脚本异常"并强制钉住自检条，而画面其实正常在播。
 *  4) 按键转发用 `window.__LINKTV_KEY__ && ...` 短路，注入未就绪时静默吞掉。
 *
 * v1.4.0 对策（核心：只留一个观察哨，绝不自动导航）：
 *  - 看门狗只观察、只提示；删除所有自动 loadUrl / 切源。
 *  - everPlaying 一旦为 true 永不被页面重载重置（本次会话曾出过画面）。
 *  - 唯一重载入口是用户主动按数字键 9（reloadCurrent）。
 *  - 确定键永远开选台面板；注入未就绪时按键转发给可见反馈，不再静默吞。
 *  - 注入层自身异常只记一行小字，不刷主状态、不强制弹自检条。
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
    private boolean bootOk = false, everPlaying = false, diagHidden = false;
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
        resetForNewPage(HOME);
        web.loadUrl(HOME + (pid.isEmpty() ? "" : "?pid=" + pid));
    }

    private void resetForNewPage(String url) {
        bootOk = false;
        // v1.4.0：everPlaying 表示"本次 App 会话曾出过画面"，一旦为 true 不再回退。
        // 页面因换台/手动重载而重开时保留它，任何计时器都不会被重新点燃。
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
            // v1.4.0：看门狗只观察、只提示，绝不自动 loadUrl。
            // 自动切源会触发 onPageStarted，与 everPlaying 重置互相点火 → 重载死循环。
            if (el > 30000 && !everPlaying && !"连接较慢".equals(stageText)) {
                stageText = "连接较慢";
                errText = "正在等待官方直播；如长时间无画面，按 [9] 重新加载";
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

    /** v1.4.0：唯一的整页重载入口，只由用户主动按数字键 9 触发，绝不自动调用 */
    private void reloadCurrent() {
        String last = prefs.getString("last", "");
        String pid = findPid(last);
        stageText = "正在重新加载…";
        errText = "";
        showDiag(true);
        web.loadUrl(HOME + (pid.isEmpty() ? "" : "?pid=" + pid));
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
            case "needBackup": return;   // v1.4.0：注入层已不再上报，防御性忽略
            case "failed":
                stageText = "连接失败"; errText = String.valueOf(detail); showDiag(true);
                break;
            case "error":
                // v1.4.0：注入层自身异常只记一行小字，不把主状态刷成"脚本异常"、
                // 不强制钉住自检条（正常播放时应正常淡出）。
                errText = String.valueOf(detail);
                break;
            case "notready":
                // 注入层还没 boot 完时用户按了键：给可见反馈，不再静默无反应
                stageText = String.valueOf(detail);
                showDiag(true);
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
        sb.append("LinkTV v1.4.0 · 电视版自检\n");
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
                // v1.4.0：让 JS 返回是否真的执行了按键。注入层未就绪时不再
                // `&&` 短路静默吞掉，而是在自检条给出"界面加载中"的可见反馈。
                String js = "(function(){try{"
                        + "if(typeof window.__LINKTV_KEY__==='function'){window.__LINKTV_KEY__('" + name + "');return 'OK'}"
                        + "return 'NOTREADY'}catch(e){return 'ERR'}})()";
                web.evaluateJavascript("window.__LINKTV_NATIVE_KEYS__=true;" + js,
                        new ValueCallback<String>() {
                            @Override public void onReceiveValue(String value) {
                                if (value != null && value.contains("NOTREADY")) {
                                    stageText = "界面加载中，请稍候…";
                                    showDiag(true);
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
