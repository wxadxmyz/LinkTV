# LinkTV v1.2.0

> 安卓 TV 直播应用 · 英文名 **LinkTV** · 包名 `com.linktv` · 版本 **1.2.0**
> 仓库：https://github.com/wxadxmyz/LinkTV
> 打开就是 CCTV 与全国省级卫视的官方直播，不用登录、不用配源、不用导入 M3U。

## 这一版做了什么（v1.2.0）

**播放内核整个换掉**：不再内置任何 m3u8 直链，改为用系统 WebView 全屏打开
**央视频官方直播页** `https://www.yangshipin.cn/tv/home`，由官方网页自己负责鉴权、
拉流与播放。App 只做三件事：把网页里除了播放画面以外的东西藏起来、把画面钉成全屏、
再接上遥控器。

这解决的是 v1.1.0 的根本问题：直链带 CORS / 防盗链，从 `file://` 页面发请求常被挡，
真机上就变成黑屏「正在准备频道」。现在流由官方页面自己拉，**实测能出画面**。

同时按反馈改掉了界面与遥控器的全部问题（详见第三节）。

## 一、频道（49 台）

| 分类 | 数量 | 内容 |
|---|---|---|
| 央视 | 18 | CCTV-1 ~ CCTV-17、CCTV-5+ 体育赛事 |
| 卫视 | 31 | 31 个省级行政区卫视 |
| 收藏夹 | — | 按菜单键或点星标加入 |

> **地方台暂时停用**：官方直播页没有省市地方台，本版按要求只保留 CCTV + 31 个省级卫视。
> 频道表结构仍保留扩展位，需要时可在 `js/channels.js` 里加回。

每个台对应央视频官方 `pid`（如 CCTV-1 = `600001859`、湖南卫视 = `600002475`），
换台即毫秒级直达，不依赖运行时学习。

## 二、技术方案

```
MainActivity (Java)
  ├─ WebView 加载 https://www.yangshipin.cn/tv/home?pid=<上次频道>
  ├─ 桌面 UA 伪装（从系统真实 UA 提取 Chrome 版本，只替换平台标识）
  ├─ dispatchKeyEvent 前置拦截遥控器按键 → __LINKTV_KEY__(name)
  └─ onPageStarted / onPageFinished 各注入一次 channels.js + inject.js

inject.js（注入到官方页面）
  ├─ clean()：找最大的 <video>，沿 DOM 链隐藏所有非播放器兄弟节点，
  │           video 钉成 fixed 全屏。按位置/尺寸判断，不写死网页 class
  ├─ 叠加 LinkTV 的 UI：OSD / 选台面板 / 右上角收藏提示 / 换台遮罩 / 错误提示
  ├─ 换台：优先「点击页面内频道元素」（不刷新页面，实测 livepid 正确变化）
  │        2.4 秒无反应 → 降级 ?pid= 整页导航
  ├─ 20 秒看门狗：无画面自动切央视网备用线路，并给出可见状态文字
  └─ 节目单：抓官方 EPG 接口，失败静默降级为「正在直播 · 频道名」
```

**备用线路**：CCTV 各台 → `https://tv.cctv.com/live/cctvN/`；
省级卫视 → `https://tv.cctv.com/live/`（央视网直播大厅）。

收藏与上次频道存在 Android `SharedPreferences`（跨页面/跨域可靠），
通过 `LinkTVStore` JavaScript 桥读写；本地浏览器打开时自动降级到 `localStorage`。

## 三、遥控器操作

| 按键 | 全屏播放时 | 选台面板打开时 |
|---|---|---|
| ▲ / ▼ | 上 / 下一个台（**只在当前分类内循环**） | 右栏：在当前分类内移动高亮；左栏：切换分类 |
| ◀ | 上一个分类 | **焦点移到左侧「频道分类」栏**（金色框） |
| ▶ | 下一个分类 | 左栏时：回到右侧频道列表；右栏时：切下一个分类 |
| 确定 | 打开选台面板 | 左栏：进入该分类频道列表；右栏：播放高亮频道并收起 |
| 菜单 | 收藏 / 取消收藏当前台 | 收藏 / 取消收藏高亮频道 |
| 返回 | 连按两次退出 | 收起面板 |

