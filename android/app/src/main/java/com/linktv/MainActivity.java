package com.linktv;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * LinkTV —— 安卓外壳
 * 用系统 WebView 加载 assets 里的本地网页（HTML/JS 直播应用）。
 * 遥控器的方向键 / 确定键会被 WebView 派发为 keydown 事件，
 * 由网页 js/app.js 处理焦点与播放，无需额外原生代码。
 */
public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 沉浸全屏（隐藏状态栏与导航栏，电视更利落）
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);                 // 启用 JS
        ws.setDomStorageEnabled(true);                // 收藏等 localStorage
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false); // 允许自动播放直播
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 所有链接/导航都在 WebView 内处理
        webView.setWebViewClient(new WebViewClient());

        setContentView(webView);

        // 加载本地网页
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
