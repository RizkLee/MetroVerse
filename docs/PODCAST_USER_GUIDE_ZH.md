# MetroVerse 播客使用、构建与安装指南

本文面向第一次接触 Android 项目的用户，说明如何使用 MetroVerse 的播客功能，以及如何用 Android Studio 构建并安装调试版。

## 1. 当前功能

MetroVerse 现在可以在同一个 App 中播放音乐和播客：

- 底部导航栏和大屏侧边导航栏都有 **Podcast（播客）** 入口。
- 通过 Apple Podcasts 搜索和排行榜发现公开播客。
- 粘贴 RSS/Atom 订阅源网址，直接添加未收录在 Apple Podcasts 的节目。
- 订阅、取消订阅和手动刷新 RSS 节目。
- 播放 RSS 音频、排队、暂停、拖动进度和断点续播。
- 在完整播放器中后退或前进 30 秒，并调整播放速度。
- 收藏单集以供稍后收听。
- 下载单集并使用继承自 Metrolist 的下载管理能力离线播放。
- 从 Library 查看播客频道、已保存单集和已下载单集。
- 继续保留原有 YouTube Music 播客能力。

Apple Podcasts 在这里仅用于“发现节目”。订阅和播放使用节目公开提供的 RSS 地址，不需要 Apple ID，也不会读取 Apple Podcasts 账户中的订阅。

## 2. 在 App 中使用播客

### 2.1 打开播客首页

1. 启动 MetroVerse。
2. 手机竖屏时，点击底部的 **Podcast/播客**。
3. 平板或横屏时，点击左侧导航栏的播客图标。
4. 页面上方显示订阅、继续收听和发现内容；首次加载排行榜需要联网。

如需把播客设为启动后默认页面：

1. 打开 **Settings/设置**。
2. 进入 **Appearance/外观**。
3. 找到默认打开页面选项。
4. 选择 **Podcast/播客**。

### 2.2 搜索 Apple Podcasts

有两个入口：

- 在播客首页点击搜索图标；或
- 打开全局 **Search/搜索**，选择 **Podcast/播客** 来源。

操作步骤：

1. 输入节目名称、作者或主题。
2. 提交搜索。
3. 点击结果进入节目详情。
4. App 会先通过 Apple lookup 获取公开 RSS 地址，再抓取节目和单集。
5. 点击 **Subscribe/订阅** 将节目保存到资料库。

搜索与榜单由 Apple Podcasts 公开接口提供，实际结果可能因所选地区不同而变化。

### 2.3 切换 Apple Podcasts 地区

1. 打开 Podcast 首页。
2. 点击操作区中的 **Apple Podcasts 地区** 按钮。
3. 选择美国、英国、中国大陆、香港、台湾、日本等支持的 storefront。
4. 首页榜单会立即按新地区重新加载。
5. 在播客搜索结果页也可以点击顶栏的地区图标切换，搜索会自动重跑。

地区选择会持久保存，只影响 Apple 的发现、榜单和搜索，不会改变 RSS 订阅源内容或播放器行为。

### 2.4 直接添加 RSS 订阅源

1. 打开 **Podcast/播客** 首页。
2. 点击添加图标。
3. 粘贴完整订阅源地址，例如 `https://example.com/feed.xml`。
4. 点击 **Add/添加**。
5. 等待节目详情出现，再点击 **Subscribe/订阅**。

也可以在全局搜索中选择播客来源，然后直接粘贴以 `http://` 或 `https://` 开头的 RSS 地址。

注意：普通网页地址不一定是 RSS 地址。有效订阅源通常返回 RSS、Atom 或 XML 内容，并包含音频 enclosure。

### 2.5 订阅、刷新与取消订阅

- **订阅**：在 RSS 节目详情页点击订阅按钮。
- **刷新单个节目**：在详情页点击刷新图标。
- **刷新全部订阅**：在播客首页点击刷新图标。
- **取消订阅**：再次点击节目详情页的已订阅按钮。

打开播客首页时，App 也会刷新订阅节目。当前版本没有定时后台刷新任务；App 未打开时不会按固定周期抓取新单集。

取消订阅不会立即删除已经收藏、下载或带有播放进度的单集。

### 2.6 播放与断点续播

1. 在节目详情页点击任意单集。
2. 单集会进入 MetroVerse 与音乐共用的播放器和队列。
3. 点击底部迷你播放器打开完整播放器。
4. 播客单集的左右主按钮是 **后退 30 秒** 和 **前进 30 秒**。
5. 锁屏通知、Android Auto 和兼容耳机控制也会使用前进/后退语义。
6. 打开播放器菜单，进入 **Advanced/高级** 可调整播放速度。

MetroVerse 会周期保存播客进度，并在切换媒体、退出服务或下次播放同一单集时恢复。正常播放到接近结尾后，再次播放会从头开始。

### 2.7 收藏单集

单集上的心形按钮表示“稍后收听”：

- 空心：尚未收藏。
- 实心：已经收藏。

收藏后的单集可在 **Library > Podcasts > Episodes** 中找到。RSS 单集只修改本地资料库，不会向 YouTube Music 发送收藏请求。

