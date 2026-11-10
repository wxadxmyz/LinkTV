# LinkTV v1.1.0

> 安卓 TV 直播应用 · 英文名 **LinkTV** · 包名 `com.linktv` · 版本 **1.1.0**
> 仓库：https://github.com/wxadxmyz/LinkTV

**打开即全屏看直播，不需要配置任何直播源。** 央视、各大卫视、全国 34 个省级行政区地方台
共 182 个频道，直播直链由程序内置并自动从云端清单更新，用户无需填写、导入或维护任何数据，
界面也不出现任何「源」相关字样。

技术上是一个 TV 风格的 HTML/JS 应用（hls.js 本地内置播放 HLS），外套安卓 WebView 外壳编译成 APK，
可装到电视 / 盒子 / 手机。

## 零、下载 APK（GitHub Actions 自动构建）

[`.github/workflows/build.yml`](.github/workflows/build.yml) 在每次 push 到 `main` 时
用 **JDK 17 + Android SDK 34** 云端构建 debug APK，并自动发布到 `Releases → latest`。

**固定下载直链（长期有效，永远指向最新构建）：**

```
https://github.com/wxadxmyz/LinkTV/releases/download/latest/LinkTV-debug.apk
```

| 想做什么 | 怎么做 |
|---|---|
| 下载 APK | 上面的直链，或仓库 **Releases → LinkTV 最新构建** → `LinkTV-debug.apk` |
| 下载构建产物 | **Actions** → 对应运行页 → `LinkTV-debug-apk` artifact |
| 手动触发构建 | **Actions → Build LinkTV APK → Run workflow** |
| 本地命令行构建 | `cd android && ./gradlew assembleDebug`（需 JDK 17 + Android SDK 34） |

APK 为 debug 签名，可直接 `adb install` 安装，无需额外签名。

## 一、界面与操作（v1.1.0 全屏优先）

**启动即全屏播放**，画面铺满，上方浮一层信息层（OSD），**3 秒后自动隐藏**只留纯画面，
按任意键重新亮起 3 秒：

| 位置 | 内容 |
|---|---|
| 左上角 | 第一行 **LinkTV**，第二行 **频道名**（如 CCTV-1 综合），下方分类与序号 |
| 右上角 | **分辨率**（4K / 1080P / 720P / HD）与当前时间 |
| 底部居中 | **LIVE 红点 + 当前节目/时段进度条 + 正在播出的内容 + 时段** |

> 直播流没有可拖动的进度条，进度条表示当前节目的播出进度（节目单取不到时按时段显示）。

**遥控器 / 键盘**

| 按键 | 全屏时 | 选台面板里 |
|---|---|---|
| `确定 / Enter` | 呼出选台面板 | 播放选中频道并收起面板 |
| `↑ ↓` | 换台 ±1，到分组边界自动跨到相邻分类并续播 | 在频道列表移动，同样跨分类 |
| `← →` | 快速切到上/下一个分类并播放其首个频道 | 快速切分类 |
| `菜单 / Menu` | 收藏 / 取消收藏当前频道 | 收藏 / 取消收藏高亮频道 |
| `返回 / Esc` | 退出应用（安卓） | 收起面板回全屏 |

**选台面板**：左侧一列「央视 / 卫视 / 地方台 / 收藏夹」，紧挨右侧是该分类的频道列表；
地方台按「地区 · 省份」分区显示。每个频道项右侧有星标（★/☆），点星标也能切换收藏。

手机上没有遥控器：点画面 = 呼出/收起面板，点分类 = 切分类，点频道 = 播放，点星标 = 收藏。
竖屏时界面自动旋转 90°，转动手机即为完整横版。

## 二、直播源：内置 + 云端自动维护（用户不碰源）

- **央视 20 个**：央视官方 HLS 直链（最稳）。
- **卫视 / 地方台**：从公开直播清单自动匹配的可播直链，离线内置在 `js/channels.js` 的 `BUILTIN_SOURCES`。
- **自动更新**：App 启动时静默加载云端清单 [`data/sources.js`](data/sources.js)
  （jsDelivr CDN 镜像仓库同名文件），按频道名覆盖内置直链。以后只需更新云端清单，
  所有已安装的应用无需重装即可用上新源。取不到云端时自动用内置直链，全程无感、界面零提示。
