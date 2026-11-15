package com.linktv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Gravity;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LinkTV v2.0.0 —— 原生播放器电视直播（架构重写）
 *
 * 与 v1.x 的根本区别：不再用 WebView 打开央视频官网网页再注入 JS 削成视频。
 * v1.x 的一切顽疾（打开要等 20 秒官网加载/重载、确定键在 NOTREADY 窗口丢失、
 * 官网浮层抢焦点露原貌、遮罩揭开时机错乱）都源于「依赖别人的网页」。
 *
 * v2.0.0：
 *  - 原生 Media3 ExoPlayer 直接播放 m3u8 / mp4 直播流，启动 1~3 秒出画面
 *  - 频道表内置 assets/tv.m3u（620 条线路 / 12 分类 / 404 频道，离线可用）；
 *    后台还会尝试从 gitee 拉取最新 m3u 存本地缓存，下次启动优先用缓存，源失效可自愈
 *  - 分类栏 + 频道列表 + 收藏提示全部原生绘制，按键由 dispatchKeyEvent 前置拦截，
 *    完全不依赖网页焦点系统 —— 确定键任何时候都能开面板
 *  - 播放失败自动切下一条线路：只换 MediaItem，绝不重建页面/Activity；
 *    索引单调递增 + 硬次数上限，天然不可能形成 v1.3.0 那种重载死循环
 *  - 画面用 TextureView（普通 View 层级，规避 SurfaceView 在部分电视盒子上打洞黑屏），
 *    自己按视频宽高比算 FIT（contain）：完整显示不裁切，
 *    超宽手机两侧留等量黑边；配合刘海 shortEdges 消除单侧黑边
 *  - 明文 http 线路（源里 389/620 条）由 usesCleartextTraffic 放行
 */
public class MainActivity extends Activity {

    private static final String TAG = "LinkTV";
    private static final String HOME_ASSET = "tv.m3u";   // 内置全量频道表（m3u 原文）
    private static final String CACHE_FILE = "tv.m3u";  // 在线更新缓存，优先于内置
    private static final String UPDATE_URL =
            "https://gitee.com/xmyzjxn/muhai-live/raw/master/tv.m3u";
    private static final int MAX_ATTEMPTS = 20;      // 单频道换线路硬上限，绝不无限重试
    private static final int CAT_ROWS = 8;           // 分类栏虚拟窗口行数
    private static final int CHAN_ROWS = 9;          // 频道栏虚拟窗口行数
    private static final String FAV_NAME = "我的收藏";
    private static final String SEP = "\u0001";
    private static final Pattern GROUP_PATTERN =
            Pattern.compile("group-title=\"([^\"]*)\"");
    private static final String[] GROUP_ORDER = {
            "央视频道", "卫视频道", "省级频道", "新闻频道", "影视频道", "少儿频道",
            "海外频道", "DJ舞曲", "历届春晚", "北京地区", "山西地区", "未分组",
    };

    /** 频道：一个名字 + 若干条线路（按可用性排序） */
    static final class Chan {
        final String name;
        final ArrayList<String> urls = new ArrayList<>();
        Chan(String n) { name = n; }
    }
    /** 分类 */
    static final class Grp {
        final String name;
        final ArrayList<Chan> chans = new ArrayList<>();
        Grp(String n) { name = n; }
    }

    private final ArrayList<Grp> groups = new ArrayList<>();
    private Grp favGroup;
    private Set<String> favKeys = new HashSet<>();

    private ExoPlayer player;
    private TextureView surface;
    private FrameLayout root, cover, panel;
    private LinearLayout catCol, chanCol;
    private TextView coverTitle, coverTip, infoBar, osd, toast, errBar, catTitle, chanTitle;
    private final TextView[] catRows = new TextView[CAT_ROWS];
    private final TextView[] chanRows = new TextView[CHAN_ROWS];

    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String verName = "2.0.0", sourceInfo = "";
    private int videoW = 0, videoH = 0;

    // 当前播放位置
    private int curGrp = 1, curChan = 0;
    private int curLine = 0, curVariant = 0, attempts = 0;
    private boolean coverShown = true, infoHidden = true;
    private long lastSwitchAt = 0;