### 2.8 下载和删除下载

1. 打开单集右侧菜单，或打开完整播放器菜单。
2. 点击 **Download/下载**。
3. 等待下载完成。
4. 在 **Library > Podcasts > Downloaded** 查看已下载单集。
5. 再次打开菜单可删除下载。

下载沿用 MetroVerse 继承的 `DownloadUtil`、Media3 DownloadService 和缓存。下载时不要强制停止 App。某些节目使用会过期、需要登录或带 DRM 的音频地址，这类内容可能无法下载。

### 2.9 Library 中的播客

打开 **Library/资料库** 并选择播客后，可以查看：

- **Channels/频道**：RSS 订阅和原有 YouTube Music 播客。
- **Episodes/单集**：已保存单集。
- **Downloaded/已下载**：已完成下载的单集。

点击 RSS 节目会进入新的 RSS 详情页；点击 YouTube Music 播客仍进入原有在线播客页。

## 3. 已知限制

- 不会同步 Apple Podcasts 账户、播放历史或私人订阅。
- 当前没有 OPML 导入/导出。
- 当前没有后台定时刷新；需要打开播客页或点击刷新。
- RSS 音频必须是 Android Media3 支持、设备可直接访问的格式。
- 需要网页登录、Cookie、付费鉴权或 DRM 的私人订阅源可能无法播放。
- RSS 直链不会参与 YouTube 电台、歌词、Last.fm scrobble 或 Listen Together。
- Apple 搜索和榜单需要访问 `itunes.apple.com`；RSS 播放还需要访问节目自己的服务器。
- FOSS 构建不包含 Google Cast；GMS 构建才包含 Cast。

## 4. 用 Android Studio 打开项目

项目目录是：

```text
E:\Code\music-podcast-app\Metrolist
```

### 4.1 检查基础环境

本项目当前要求：

- Android Studio 较新稳定版。
- JDK 21。
- Android SDK Platform 37，用于编译。
- Android SDK Platform Tools，用于 `adb` 安装。
- 最低运行系统 Android 8.0（API 26）。

Android Studio 设置步骤：

1. 打开 Android Studio。
2. 选择 **Open**，选中 `E:\Code\music-podcast-app\Metrolist`。
3. 如果询问是否信任项目，确认这是本机项目后选择信任。
4. 打开 **File > Settings > Build, Execution, Deployment > Build Tools > Gradle**。
5. 将 **Gradle JDK** 选择为 JDK 21；可选择 Android Studio 提供的兼容 JBR 21。
6. 打开 **Tools > SDK Manager**。
7. 在 **SDK Platforms** 安装 Android API 37。
8. 在 **SDK Tools** 安装 Android SDK Build-Tools、Android SDK Platform-Tools 和 Android SDK Command-line Tools。
9. 返回项目，点击工具栏中的 **Sync Project with Gradle Files**。

首次同步会下载依赖，耗时取决于网络。不要在下载过程中关闭 Android Studio。

### 4.2 选择构建变体

1. 打开 **Build > Select Build Variant**，或打开 Build Variants 工具窗口。
2. 将 `app` 模块选择为 `fossDebug`。

建议初次使用 `fossDebug`：它不依赖 Google Cast 配置，调试包名为 `com.rizklee.metroverse.debug`，可以和 MetroVerse 正式版及上游 Metrolist 共存。

## 5. 直接运行到设备

### 5.1 使用 Android 模拟器

1. 打开 **Tools > Device Manager**。
2. 点击添加虚拟设备。
3. 选择常见 Pixel 设备。
4. 下载并选择 API 26 或更高的系统镜像，建议 API 35 或 36。
5. 启动模拟器。
6. 在 Android Studio 顶部选择该模拟器。
7. 点击绿色 Run 按钮。

播客需要网络。模拟器必须能够访问 Apple 接口和外部 RSS/音频服务器。

### 5.2 使用 Android 手机

1. 在手机设置中打开“关于手机”。
2. 连续点击“版本号”约 7 次，启用开发者选项。
3. 在开发者选项中打开“USB 调试”。
4. 用支持数据传输的 USB 线连接电脑。
5. 在手机上允许这台电脑的 USB 调试授权。
6. 如 Windows 无法识别设备，安装手机厂商 USB 驱动。
7. 在 Android Studio 顶部选择手机并点击 Run。

可在终端检查连接：

```powershell
D:\Android\AndroidSDK\platform-tools\adb.exe devices
```

设备状态应为 `device`，而不是 `unauthorized`。

## 6. 生成 APK

### 6.1 Android Studio 图形界面

1. 确认 Build Variant 是 `fossDebug`。
2. 选择 **Build > Build Bundle(s) / APK(s) > Build APK(s)**。
3. 等待右下角提示构建完成。
4. 点击提示中的 **locate** 查找 APK。

典型输出目录是：

```text
app\build\outputs\apk\foss\debug\
```

### 6.2 Windows 终端

在项目根目录打开 PowerShell：

```powershell
cd E:\Code\music-podcast-app\Metrolist
.\gradlew.bat :app:assembleFossDebug
```

