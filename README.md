<p align="right">
  <a href="#english">English</a> · <a href="#简体中文">简体中文</a>
</p>

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_launcher_artwork.png" width="144" alt="MetroVerse app icon" />
</p>

<h1 align="center">MetroVerse</h1>

<p align="center">
  Music and open podcasts, one Android listening experience.
</p>

<p align="center">
  <a href="https://github.com/RizkLee/MetroVerse/releases"><img src="https://img.shields.io/github/v/release/RizkLee/MetroVerse?include_prereleases&label=release" alt="Release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL-3.0" /></a>
</p>

<a id="english"></a>

## English

MetroVerse is an independently maintained Android audio client that brings YouTube Music and public RSS podcasts into one library, search surface, queue, player, notification, cache, and download system.

It keeps the familiar Material 3 interaction model inherited from Metrolist while adding native podcast discovery, subscriptions, direct media playback, resume state, and podcast-aware controls. Apple Podcasts is used only to discover public feeds. No Apple account is required.

### Highlights

- YouTube Music browsing, search, playlists, library, queue, background playback, lyrics, and local history.
- A dedicated Podcast destination across phone, tablet, and landscape navigation.
- Apple Podcasts charts, storefront selection, search, and genre-based Categories.
- Direct RSS/Atom import, subscriptions, manual refresh, episode artwork, descriptions, and publication dates.
- Shared Media3 playback, notification, Android Auto, cache, downloads, and persistent queue.
- Podcast resume positions, 10-second seek controls, playback speed, and a gradual sleep-timer fade.
- Source-aware behavior that keeps RSS episodes out of YouTube-only radio, lyrics, scrobbling, and synchronization paths.
- English and Simplified Chinese text for MetroVerse-specific podcast workflows.

### Project lineage

MetroVerse is built from established GPL-3.0 work:

