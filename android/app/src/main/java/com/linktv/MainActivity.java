package com.linktv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkTV v1.2.0 —— 安卓 TV 外壳
 *
 * 播放内核：WebView 全屏加载「央视频官方直播页」，注入 inject.js 清浮层只留官方播放器，
 * 播放/鉴权/拉流全部由官方网页负责；本地不含任何 m3u8 直链。
 *
 * 关键点：
 *  - 桌面 UA 伪装：从系统真实 UserAgent 提取 Chrome 版本拼成 Windows 桌面 UA，
 *    否则央视频会把移动 WebView 甩到「下载 App」页面。
 *  - dispatchKeyEvent 前置拦截全部遥控器按键，转发给注入层（避免被 WebView 吞掉）。
 *  - onPageStarted 就注入（应对 SPA 异步渲染），onPageFinished 再补一次。
 *  - 内核检测：Chrome 主版本过低或不支持 MediaSource 时明确提示，不让人对着黑屏。
 *  - 20 秒无画面由注入层自动切央视网备用线路，并显示可见状态文字。
 */
public class MainActivity extends Activity {

    private static final String HOME = "https://www.yangshipin.cn/tv/home";
    private static final int MIN_CHROME_MAJOR = 70;

    private WebView web;
    private SharedPreferences prefs;
    private String channelsJs = "";
    private String injectJs = "";
    private String rawUa = "";
    private long lastBackAt = 0;

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
        ws.setMediaPlaybackRequiresUserGesture(false);   // 启动即自动播放
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setLoadWithOverviewMode(false);
        ws.setUseWideViewPort(false);
        rawUa = ws.getUserAgentString();
        ws.setUserAgentString(buildDesktopUa(rawUa));

        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                inject();   // 早注入，SPA 渲染未完成也没关系，注入层会轮询找 video
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                inject();
            }
            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                showNetHint(desc);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "LinkTVStore");

        setContentView(web);

        if (!checkEnvironment()) return;

        String last = prefs.getString("last", "");
        String pid = findPid(last);
        String url = HOME + (pid.isEmpty() ? "" : "?pid=" + pid);
        web.loadUrl(url);
    }

    /** 桌面 UA：保留设备真实的 Chrome 版本，只替换平台标识，不写死版本号 */
    private String buildDesktopUa(String raw) {
        String chrome = "120.0.0.0";
        if (raw != null) {
            Matcher m = Pattern.compile("Chrome/([0-9.]+)").matcher(raw);
            if (m.find()) chrome = m.group(1);
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + chrome + " Safari/537.36";
    }

    /** 内核版本检测：用系统真实 UA 判断；过旧给出可见提示，可选继续 */
    private boolean checkEnvironment() {
        int major = 0;
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(rawUa == null ? "" : rawUa);
        if (m.find()) { try { major = Integer.parseInt(m.group(1)); } catch (Exception ignored) {} }
        if (major > 0 && major < MIN_CHROME_MAJOR) {
            new AlertDialog.Builder(this)
                    .setTitle("网页内核版本过低")
                    .setMessage("当前系统 WebView（Chrome " + major + "）过旧，央视频直播可能无法播放。"
                            + "建议到应用商店升级「Android System WebView」或 Chrome 后重试。")
                    .setPositiveButton("仍然继续", (d, w) -> loadHome())
                    .setNegativeButton("退出", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
            return false;
        }
        return true;
    }

    private void loadHome() {
        String pid = findPid(prefs.getString("last", ""));
        web.loadUrl(HOME + (pid.isEmpty() ? "" : "?pid=" + pid));
    }

    /** 频道表 + 注入层合并为单次注入，保证顺序；并关闭网页端按键兜底（真机全部由原生转发） */
    private void inject() {
        if (web == null || injectJs.isEmpty()) return;
        String script = "window.__LINKTV_NATIVE_KEYS__=true;\n"
                + channelsJs + "\n" + injectJs;
        web.evaluateJavascript(script, null);
    }

    private void showNetHint(String desc) {
        if (web == null) return;
        web.evaluateJavascript("(function(){var e=document.getElementById('ltv-err');"
                + "if(e){e.textContent='网络异常：" + esc(desc) + "，请检查电视网络';e.style.display='block';}})()", null);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
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

    /** 从频道表里按名称找回 pid（启动回到上次频道） */
    private String findPid(String name) {
        if (name == null || name.isEmpty()) return "";
        Matcher m = Pattern.compile("n:\\s*\"" + Pattern.quote(name) + "\"[^}]*?p:\\s*\"(\\d+)\"").matcher(channelsJs);
        return m.find() ? m.group(1) : "";
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
                // 标记由原生转发，注入层的 keydown 兜底不再重复处理
                web.evaluateJavascript("window.__LINKTV_NATIVE_KEYS__=true;"
                        + "(function(){try{window.__LINKTV_KEY__&&window.__LINKTV_KEY__('" + name + "')}catch(e){}})()", null);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** 存储桥：跨页面/跨域保存收藏与上次频道；退出由 Java 处理双击返回 */
    private class Bridge {
        @JavascriptInterface
        public String get(String k) { return prefs.getString(k, null); }

        @JavascriptInterface
        public void set(String k, String v) { prefs.edit().putString(k, v).apply(); }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(() -> {
                long now = System.currentTimeMillis();
                if (now - lastBackAt < 2200) finish();
                else {
                    lastBackAt = now;
                    if (web != null) web.evaluateJavascript(
                            "(function(){var t=document.getElementById('ltv-toast');"
                                    + "if(t){t.textContent='再按一次返回键退出';t.classList.add('on');"
                                    + "setTimeout(function(){t.classList.remove('on')},2000);}})()", null);
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