然后在输出目录查找 APK：

```powershell
Get-ChildItem .\app\build\outputs\apk\foss\debug\*.apk
```

### 6.3 构建签名正式版

正式版不能使用 Debug 签名。项目已经提供本机 keystore 生成脚本、Gradle 签名配置和完整说明：

- [MetroVerse 正式版 APK 签名、构建与发布指南](RELEASE_BUILD_ZH.md)

生成并备份签名文件后，命令是：

```powershell
.\gradlew.bat :app:assembleFossRelease
```

正式 APK 位于 `app\build\outputs\apk\foss\release\app-foss-release.apk`。必须用 `apksigner verify` 确认签名后才能分发。

### 6.4 用 adb 安装 APK

先连接并授权手机，然后执行：

```powershell
D:\Android\AndroidSDK\platform-tools\adb.exe install -r .\app\build\outputs\apk\foss\debug\app-foss-debug.apk
```

如果实际文件名不同，以 `Get-ChildItem` 显示的名称为准。

常见安装错误：

- `device unauthorized`：解锁手机并重新确认 USB 调试授权。
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE`：设备上已有同包名但签名不同的 App；先备份数据，再卸载冲突版本。
- `INSTALL_FAILED_VERSION_DOWNGRADE`：先卸载旧调试版，或仅在清楚影响时使用 `adb install -r -d`。
- 安装后找不到 App：调试版名称是 **MetroVerse Debug**。

## 7. 构建前建议执行的检查

在项目根目录运行：

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
.\gradlew.bat :app:testFossDebugUnitTest --tests "com.metrolist.music.podcast.PodcastParsingTest"
.\gradlew.bat :app:lintFossDebug
.\gradlew.bat :app:assembleFossDebug
```

当前仓库全量单元测试中，原有 `YouTubeUtilsTest` 有 4 个图片 URL 尺寸断言失败；它们与播客整合无关。播客专项测试已通过。

`lintFossDebug` 任务可以完成，但现有项目报告仍包含 67 个 error 和 265 个 warning；播客新增文件没有 lint error。HTML 报告位于 `app/build/reports/lint-results-fossDebug.html`。

已验证的调试 APK 是 `app/build/outputs/apk/foss/debug/app-foss-debug.apk`，约 47 MiB。

## 8. 手工验收清单

建议每次改动播客代码后完成以下检查：

- 手机竖屏底栏显示 Podcast，平板/横屏侧栏也显示 Podcast。
- Apple 搜索可返回结果，点击后能解析 RSS。
- 粘贴一个 RSS URL 可打开详情并订阅。
- 退出并重新打开 App 后订阅仍存在。
- 播放单集后能暂停、拖动和前后跳转 30 秒。
- 播放一段后切换到音乐，再回到单集可恢复进度。
- RSS 单集可加入队列，重启 App 后队列仍可恢复并播放。
- 收藏单集后可在 Library 的 Episodes 中看到。
- 下载后可在断网状态播放，并出现在 Downloaded 中。
- RSS 单集分享的是节目网页或音频地址，不是伪造的 YouTube 链接。
- 音乐仍使用上一首/下一首，YouTube Music 播客仍打开原页面。
- 播放 RSS 时不会发起 YouTube 解密、歌词或电台请求。

## 9. 故障排查

### Gradle 提示找不到 Android 37

打开 SDK Manager 安装 API 37，然后重新 Sync。也可以确认 `local.properties` 中的 `sdk.dir` 指向正确 Android SDK。

### Gradle 提示 Java 版本错误

确认 Gradle JDK 是 21。不要用 JDK 17 或更早版本构建当前项目。

### Apple 搜索为空

检查网络、系统地区和 `itunes.apple.com` 是否可访问。Apple 的接口可能按地区返回不同内容。

### RSS 地址无法添加

用浏览器打开该地址，确认返回的是 XML/RSS，而不是普通网页、登录页或防机器人页面。服务器跳转后的最终地址必须可以被 App 访问。

### 单集可见但无法播放

可能原因包括音频地址过期、服务器拒绝移动端请求、TLS 证书错误、不支持的编码、私人鉴权或 DRM。可在 Android Studio 的 Logcat 中筛选 `MusicService`、`Podcast` 或 `ExoPlayer`。

Windows 命令行也可以查看日志：

```powershell
D:\Android\AndroidSDK\platform-tools\adb.exe logcat | Select-String "MusicService|Podcast|ExoPlayer"
```

### 修改后出现数据库问题

数据库版本已从 38 升到 39。调试阶段可以卸载 **MetroVerse Debug** 后重装来清空调试数据，但这会删除调试版的订阅、队列、下载索引和设置。不要直接清除正式版数据。

## 10. 关于密钥

播客功能不需要 Apple API Key。项目中的 Last.fm 配置与播客无关。

不要提交本机的 `lastfm-api-key.txt`、keystore、密码或 `local.properties`。需要 Last.fm 时，应把 `LASTFM_API_KEY` 和 `LASTFM_SECRET` 放在本机 `local.properties` 或环境变量中，并确保文件不进入 Git。
