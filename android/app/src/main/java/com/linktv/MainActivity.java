package com.linktv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * LinkTV v1.1.0 —— 安卓 TV 外壳
 * 用系统 WebView 全屏加载 assets 里的本地网页（HTML/JS 直播应用）。
 *  - 沉浸全屏、常亮、允许自动播放与 http/https 混合内容（直播源多为 http）
 *  - 遥控器方向键 / 确定键由 WebView 派发为 keydown，网页 app.js 处理
 *  - Back / Menu 两个键 WebView 默认不派发，这里通过 evaluateJavascript 转发给网页
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);                 // 收藏 / 上次频道
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true); // 本地页面加载云端清单 / 官方节目单
        ws.setMediaPlaybackRequiresUserGesture(false); // 启动即自动播放直播
        // 直播源大量为 http，必须允许混合内容，否则 WebView 会静默拦截导致黑屏
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    /** 菜单键：转发给网页用于「加入/移出收藏」 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (webView != null) {
                webView.evaluateJavascript("(function(){try{window.__linktvMenu&&window.__linktvMenu()}catch(e){}} )()", null);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 返回键：面板打开时先收起面板，否则退出应用 */
    @Override
    public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript(
                "(function(){try{return !!(window.__linktvBack&&window.__linktvBack())}catch(e){return false}})()",
                value -> {
                    if (!"true".equals(value)) finish();
                });
    }

    @Override
    protected void onDestroy() {
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