**v1.1.0 → v1.2.0 修掉的问题**

1. 界面只占屏幕左上角一条、文字重叠断行 → 去掉误触发的竖屏旋转，注入层用 `position:fixed; inset:0` 铺满
2. 按左键光标没到分类栏 → 新增左右栏焦点区，左键进左栏并给金色焦点框
3. 频道按到最下面自动跳到第 2 个分类 → 上下键改为只在当前分类内循环，切分类交给左右键
4. 频道分类栏跟着右侧列表一起上滚 → 去掉 `scrollIntoView`，只手动滚动右侧列表容器
5. 收藏提示弹在左上角 → 改为**屏幕右上角**的临时浮层，2 秒淡出
6. 播不了就纯黑屏 → 全程有可见状态：连接中 / 已切备用线路 / 网络异常请重试

**OSD**：左上角软件名 + 频道名 + 分类序号，右上角分辨率 + 时钟，
底部 LIVE 红标 + 当前节目 + 时段进度条，3 秒自动隐藏，任意键重新亮起。

## 四、构建与安装

推送到 `main` 后由 GitHub Actions 自动构建 debug APK，并更新 `latest` Release。

- **长期下载直链**：<https://github.com/wxadxmyz/LinkTV/releases/download/latest/LinkTV-debug.apk>
- Release 页：<https://github.com/wxadxmyz/LinkTV/releases/tag/latest>

也可本地构建（需 Android SDK 34 + JDK 17）：

```bash
cd android
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到电视/盒子：U 盘拷贝 APK → 电视文件管理器打开 → 允许「未知来源」→ 安装。
`minSdk 21`（Android 5.0+），横屏，TV Leanback 桌面可见。

## 五、已知限制（如实说明）

- **依赖官方网页**：App 只是打开央视频的公开网页，播放由官方负责；官方改版可能让清浮层或换台失效，
  届时更新注入脚本即可。想长期稳定，装官方 App 更省心。
- **需要较新的 WebView**：官方播放器用 wasm + MSE，建议 Chrome 内核 70 以上；
  过旧会弹窗提示升级 Android System WebView，不会让你对着黑屏。
- **网络**：异地 / 运营商限制可能访问不了央视频，20 秒后自动切央视网备用线路；
  两条都不通会明确提示检查网络。
- **换台需要等一下**：官方播放器重载通常几秒，期间用黑遮罩盖住，不会露出网页原貌。
- **省级卫视备用线路有限**：央视网没有各省卫视的独立直播页，只能回落到直播大厅。
- 沙箱里已用真实浏览器验证「官方页面能播 + 清浮层全屏 + 连续换台 livepid 正确」，
  但各品牌电视 / 盒子的 WebView 差异仍需真机实测。

## 六、目录

```
tv-live/
├── index.html                 # 本地界面回归页（file:// 打开即 demo 模式）
├── js/
│   ├── channels.js            # 49 台频道表：pid / 页面匹配名 / 备用线路
│   └── inject.js              # 注入层：清浮层 + 全屏 UI + 换台 + 看门狗 + 按键
├── android/
│   ├── app/src/main/java/com/linktv/MainActivity.java   # WebView 外壳
│   ├── app/src/main/assets/   # 注入脚本与回归页（打包进 APK）
│   └── app/build.gradle       # versionCode 3 / versionName 1.2.0
├── tv-ui.html                 # 早期 UI 设计预览（保留）
├── branding/                  # 方案 A「信号电视」图标与横幅
└── .github/workflows/build.yml
```

> 本应用只打开官方公开网页，**不内置、不分发任何直播源**，节目版权归中央广播电视总台所有。
> 仅供个人在家观看。