- [Metrolist](https://github.com/MetrolistGroup/Metrolist) provides the music architecture, Jetpack Compose foundation, player, queue, downloads, database, and most inherited functionality.
- [Podium](https://github.com/aimok04/podium) was studied as a GPL-3.0 reference for Apple Podcasts discovery, RSS parsing, subscriptions, and genre-based podcast browsing.
- MetroVerse integrates those podcast concepts into Metrolist's existing architecture. It does not embed Podium as a second application or run a separate podcast player.

MetroVerse is not an official release of Metrolist or Podium and is not affiliated with Apple, Google, or YouTube. Original copyright notices, source headers, and contribution history remain preserved. See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).

### Package

```text
App name:        MetroVerse
Application ID:  com.rizklee.metroverse
Debug ID:        com.rizklee.metroverse.debug
Version:         0.3.0
Minimum Android: Android 8.0 / API 26
Target Android:  API 36
```

The inherited Kotlin namespace remains `com.metrolist.music`; the installed application uses the independent MetroVerse package ID above.

### Build

Requirements:

- Android Studio or command-line Gradle with JDK 21.
- Android SDK Platform 37 and Platform Tools.

```powershell
# Debug APK
.\gradlew.bat :app:assembleFossDebug

# Podcast-focused tests
.\gradlew.bat :app:testFossDebugUnitTest --tests "com.metrolist.music.podcast.PodcastParsingTest"

# Signed release, requires the private local signing configuration
.\gradlew.bat :app:assembleFossRelease
```

Release signing uses a private keystore that must never be committed. GitHub Actions signing uses repository Secrets and the same long-term certificate so installed releases remain upgrade-compatible.

### Releases and verification

Downloads are published on the [Releases page](https://github.com/RizkLee/MetroVerse/releases). Verify the attached SHA-256 file and APK signing certificate before installation.

MetroVerse does not automatically install updates. A successful build also does not replace device testing for feed parsing, network playback, resume state, downloads, process restoration, and responsive layouts.

### Current scope

- Podcast refresh is foreground-driven; periodic WorkManager refresh is not included yet.
- OPML, private-feed authentication, chapters, and transcripts are not included yet.
- DRM, login-protected, expiring, or unsupported media URLs may fail.
- FOSS builds do not include Google Cast; the GMS flavor provides Cast support.
- The inherited codebase still contains lint debt and several unrelated baseline test failures.

### Contributing

Issues and focused pull requests are welcome. Do not include account cookies, private feed URLs, API credentials, signing material, or personal listening data in reports.

MetroVerse is independently developed and remains learning-oriented, but published changes are reviewed, built, signed, and documented as a maintained application rather than a disposable experiment. No commercial support or response-time guarantee is provided.

### License

MetroVerse is distributed under the [GNU General Public License v3.0](LICENSE). Distributors of modified APKs must preserve attribution and satisfy GPL source-availability requirements.

---

<a id="简体中文"></a>

<p align="right"><a href="#english">Back to English</a></p>

## 简体中文

MetroVerse 是一个独立维护的 Android 音频客户端，将 YouTube Music 与公开 RSS 播客整合到同一套资料库、搜索、队列、播放器、通知、缓存和下载系统中。

应用延续 Metrolist 熟悉的 Material 3 交互方式，并原生加入播客发现、订阅、直链播放、断点续播和播客专用控制。Apple Podcasts 仅用于发现公开订阅源，不需要 Apple 账号。

### 主要能力

- YouTube Music 浏览、搜索、歌单、资料库、队列、后台播放、歌词和本地历史。
- 手机、平板和横屏布局中的独立 Podcast 主入口。
- Apple Podcasts 榜单、地区切换、搜索和按类别发现播客。
- RSS/Atom 地址导入、订阅、手动刷新、分集封面、简介和发布日期。
- 音乐与播客共用 Media3 播放器、通知、Android Auto、缓存、下载和持久队列。
- 播客断点续播、前后 10 秒、倍速播放和睡眠计时渐弱暂停。
- 明确区分来源，RSS 分集不会误入 YouTube 电台、歌词、Last.fm 或同步路径。
- MetroVerse 播客流程维护英文和简体中文文案。

### 项目来源

MetroVerse 建立在两个 GPL-3.0 项目的工作之上：

- [Metrolist](https://github.com/MetrolistGroup/Metrolist) 提供音乐架构、Jetpack Compose 界面基础、播放器、队列、下载、数据库及大部分继承功能。
- [Podium](https://github.com/aimok04/podium) 是 Apple Podcasts 发现、RSS 解析、订阅和播客分类浏览的 GPL-3.0 参考实现。
- MetroVerse 将这些播客思路整合进 Metrolist 的既有架构，没有嵌入第二个 Podium App，也没有建立平行播放器。

MetroVerse 不是 Metrolist 或 Podium 的官方版本，与 Apple、Google 或 YouTube 没有隶属关系。原项目版权、源码头和贡献历史均保留，详见 [NOTICE.md](NOTICE.md) 与 [LICENSE](LICENSE)。

### 包信息

```text
应用名称：      MetroVerse
正式包名：      com.rizklee.metroverse
调试包名：      com.rizklee.metroverse.debug
当前版本：      0.3.0
最低系统：      Android 8.0 / API 26
目标 API：      36
```

内部 Kotlin namespace 继续使用 `com.metrolist.music` 以保持继承代码兼容；安装到设备上的应用使用独立 MetroVerse 包名。

### 构建

环境要求：JDK 21、Android SDK Platform 37 和 Platform Tools。

```powershell
# 调试 APK
.\gradlew.bat :app:assembleFossDebug

# 播客专项测试
.\gradlew.bat :app:testFossDebugUnitTest --tests "com.metrolist.music.podcast.PodcastParsingTest"

# 签名正式版，需要本机私有签名配置
.\gradlew.bat :app:assembleFossRelease
```

正式签名 keystore 不得提交到仓库。GitHub Actions 必须使用同一张长期证书进行签名，才能让已安装的正式版继续覆盖升级。

### 下载与校验

APK 发布在 [Releases 页面](https://github.com/RizkLee/MetroVerse/releases)。安装前应核对随附 SHA-256 文件和 APK 签名证书。

MetroVerse 不会自动安装更新。构建通过也不能替代真机上的 RSS 解析、网络播放、断点续播、下载、进程恢复和横竖屏验收。

### 当前范围

- 播客刷新目前由前台页面和手动下拉触发，尚未加入 WorkManager 周期刷新。
- 尚未加入 OPML、私人订阅认证、章节和转录文本。
- DRM、登录保护、过期或设备不支持的媒体地址可能无法播放。
- FOSS 构建不包含 Google Cast，GMS flavor 才包含 Cast。
- 继承代码仍有 lint 债务和少量与本项目改动无关的基线测试失败。

### 参与开发

欢迎提交清晰、范围明确的 Issue 和 Pull Request。请勿在报告中附带账号 Cookie、私人订阅地址、API 凭据、签名材料或个人收听数据。

MetroVerse 仍带有独立学习与研究性质，但公开改动会按持续维护的应用完成审查、构建、签名和说明，而不是一次性实验。项目不提供商业支持或固定响应时限。

### 许可证

MetroVerse 使用 [GNU GPL v3.0](LICENSE)。分发修改后的 APK 时，必须保留来源归属，并履行 GPL 对应源码提供义务。

### 免责声明

使用者应自行遵守所访问服务和播客订阅源的条款。MetroVerse 不提供任何担保，也不保证服务可用性、数据保存、持续兼容性或账号安全。