    // 面板/焦点状态
    private boolean panelOpen = false;
    private int zone = 1;              // 0=分类栏 1=频道栏
    private int panelGrp = 1, panelChan = 0;
    private long lastBackAt = 0;

    private final Runnable hideToast = new Runnable() {
        @Override public void run() { if (toast != null) toast.setAlpha(0f); }
    };
    private final Runnable hideOsd = new Runnable() {
        @Override public void run() { if (osd != null) osd.setAlpha(0f); }
    };

    // ============================ 生命周期 ============================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fullscreen();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi.versionName != null && !pi.versionName.isEmpty()) verName = pi.versionName;
        } catch (Exception ignore) {}

        prefs = getSharedPreferences("linktv", Context.MODE_PRIVATE);
        favKeys = new HashSet<>(prefs.getStringSet("favs", new HashSet<String>()));

        buildUi();
        loadChannels();
        restoreLast();
        initPlayer();
        startChannel();
        ui.postDelayed(new Runnable() {
            @Override public void run() { tryUpdateInBackground(); }
        }, 2500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
    }

    private void fullscreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ============================ UI 构建（纯代码，无 XML 布局） ============================

    private TextView mkText(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setIncludeFontPadding(false);
        return t;
    }

    private FrameLayout.LayoutParams flp(int w, int h, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h);
        p.gravity = gravity;
        return p;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        // 1) 视频层：TextureView 是普通 View，参与正常层级合成，
        //    不像 SurfaceView 打洞(punch-through)那样在部分电视盒子上黑屏。
        //    尺寸由 applyVideoLayout() 按视频宽高比算 FIT（完整不裁切）。
        surface = new TextureView(this);
        root.addView(surface, flp(1, 1, Gravity.CENTER));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                applyVideoLayout();
            }
        });

        // 2) 换台 OSD（顶部偏左，2.5 秒淡出）
        osd = mkText(30f, 0xFFFFFFFF, true);
        osd.setBackgroundColor(0xAA0A1020);
        osd.setPadding(dp(26), dp(12), dp(26), dp(12));
        osd.setAlpha(0f);
        FrameLayout.LayoutParams op = flp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        op.topMargin = dp(30); op.leftMargin = dp(36);
        root.addView(osd, op);

        // 3) 右上角临时提示（收藏/退出等）
        toast = mkText(26f, 0xFFFFCF4D, true);
        toast.setBackgroundColor(0xCC080C18);
        toast.setPadding(dp(24), dp(12), dp(24), dp(12));
        toast.setAlpha(0f);
        FrameLayout.LayoutParams tp = flp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        tp.topMargin = dp(30); tp.rightMargin = dp(36);
        root.addView(toast, tp);

        // 4) 底部错误/帮助条
        errBar = mkText(24f, 0xFFC9D6FF, false);
        errBar.setBackgroundColor(0xAA0A1020);
        errBar.setPadding(dp(24), dp(10), dp(24), dp(10));
        errBar.setAlpha(0f);
        FrameLayout.LayoutParams ep = flp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ep.bottomMargin = dp(26);
        root.addView(errBar, ep);

        // 5) 信息条（按 0 显隐，默认隐藏）
        infoBar = mkText(19f, 0xFFEAF0FF, false);
        infoBar.setBackgroundColor(0xDD05070D);
        infoBar.setTypeface(Typeface.MONOSPACE);
        infoBar.setPadding(dp(22), dp(14), dp(22), dp(14));
        infoBar.setAlpha(0f);
        FrameLayout.LayoutParams ip = flp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        ip.leftMargin = dp(26); ip.bottomMargin = dp(26);
        root.addView(infoBar, ip);

        // 6) 选台面板（默认隐藏）
        buildPanel();

        // 7) 启动遮罩（品牌 + 版本 + 连接提示），首帧后淡出
        cover = new FrameLayout(this);
        cover.setBackgroundColor(0xFF05070D);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        coverTitle = mkText(52f, 0xFFFFFFFF, true);
        coverTitle.setText("LinkTV");
        coverTitle.setGravity(Gravity.CENTER);
        TextView ver = mkText(22f, 0xFF6C8CFF, false);
        ver.setText("v" + verName + " · 原生播放器版");
        ver.setGravity(Gravity.CENTER);
        coverTip = mkText(24f, 0xFFB8C6E6, false);
        coverTip.setText("正在连接直播…");
        coverTip.setGravity(Gravity.CENTER);
        coverTip.setPadding(0, dp(24), 0, 0);
        box.addView(coverTitle);
        box.addView(ver);
        box.addView(coverTip);
        cover.addView(box, flp(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(cover, flp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackgroundColor(0xEE0A1020);
        panel.setVisibility(View.GONE);

        // 左：分类栏
        catCol = new LinearLayout(this);
        catCol.setOrientation(LinearLayout.VERTICAL);
        catCol.setBackgroundColor(0xFF0C1428);
        catCol.setPadding(dp(18), dp(18), dp(18), dp(18));
        catTitle = mkText(22f, 0xFF6C8CFF, true);
        catTitle.setText("频道分类");
        catTitle.setPadding(dp(10), 0, 0, dp(10));
        catCol.addView(catTitle);
        for (int i = 0; i < CAT_ROWS; i++) {
            TextView t = mkText(25f, 0xFFD8E2FA, false);
            t.setPadding(dp(14), dp(11), dp(14), dp(11));
            catRows[i] = t;
            catCol.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        panel.addView(catCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.30f));

        // 右：频道栏
        chanCol = new LinearLayout(this);
        chanCol.setOrientation(LinearLayout.VERTICAL);
        chanCol.setPadding(dp(24), dp(18), dp(24), dp(18));
        chanTitle = mkText(22f, 0xFF6C8CFF, true);
        chanTitle.setText("频道列表");
        chanTitle.setPadding(dp(10), 0, 0, dp(10));
        chanCol.addView(chanTitle);
        for (int i = 0; i < CHAN_ROWS; i++) {
            TextView t = mkText(26f, 0xFFEAF0FF, false);
            t.setPadding(dp(14), dp(10), dp(14), dp(10));
            chanRows[i] = t;
            chanCol.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView help = mkText(19f, 0xFF7E8CB0, false);
        help.setText("↑↓ 选台 / ←→ 分类 / 确定 播放 / 菜单 收藏 / 返回 关闭 / 0 信息 / 9 重载");
        help.setPadding(dp(10), dp(14), 0, 0);
        chanCol.addView(help);
        panel.addView(chanCol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0.70f));

        root.addView(panel, flp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /** FIT(contain)：完整显示不裁切；16:9 源在超宽屏两侧留等量黑边 */
    private void applyVideoLayout() {
        if (root == null || surface == null) return;
        int cw = root.getWidth(), ch = root.getHeight();
        if (cw <= 0 || ch <= 0) return;
        int w = cw, h = ch;
        if (videoW > 0 && videoH > 0) {
            float ar = (float) videoW / (float) videoH;
            if (ar <= 0f) ar = 16f / 9f;
            w = cw;
            h = (int) (cw / ar);
            if (h > ch) { h = ch; w = (int) (ch * ar); }
        }
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (p == null) return;
        if (p.width != w || p.height != h) {
            p.width = w; p.height = h;
            p.gravity = Gravity.CENTER;
            surface.setLayoutParams(p);
        }
    }

    // ============================ 频道表 ============================

    /** 读取文本：优先在线更新缓存，否则内置 assets */
    private String readChannelText() {
        File f = new File(getFilesDir(), CACHE_FILE);
        if (f.exists() && f.length() > 20000) {
            try {
                String s = readStream(new FileInputStream(f));
                if (countExtinf(s) >= 100) {
                    sourceInfo = "在线缓存 " + countExtinf(s) + " 条";
                    return s;
                }
            } catch (Exception ignore) {}
        }
        try {
            String s = readStream(getAssets().open(HOME_ASSET));
            sourceInfo = "内置 " + countExtinf(s) + " 条";
            return s;
        } catch (Exception e) {
            Log.e(TAG, "内置频道表读取失败", e);
            sourceInfo = "内置频道表读取失败";
            return "";
        }
    }

    private String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 去掉 TVBox 私有标记 $LR•IPV4『线路N』 及 $ 之后的所有内容 */
    private String cleanTag(String s) {
        int i = s.indexOf('$');
        if (i >= 0) s = s.substring(0, i);
        return s.trim();
    }

    /** 线路排序：m3u8 优先 → https 次之 → rtmp 垫底（播放器不支持，自动跳过） */
    private int lineRank(String u) {
        String low = u.toLowerCase();
        if (low.startsWith("rtmp://")) return 20;
        int r = low.contains(".m3u8") ? 0 : 10;
        if (low.startsWith("https://")) r += 0; else r += 1;
        return r;
    }

    private void loadChannels() {
        String text = readChannelText();
        // 分类名 → (频道名 → Chan)，LinkedHashMap 保持源里的出现顺序
        LinkedHashMap<String, LinkedHashMap<String, Chan>> map = new LinkedHashMap<>();
        String curGroup = "未分组", curName = "";
        int lineCount = 0;
        for (String raw : text.split("\r?\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("#EXTINF")) {
                Matcher m = GROUP_PATTERN.matcher(s);
                curGroup = (m.find() && !m.group(1).trim().isEmpty())
                        ? m.group(1).trim() : "未分组";
                int idx = s.lastIndexOf(',');
                curName = idx >= 0 ? cleanTag(s.substring(idx + 1)) : "";
                if (curName.isEmpty()) curName = "未命名频道";
            } else if (s.startsWith("#")) {
                continue;
            } else {
                String url = cleanTag(s);
                if (!url.contains("://") || curName.isEmpty()) continue;
                LinkedHashMap<String, Chan> gm = map.get(curGroup);
                if (gm == null) { gm = new LinkedHashMap<>(); map.put(curGroup, gm); }
                Chan ch = gm.get(curName);
                if (ch == null) { ch = new Chan(curName); gm.put(curName, ch); }
                ch.urls.add(url);
                lineCount++;
            }
        }

        // 分类排序：预设顺序优先，其余按频道数降序
        ArrayList<String> names = new ArrayList<>(map.keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int ia = indexOfGroup(a), ib = indexOfGroup(b);
                if (ia >= 0 && ib >= 0) return ia - ib;
                if (ia >= 0) return -1;
                if (ib >= 0) return 1;
                return map.get(b).size() - map.get(a).size();
            }
        });
        for (String gn : names) {
            LinkedHashMap<String, Chan> gm = map.get(gn);
            Grp grp = new Grp(gn);
            for (Chan ch : gm.values()) {
                Collections.sort(ch.urls, new Comparator<String>() {
                    @Override public int compare(String a, String b) {
                        return lineRank(a) - lineRank(b);
                    }
                });
                if (!ch.urls.isEmpty()) grp.chans.add(ch);
            }
            if (!grp.chans.isEmpty()) groups.add(grp);
        }

        if (groups.isEmpty()) {
            Grp g = new Grp("央视频道");
            Chan c = new Chan("频道表缺失");
            c.urls.add("");
            g.chans.add(c);
            groups.add(g);
            sourceInfo = "频道表解析失败";
        }
        Log.i(TAG, "频道表: " + groups.size() + " 分类 / " + totalChannels()
                + " 频道 / " + lineCount + " 线路 · " + sourceInfo);

        favGroup = new Grp(FAV_NAME);
        groups.add(0, favGroup);       // 收藏恒为第 0 个分类
        rebuildFav();
    }

    private int indexOfGroup(String n) {
        for (int i = 0; i < GROUP_ORDER.length; i++) {
            if (GROUP_ORDER[i].equals(n)) return i;
        }
        return -1;
    }

    private int totalChannels() {
        int t = 0;
        for (Grp g : groups) t += g.chans.size();
        return t;
    }

    private int totalLines() {
        int t = 0;
        for (Grp g : groups) for (Chan c : g.chans) t += c.urls.size();
        return t;
    }

    private String favKey(String grp, String chan) { return grp + SEP + chan; }

    private void rebuildFav() {
        favGroup.chans.clear();
        for (int i = 1; i < groups.size(); i++) {
            Grp g = groups.get(i);
            for (Chan c : g.chans) {
                if (favKeys.contains(favKey(g.name, c.name))) favGroup.chans.add(c);
            }
        }
    }

    private void toggleFav(int gi, int ci) {
        if (gi < 0 || gi >= groups.size()) return;
        Grp g = groups.get(gi);
        if (ci < 0 || ci >= g.chans.size()) return;
        Chan c = g.chans.get(ci);
        // 收藏项引用的是原分类对象，需找到它的真实归属分类名
        String owner = g.name;
        if (g == favGroup) {
            owner = "";
            for (int i = 1; i < groups.size() && owner.isEmpty(); i++) {
                for (Chan x : groups.get(i).chans) {
                    if (x == c) { owner = groups.get(i).name; break; }
                }
            }
            if (owner.isEmpty()) owner = g.name;
        }
        String key = favKey(owner, c.name);
        boolean added;
        if (favKeys.contains(key)) { favKeys.remove(key); added = false; }
        else { favKeys.add(key); added = true; }
        prefs.edit().putStringSet("favs", new HashSet<>(favKeys)).apply();
        rebuildFav();
        toast(added ? "已收藏：" + c.name : "已取消收藏：" + c.name);
        if (panelOpen) renderPanel();
    }

    private void restoreLast() {
        curGrp = prefs.getInt("lastGrp", 1);
        curChan = prefs.getInt("lastChan", 0);
        if (curGrp < 0 || curGrp >= groups.size()) curGrp = groups.size() > 1 ? 1 : 0;
        if (groups.get(curGrp).chans.isEmpty()) {
            for (int i = 1; i < groups.size(); i++) {
                if (!groups.get(i).chans.isEmpty()) { curGrp = i; break; }
            }
        }
        if (curChan < 0 || curChan >= groups.get(curGrp).chans.size()) curChan = 0;
    }

    private void saveLast() {
        prefs.edit().putInt("lastGrp", curGrp).putInt("lastChan", curChan).apply();
    }

    // ============================ 播放器 ============================

    private void initPlayer() {
        String ua = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE
                + "; LinkTV/" + verName + ") AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)   // 源里大量 http→https 302 跳 mp4
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(15000);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(http);
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(msf).build();
        player.setVideoTextureView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    onPictureUp();
                } else if (state == Player.STATE_BUFFERING) {
                    setCoverTip("正在缓冲…");
                } else if (state == Player.STATE_ENDED) {
                    onLineFailed();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                onPictureUp();
            }

            @Override
            public void onVideoSizeChanged(VideoSize size) {
                videoW = size.width;
                videoH = size.height;
                applyVideoLayout();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "线路失败: " + (error == null ? "?" : error.getMessage()));
                onLineFailed();
            }
        });
    }

    /** 是否含明确的视频后缀（含 query） */
    private boolean knownSuffix(String low) {
        return low.contains(".m3u8") || low.contains(".mp4") || low.contains(".flv")
                || low.contains(".ts") || low.contains(".mkv") || low.contains(".m4v")
                || low.contains(".avi") || low.contains(".3gp") || low.contains(".mov");
    }

    /** 无明确后缀的 URL 给两次机会：先按自动(progressive)，再强制 HLS */
    private int variantsOf(String url) {
        return knownSuffix(url.toLowerCase()) ? 1 : 2;
    }

    private MediaItem buildItem(String url, int variant) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(url);
        String low = url.toLowerCase();
        if (low.contains(".m3u8")) {
            b.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (!knownSuffix(low) && variant == 1) {
            // .php 中转 / 无后缀流：第二步强制按 HLS 解析
            b.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        return b.build();
    }

    private Chan curChannel() {
        if (curGrp < 0 || curGrp >= groups.size()) return null;
        Grp g = groups.get(curGrp);
        if (curChan < 0 || curChan >= g.chans.size()) return null;
        return g.chans.get(curChan);
    }

    /** 换台（用户主动）：重置全部索引；只换 MediaItem，绝不重建页面/Activity */
    private void startChannel() {
        curLine = 0; curVariant = 0; attempts = 0;
        lastSwitchAt = 0;
        Chan c = curChannel();
        if (c == null) { giveUp(); return; }
        saveLast();
        showOsd(c.name);
        setCoverTip("正在连接：" + c.name);
        playCurrent();
    }

    private void playCurrent() {
        Chan c = curChannel();
        if (c == null) { giveUp(); return; }
        if (curLine >= c.urls.size()) { giveUp(); return; }
        String url = c.urls.get(curLine);
        if (url == null || url.isEmpty()) { onLineFailed(); return; }
        // ExoPlayer 不支持 rtmp，直接跳过这条线路
        if (url.toLowerCase().startsWith("rtmp://")) { onLineFailed(); return; }
        try {
            player.setMediaItem(buildItem(url, curVariant));
            player.prepare();
            player.setPlayWhenReady(true);
            setCoverTip("线路 " + (curLine + 1) + "/" + c.urls.size() + " · " + c.name);
            updateInfo();
        } catch (Exception e) {
            Log.w(TAG, "setMediaItem 异常", e);
            onLineFailed();
        }
    }

    /** 失败前进：先换协议变体，再换线路；索引单调递增 + 次数上限，绝不循环 */
    private void onLineFailed() {
        long now = System.currentTimeMillis();
        if (now - lastSwitchAt < 350) return;   // 防抖：同一次失败只前进一次
        lastSwitchAt = now;
        Chan c = curChannel();
        if (c == null) { giveUp(); return; }
        if (curLine >= c.urls.size() || attempts >= MAX_ATTEMPTS) { giveUp(); return; }
        attempts++;
        int vars = variantsOf(c.urls.get(curLine));
        curVariant++;
        if (curVariant >= vars) {
            curVariant = 0;
            curLine++;
        }
        if (curLine >= c.urls.size()) { giveUp(); return; }
        setCoverTip("切换线路 " + (curLine + 1) + "/" + c.urls.size() + "…");
        playCurrent();
    }

    private void giveUp() {
        showErr("该频道线路均不可用 · 按 ↑↓ 换台 / 确定 选台");
        revealCover();          // 揭开遮罩，让用户仍能操作，不卡在「正在连接」
        updateInfo();
    }

    private void onPictureUp() {
        if (coverShown) revealCover();
        setErr("");
        updateInfo();
    }

    private void revealCover() {
        coverShown = false;
        if (cover == null) return;
        cover.animate().cancel();
        cover.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
            @Override public void run() { cover.setVisibility(View.GONE); }
        }).start();
    }

    private void setCoverTip(String s) {
        if (coverTip != null && coverShown) coverTip.setText(s);
    }

    // ============================ 面板 ============================

    private void openPanel() {
        panelOpen = true;
        panelGrp = clamp(curGrp, 0, groups.size() - 1);
        panelChan = clamp(curChan, 0, Math.max(0, groups.get(panelGrp).chans.size() - 1));
        zone = 1;
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
        cover.bringToFront();
        renderPanel();
    }

    private void closePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
    }

    private void renderPanel() {
        if (!panelOpen) return;
        Grp g = groups.get(panelGrp);
        chanTitle.setText(g.name + " · " + g.chans.size() + " 个频道"
                + (zone == 1 ? "   ▸ 频道栏" : "   ▸ 分类栏"));
        catTitle.setText("频道分类 · " + (groups.size() - 1));

        int catTop = clamp(panelGrp - CAT_ROWS / 2, 0, Math.max(0, groups.size() - CAT_ROWS));
        for (int i = 0; i < CAT_ROWS; i++) {
            int idx = catTop + i;
            TextView t = catRows[i];
            if (idx >= groups.size()) { t.setText(""); t.setBackgroundColor(0); continue; }
            Grp gg = groups.get(idx);
            boolean sel = idx == panelGrp;
            t.setText((sel && zone == 0 ? "▶ " : "   ") + gg.name + "  " + gg.chans.size());
            t.setTextColor(sel ? (zone == 0 ? 0xFFFFCF4D : 0xFFFFFFFF) : 0xFFA8B6D8);
            t.setBackgroundColor(sel ? 0x33FFCF4D : 0);
        }

        int chanTop = clamp(panelChan - CHAN_ROWS / 2, 0,
                Math.max(0, g.chans.size() - CHAN_ROWS));
        for (int i = 0; i < CHAN_ROWS; i++) {
            int idx = chanTop + i;
            TextView t = chanRows[i];
            if (idx >= g.chans.size()) { t.setText(""); t.setBackgroundColor(0); continue; }
            Chan c = g.chans.get(idx);
            boolean sel = idx == panelChan;
            t.setText((sel && zone == 1 ? "▶ " : "   ") + c.name
                    + (c.urls.size() > 1 ? "   ·  " + c.urls.size() + " 线路" : ""));
            t.setTextColor(sel ? (zone == 1 ? 0xFFFFCF4D : 0xFFFFFFFF) : 0xFFD8E2FA);
            t.setBackgroundColor(sel ? 0x336C8CFF : 0);
        }
        updateInfo();
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : (v < lo ? lo : (v > hi ? hi : v));
    }

    // ============================ 按键（前置拦截，完全不依赖网页） ============================

    private boolean handledKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU:
            case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2:
            case KeyEvent.KEYCODE_3: case KeyEvent.KEYCODE_4: case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_6: case KeyEvent.KEYCODE_7: case KeyEvent.KEYCODE_8:
            case KeyEvent.KEYCODE_9:
                return true;
            default: return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (!handledKey(k)) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_UP) return true;   // UP 一起消费，避免系统默认行为
        handleKey(k);
        return true;
    }

    private void handleKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                onOk();
                break;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                onBack();
                break;
            case KeyEvent.KEYCODE_DPAD_UP: onUpDown(-1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: onUpDown(1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: onLeftRight(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: onLeftRight(1); break;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU:
                toggleFav(panelOpen && zone == 1 ? panelGrp : curGrp,
                          panelOpen && zone == 1 ? panelChan : curChan);
                break;
            case KeyEvent.KEYCODE_0:
                infoHidden = !infoHidden;
                infoBar.setAlpha(infoHidden ? 0f : 1f);
                updateInfo();
                break;
            case KeyEvent.KEYCODE_9:
                toast("重新加载当前频道");
                startChannel();
                break;
            default:
                // 数字 1-8 保留，暂不绑定动作
                break;
        }
    }

    private void onOk() {
        if (!panelOpen) { openPanel(); return; }
        if (zone == 0) { zone = 1; renderPanel(); return; }
        Grp g = groups.get(panelGrp);
        if (g.chans.isEmpty()) { toast("该分类暂无频道"); return; }
        curGrp = panelGrp;
        curChan = clamp(panelChan, 0, g.chans.size() - 1);
        closePanel();
        startChannel();
    }

    private void onBack() {
        if (panelOpen) { closePanel(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 2500) { finish(); return; }
        lastBackAt = now;
        toast("再按一次返回键退出");
    }

    private void onUpDown(int dir) {
        if (panelOpen) {
            if (zone == 0) {
                panelGrp = clamp(panelGrp + dir, 0, groups.size() - 1);
                panelChan = 0;
            } else {
                Grp g = groups.get(panelGrp);
                if (!g.chans.isEmpty())
                    panelChan = clamp(panelChan + dir, 0, g.chans.size() - 1);
            }
            renderPanel();
        } else {
            // 在当前分类内循环换台，绝不跨分类（v1.x 用户明确要求）
            Grp g = groups.get(curGrp);
            if (g.chans.isEmpty()) return;
            curChan = (curChan + dir + g.chans.size()) % g.chans.size();
            startChannel();
        }
    }

    private void onLeftRight(int dir) {
        if (panelOpen) {
            zone = (dir < 0) ? 0 : 1;
            renderPanel();
        } else {
            // 切分类并播该分类第一个频道（跳过空的收藏分类）
            int n = groups.size();
            for (int step = 1; step <= n; step++) {
                int gi = (curGrp + dir * step + n * n) % n;
                if (!groups.get(gi).chans.isEmpty()) {
                    curGrp = gi; curChan = 0;
                    startChannel();
                    return;
                }
            }
        }
    }

    // ============================ 提示 / 信息条 ============================

    private void toast(String msg) {
        ui.removeCallbacks(hideToast);
        toast.setText(msg);
        toast.setAlpha(1f);
        toast.bringToFront();
        ui.postDelayed(hideToast, 2200);
    }

    private void showOsd(String chan) {
        ui.removeCallbacks(hideOsd);
        osd.setText(chan);
        osd.setAlpha(1f);
        osd.bringToFront();
        ui.postDelayed(hideOsd, 2500);
    }

    private void showErr(String s) { setErr(s); }

    private void setErr(String s) {
        if (errBar == null) return;
        if (s == null || s.isEmpty()) { errBar.setAlpha(0f); return; }
        errBar.setText(s);
        errBar.setAlpha(1f);
        errBar.bringToFront();
    }

    private void updateInfo() {
        if (infoBar == null) return;
        Chan c = curChannel();
        String url = (c != null && curLine < c.urls.size()) ? c.urls.get(curLine) : "-";
        if (url.length() > 62) url = url.substring(0, 62) + "…";
        String state = "准备中";
        if (player != null) {
            int s = player.getPlaybackState();
            if (s == Player.STATE_READY) state = player.isPlaying() ? "播放中" : "已就绪";
            else if (s == Player.STATE_BUFFERING) state = "缓冲中";
            else if (s == Player.STATE_IDLE) state = "空闲";
            else if (s == Player.STATE_ENDED) state = "已结束";
        }
        infoBar.setText(
                "LinkTV v" + verName + " · 原生播放器版\n"
                + "状态：" + state + "   分类：" + (curGrp >= 0 && curGrp < groups.size()
                        ? groups.get(curGrp).name : "-") + "\n"
                + "频道：" + (c == null ? "-" : c.name) + "\n"
                + "线路：" + (curLine + 1) + "/" + (c == null ? 0 : c.urls.size())
                + "   尝试：" + attempts + "/" + MAX_ATTEMPTS + "\n"
                + "地址：" + url + "\n"
                + "数据：" + groups.size() + " 分类(含收藏) · " + totalChannels()
                + " 频道 · " + totalLines() + " 线路 · " + sourceInfo + "\n"
                + "操作：确定 选台 / ↑↓ 换台 / ←→ 分类 / 菜单 收藏 / 0 隐藏 / 9 重载");
    }

    // ============================ 在线更新（尽力而为，失败静默用内置表） ============================

    private void tryUpdateInBackground() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(UPDATE_URL).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "LinkTV/" + verName);
                    int code = conn.getResponseCode();
                    if (code != 200) { conn.disconnect(); return; }
                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n, total = 0;
                    while ((n = is.read(buf)) > 0 && total < 2_000_000) {
                        bos.write(buf, 0, n); total += n;
                    }
                    is.close(); conn.disconnect();
                    String text = new String(bos.toByteArray(), "UTF-8");
                    // 校验：必须是像样的 m3u（至少 100 个频道）才落盘，避免坏数据覆盖内置表
                    int cnt = countExtinf(text);
                    if (cnt < 100) return;
                    java.io.File f = new java.io.File(getFilesDir(), "tv.m3u");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(bos.toByteArray()); fos.close();
                    Log.i(TAG, "频道源已更新缓存: " + cnt + " 条，下次启动生效");
                } catch (Exception ignore) {
                    // 网络不可用 / 源失效：静默，继续用内置表，绝不阻塞播放
                }
            }
        }, "linktv-update").start();
    }

    private int countExtinf(String s) {
        int c = 0, i = 0;
        while ((i = s.indexOf("#EXTINF", i)) >= 0) { c++; i += 7; }
        return c;
    }
}