- **hls.js 已本地内置**（`js/hls.min.js`），APK 完全离线也能初始化播放；安卓 WebView 原生 HLS 亦可用。
- 个别频道暂时没有可用直链时，显示品牌「正在准备频道」待播画面，不弹任何「源失效 / 加载失败」提示。

> 覆盖率说明（如实）：央视 20/20、卫视 26/36、地方台 38/126 有内置可播直链，共 **84/182**。
> 地方台公开可播直链本就稀少，剩余频道随云端清单持续补齐。所有直链来自公开直播清单，
> 可能随上游变动；本应用不含任何需要用户填写的源数据。

## 三、品牌与图标

**方案 A「信号电视」**：蓝紫渐变圆角屏幕 + 中央白色播放三角 + 右上角信号弧，寓意"直播信号接入大屏"。
矢量源与成品在 `branding/`：`linktv-icon.svg`（矢量源）、`linktv-icon-1024.png` 等主图标、
`linktv-banner-1280x720.png`（电视横幅）、`favicon.png`。

已接入工程：安卓 `mipmap-*` 各密度 launcher 图标 + `drawable-xhdpi/banner.png`、网页 `favicon.png`。
修改 SVG 后可用 cairosvg / ImageMagick 重新导出各尺寸 PNG。

## 四、目录结构

```
tv-live/
├── .github/workflows/build.yml   # GitHub Actions 自动构建 APK
├── index.html                   # 网页入口（全屏直播界面）
├── tv-ui.html                   # 早期 UI 预览页（模拟数据，非真机界面）
├── favicon.png
├── css/style.css                # 全屏 TV 样式（含竖屏旋转）
├── js/
│   ├── app.js                   # 全屏逻辑 / 遥控导航 / 收藏 / 选台面板 / OSD
│   ├── channels.js              # 频道目录 + 内置直链 + 云端清单地址
│   └── hls.min.js               # hls.js 本地内置（离线可用）
├── data/
│   ├── sources.js               # 云端直播清单（程序自动加载，用户无需操作）
│   └── sources.json             # 同上，JSON 副本（便于维护/查阅）
├── branding/                    # 图标矢量源与多尺寸成品
└── android/                     # 安卓 TV WebView 外壳工程
    ├── gradlew / gradlew.bat / gradle/wrapper/   # 完整 Gradle Wrapper
    └── app/src/main/assets/      # 已内置上述网页与数据，APK 自包含
```

## 五、在电脑浏览器里先体验（无需安卓）

```bash
cd tv-live
python3 -m http.server 8080
# 浏览器打开 http://localhost:8080
```

> 能否播放取决于网络能否访问对应直播域名；无法访问时会显示「正在准备频道」待播画面。

## 六、编译安卓 APK（在你的电脑上）

1. 安装 **Android Studio**（含 Android SDK 34 与 JDK 17）。
2. `File → Open` 选择 `tv-live/android`。
3. 等待 Gradle 同步完成（首次需联网下载 AGP / Gradle）。
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
5. 产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。
6. 安装到设备：
   ```bash
   adb connect <电视IP>:5555
   adb install app-debug.apk
   ```

命令行亦可：`cd android && ./gradlew assembleDebug`（Wrapper 已内置，无需预装 Gradle）。

## 七、已知限制

- **直播直链来自公开清单**，随时可能失效、限速或地区限制；云端清单会持续更新，但不保证 100% 可播。
- **地方台覆盖率有限**：目录覆盖全部 34 个省级行政区，但有直链的约 38/126，其余待云端清单补齐。
- **官方节目单**：尽力拉取央视 EPG 显示真实节目名与播出进度；受跨域/网络限制取不到时，
  降级为「正在直播 · 频道名 + LIVE + 时段进度」，不影响播放。
- **分辨率**：HLS 直链可读取真实分辨率（4K/1080P/720P），读不到时显示 HD。
- **版权提示**：请确保你拥有所播放内容的合法观看授权，仅用于个人学习/测试。
