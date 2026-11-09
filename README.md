# LinkTV（CCTV + 各大卫视）

> 英文名 **LinkTV** · 安卓包名 `com.linktv`
> 仓库：https://github.com/wxadxmyz/LinkTV

一个 **安卓 TV 版** 直播应用，采用 **HTML/JS + 公开 IPTV 源** 方案：
核心是网页版直播应用（hls.js 播放 m3u8，支持遥控器方向键导航），
外层套一个 **安卓 WebView 外壳**，编译成 APK 即可装到电视 / 盒子 / 手机。

## 零、自动构建 APK（GitHub Actions）

仓库已配置 [`.github/workflows/build.yml`](.github/workflows/build.yml)：
每次 push 到 `main` 都会用 **JDK 17 + Android SDK 34** 在云端构建 debug APK。

| 想做什么 | 怎么做 |
|---|---|
| 下载 APK | 仓库 **Releases → LinkTV 最新构建**（`latest` 预发布）→ 点 `LinkTV-debug.apk` |
| 下载构建产物 | **Actions** → 对应运行页 → `LinkTV-debug-apk` artifact |
| 手动触发构建 | **Actions → Build LinkTV APK → Run workflow** |
| 本地命令行构建 | `cd android && ./gradlew assembleDebug`（需 JDK 17 + Android SDK 34） |

APK 为 debug 签名，可直接 `adb install` 安装到设备，无需额外签名。

## 品牌与图标

**LinkTV** —— 标志由「相扣的链环（Link）+ 播放三角（TV）」构成，红橙 / 青蓝双色渐变。
矢量源与成品都在 `branding/`：

| 文件 | 用途 |
|---|---|
| `branding/linktv-icon.svg` | 矢量源文件，可无损缩放/改色 |
| `branding/linktv-icon-512.png` | 主图标预览 |
| `branding/linktv-banner-1280x720.png` | 电视横幅（含品牌字） |
| `branding/favicon.png` / `.ico` | 网站图标 |

已接入工程：安卓 `mipmap-*` 各密度 launcher 图标 + `drawable-xhdpi/banner.png` 电视横幅，
网页 `favicon.png`。修改 SVG 源文件后可用 cairosvg / ImageMagick 重新导出各尺寸 PNG。

## 目录结构

```
tv-live/
├── .github/workflows/build.yml   # GitHub Actions 自动构建 APK
├── .gitignore
├── index.html                   # 网页入口
├── tv-ui.html                   # 电视版 UI 预览（横屏，模拟数据）
├── favicon.png
├── css/style.css                # TV 风格样式
├── js/channels.js               # 频道数据源（央视 / 卫视 / 地方台，可改）
├── js/app.js                    # 播放器 + 遥控导航 + 收藏 + M3U 导入
├── branding/                    # 图标矢量源与多尺寸成品
├── android/                     # 安卓 TV WebView 外壳工程（Android Studio 项目）
│   ├── gradlew / gradlew.bat     # Gradle Wrapper（已内置，命令行可直接构建）
│   ├── gradle/wrapper/gradle-wrapper.jar
│   └── app/src/main/assets/      # 已内置上述网页，APK 自包含
└── README.md
```

## 一、在电脑浏览器里先体验（无需安卓）

```bash
cd tv-live
python3 -m http.server 8080
# 浏览器打开 http://localhost:8080
```

> 源能否播放取决于你的网络是否能访问对应域名。央视官方 HLS 较稳；
> 卫视为公开示例源，可能失效，请按需替换。

**操作方式**
- 鼠标点击：选频道、按钮、搜索。
- 键盘（等价于电视遥控 D-pad）：
  - `↑ ↓` 在「分类标签 / 频道列表 / 控制栏」之间移动
  - `← →` 在列表内切换上一个/下一个分类；在控制栏切换按钮
  - `Enter` 确定（播放/执行）
  - `空格` 播放/暂停，`F` 全屏，`Menu` 打开导入

## 二、编译安卓 APK（在你的电脑上）

1. 安装 **Android Studio**（含 Android SDK 34 与 JDK 17）。
2. `File → Open` 选择 `tv-live/android` 目录。
3. 等待 Gradle 同步完成（首次会下载 AGP / Gradle，需联网）。
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
5. 生成的 APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`。
6. 用 adb 安装到电视/盒子/手机：
   ```bash
   adb connect <电视IP>:5555     # 电视需开启「无线调试/ADB」
   adb install app-debug.apk
   ```

> 仓库已内置完整的 Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle-wrapper.jar`），
> 命令行构建无需预先安装 Gradle：`cd android && ./gradlew assembleDebug`。

## 三、更换 / 添加直播源

两种方式，任选其一：

**方式 A：直接改文件**
编辑 `js/channels.js`（安卓端改 `android/app/src/main/assets/js/channels.js` 后重新编译）：
```js
{ name: "湖南卫视", url: "https://你的源/index.m3u8" }
```

**方式 B：App 内导入 M3U**
- 点右上角「导入M3U」，粘贴 `.m3u` 文本（支持 `#EXTINF` 格式），或点「拉取远程URL」填直链。
- 也可在 `channels.js` 里填写 `REMOTE_M3U_URL` 指向你的播放列表。

## 四、已知限制

- **公开 IPTV 源不稳定**：随时可能失效、限速或地区限制，需自行维护源列表。
- **版权提示**：请确保你拥有所播放内容的合法观看授权，仅用于个人学习/测试。
- **hls.js 走 CDN**：首屏需联网加载 hls.js；安卓 WebView 原生支持 HLS，
  即使 hls.js 未加载也能播 `.m3u8`。如需完全离线，可把 hls.min.js 下载到本地 `js/` 并改引用。
- 地方台频道目录覆盖全部 34 个省级行政区，但其中大部分**直播源留空待补**
  （公开源不稳定，未填充无效地址），需通过「导入 M3U」批量灌入自己的源。
