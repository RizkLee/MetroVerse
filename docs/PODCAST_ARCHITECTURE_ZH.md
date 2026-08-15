# MetroVerse 播客整合架构与后续开发指南

## 1. 设计目标

本次整合采用“一个资料库、一个播放器、一个导航体系”的方式：

- Podium 只作为 Apple Podcasts、RSS 解析和订阅流程的实现参考。
- 不嵌入 Podium Activity、Material 3 Expressive 页面或独立数据库。
- RSS 单集进入 MetroVerse 继承的 `SongEntity`、队列、播放器、通知、缓存和下载链路。
- 原有 YouTube Music 播客继续工作，并与 RSS 来源明确区分。
- 手机 Bottom Navigation 和大屏 Navigation Rail 使用同一个 `Screens.Podcast`。

设计约束和产品决策也记录在项目根目录的 `PRODUCT.md`。

## 2. 功能数据流

### 2.1 Apple Podcasts 发现

```text
PodcastScreen / PodcastSearchResultScreen
    -> PodcastViewModel / PodcastSearchViewModel
    -> PodcastRepository.search() 或 topPodcasts()
    -> iTunes Search / RSS chart JSON
    -> PodcastDiscoverItem
    -> 点击结果
    -> iTunes lookup 获取 feedUrl
    -> PodcastRepository.fetchFeed()
```

Apple API 不提供播放流。`feedUrl` 才是进入本地资料库和播放器的边界。

使用的公开端点：

```text
https://itunes.apple.com/search
https://itunes.apple.com/lookup
https://itunes.apple.com/{country}/rss/toppodcasts/...
```

Apple storefront 来自持久化的 `PodcastRegionKey`。`PodcastRegions.kt` 将系统国家规范化到受支持列表，首页和搜索 ViewModel 在地区变化时取消旧请求并重新加载；`CancellationException` 会继续向上抛出，不会再被转换为 `StandaloneCoroutine was cancelled` 用户错误。这个设置只影响 Apple 发现，不修改任何 RSS 订阅。Discover 首次请求 24 项，滚动接近页面末尾后每次提高 24 项 limit，最多请求 Apple 榜单的 200 项。

### 2.2 RSS 导入

```text
RSS URL
    -> normalizeFeedUrl()
    -> Ktor CIO 下载 XML
    -> rssparser 6.1.4 解析
    -> PodcastEntity
    -> 每个带 audio/video enclosure 的 item
    -> MediaMetadata(isEpisode = true, mediaUrl = enclosure)
    -> MusicDatabase.insert/update
    -> SongEntity + ArtistEntity + SongArtistMap
```

RSS 描述通过 Jsoup 转成可显示的纯文本。单次最多导入 1000 个单集，避免异常大订阅源无限占用内存和数据库。RSS/Atom 没有统一的历史分集分页参数，详情页使用 `LazyColumn` 避免一次组合全部行，但首次导入仍需下载并解析服务器返回的完整 XML；不能把客户端分批显示误称为网络分页。

### 2.3 播放

```text
RssPodcastScreen
    -> Song.toMediaItem()
    -> ListQueue
    -> MusicService
    -> ResolvingDataSource
    -> SongEntity.mediaUrl
    -> CacheDataSource + OkHttpDataSource
    -> ExoPlayer
```

`MediaItem` 使用：

- `mediaId`：稳定的 RSS 单集 ID。
- `uri`：RSS enclosure URL。
- `customCacheKey`：稳定单集 ID，避免 URL 跳转或查询参数变化破坏缓存键。
- `tag`：可序列化的 MetroVerse `MediaMetadata`，包含 `mediaUrl`。
- Media3 metadata type：`MEDIA_TYPE_PODCAST_EPISODE`。

`MusicService.createDataSourceFactory()` 在访问 YouTube 播放解析前查询 `SongEntity.mediaUrl`。存在直链时添加 MetroVerse User-Agent 与音频 Accept 请求头，直接返回 URI，并继续经过现有 CacheDataSource。媒体源使用 `DefaultExtractorsFactory`，覆盖 MP3、AAC/ADTS、Ogg、FLAC、WAV、MP4、Matroska 和 MPEG-TS 等 Media3 默认容器；0.1.0 只注册 Matroska/Fragmented MP4/MP4，是 `PARSING_CONTAINER_UNSUPPORTED (3003)` 的根因。

### 2.4 下载

```text
单集菜单
    -> DownloadRequest(mediaId, customCacheKey)
    -> ExoDownloadService
    -> DownloadUtil ResolvingDataSource
    -> SongEntity.mediaUrl
    -> Media3 DownloadManager / downloadCache
```

因此在线播放与下载使用同一稳定 ID，缓存层复用从 Metrolist 继承的模块。Storage 的音频缓存开关在每次创建数据流时读取：关闭后仍可读取明确下载的内容，但不会写播放器缓存；大小限制、清理缓存和下载清理同时作用于音乐与播客。

### 2.5 断点续播

复用 `SongEntity.isEpisode` 和 `playbackPosition`：

- 媒体切换时保存上一个单集进度。
- 播放期间周期保存。
- `MusicService.onDestroy()` 保存当前进度。
- 重新播放时恢复进度。
- 接近结尾时按原有阈值从头开始。

队列持久化使用可序列化的 `MediaMetadata`。专项测试验证 RSS URL 等字段在序列化恢复后不会丢失。

## 3. 数据库设计

Room 数据库先从 38 升到 39，再在 0.2.0 升到 40。版本 40 为 `search_history` 添加 `source`，唯一索引改为 `(query, source)`，使同一查询可以分别存在于 YouTube Music 和 Podcasts 历史中。0.1.0 的记录没有来源信息，`Migration39To40` 将其标记为 `LEGACY` 并从两个来源界面隐藏，避免继续错误归类。导出的最新 schema 位于：

```text
app/schemas/com.metrolist.music.db.InternalDatabase/40.json
```

### 3.1 PodcastEntity 新字段

- `feedUrl`：RSS/Atom 地址；为空表示原有 YouTube Music 播客。
- `description`：节目简介。
- `websiteUrl`：节目网站。
- `language`：预留语言字段。
- `bookmarkedAt`：统一表示订阅/保存时间。
- `lastUpdateTime`：最后成功写入或更新的时间。

来源判断规则必须保持简单明确：

```kotlin
val isRssPodcast = podcast.feedUrl != null
```

不要依靠 ID 前缀猜测来源，前缀只用于稳定 ID 和调试可读性。

### 3.2 SongEntity 新字段

- `mediaUrl`：RSS enclosure 直链；为空时走原有 YouTube/本地媒体分支。
- `shareUrl`：单集网页；为空时分享 `mediaUrl`。
- `description`：单集简介。
- `date`：复用现有日期字段保存 RSS 发布时间。

原有字段继续承担播客语义：

- `isEpisode = true`
- `albumId = PodcastEntity.id`
- `albumName = PodcastEntity.title`
- `inLibrary`：稍后收听收藏状态。
- `isDownloaded/dateDownload`：下载状态。
- `playbackPosition`：断点位置。

### 3.3 稳定 ID

`PodcastParsing.kt` 生成 SHA-256 稳定 ID：

- 节目：规范化 `feedUrl` 的哈希。
- 单集：优先使用 `feedUrl + guid`。
- GUID 缺失时使用 `feedUrl + mediaUrl`。

这样不同订阅源中的相同 GUID 不会碰撞；媒体地址变化时，有 GUID 的单集仍保持同一 ID。

## 4. 来源隔离规则

RSS 单集必须避免触发只适用于 YouTube ID 的逻辑。当前统一判断是：

```kotlin
val isDirectPodcast = metadata.isEpisode && metadata.mediaUrl != null
```

已经隔离的行为：

- YouTube `playerResponseForPlayback()` 和签名解密。
- YouTube 播放历史注册。
- YouTube 收藏/资料库同步。
- YouTube 相似电台和重新抓取。
- 歌词获取。
- Last.fm scrobble。
- 音质切换导致的 YouTube 流重取。
- 交叉淡入淡出。
- Listen Together 入口。
- 伪造的 `music.youtube.com/watch` 分享链接。

如果未来增加新的 YouTube 专用菜单或后台任务，必须加入同样的来源判断。

## 5. UI 与导航

主要路由：

```text
podcast
podcast_search/{query}
rss_podcast/{podcastId}
podcast_collection/{liked|downloaded}
```

顶级入口：

- `Screens.Podcast`
- `NavigationTab.PODCAST`
- 手机 Bottom Navigation
- 平板/横屏 Navigation Rail
- 默认打开页面设置

搜索来源：

```kotlin
SearchSource.ONLINE   // UI label: YouTube Music
SearchSource.PODCAST  // UI label: Podcasts
SearchSource.LOCAL    // UI label: Library
```

新增界面全部复用 MetroVerse 从 Metrolist 继承的 TopAppBar、NavigationIconButton、ChipsRow、AsyncImage、SongListItem、BottomSheet 和主题色，不引入 Podium 的页面或第二套设计 token。

## 6. 核心文件索引

### 数据和网络

- `podcast/PodcastModels.kt`：Apple JSON 模型和发现模型。
- `podcast/PodcastParsing.kt`：URL、日期、时长和稳定 ID。
- `podcast/PodcastRepository.kt`：Apple API、RSS 下载、解析、订阅和刷新。
- `podcast/PodcastRegions.kt`：Apple storefront 支持列表和地区规范化。
- `db/entities/PodcastEntity.kt`：节目实体。
- `db/entities/SongEntity.kt`：RSS 单集直链元数据。
- `db/DatabaseDao.kt`：RSS 节目和单集查询。
- `db/MusicDatabase.kt`：Room 40、搜索来源迁移与 schema。

### UI 和状态

- `viewmodels/PodcastViewModels.kt`
- `ui/screens/podcast/PodcastScreen.kt`
- `ui/screens/podcast/PodcastSearchResultScreen.kt`
- `ui/screens/podcast/PodcastRegionSelector.kt`
- `ui/screens/podcast/RssPodcastScreen.kt`
- `ui/screens/search/SearchScreen.kt`
- `ui/screens/library/LibraryPodcastsScreen.kt`
- `ui/screens/library/PodcastCollectionScreen.kt`
- `ui/screens/NavigationBuilder.kt`
- `ui/screens/Screens.kt`

### 播放和菜单

- `extensions/MediaItemExt.kt`
- `models/MediaMetadata.kt`
- `playback/MusicService.kt`
- `playback/DownloadUtil.kt`
- `playback/PlayerConnection.kt`
- `ui/player/Player.kt`
- `ui/player/PlaybackOptionBottomSheets.kt`
- `ui/menu/PlayerMenu.kt`
- `ui/menu/QueueMenu.kt`
- `ui/menu/SongMenu.kt`

### 测试和资源

- `app/src/test/kotlin/com/metrolist/music/podcast/PodcastParsingTest.kt`
- `app/schemas/com.metrolist.music.db.InternalDatabase/40.json`
- `res/drawable/podcast.xml`
- `res/drawable/replay_10.xml`
- `res/drawable/forward_10.xml`
- `res/values/metrolist_strings.xml`
- `res/values-zh-rCN/metrolist_strings.xml`

## 7. 后续开发建议

### 7.1 后台刷新

当前刷新发生在打开 Podcast 首页或手动点击刷新时。如需定时刷新：

1. 添加 AndroidX WorkManager 依赖。
2. 创建 Hilt Worker 调用 `PodcastRepository.refreshSubscribed()`。
3. 使用唯一 `PeriodicWorkRequest`，建议 12 至 24 小时并要求联网。
4. 对每个失败订阅源独立记录，不能因为一个 feed 失败而终止全部更新。
5. 只在发现新单集时发送通知，并提供通知总开关。
6. 增加退避策略，避免服务器故障时频繁重试。

### 7.2 OPML 导入导出

- 使用 XML parser，不要用字符串切割。
- 导入时规范化并按 `feedUrl` 去重。
- 批量导入要限制并发，避免同时打开大量网络连接。
- 导出只包含用户已订阅且有 `feedUrl` 的 RSS 节目。

### 7.3 播客章节和封面

可增加 chapter URL 或 Podcasting 2.0 namespace 解析。章节应关联单集 ID，不要写入歌词表。播放器章节跳转应继续调用 `PlayerConnection.seekTo()`。

### 7.4 大型资料库性能

当前 feed 单次最多 1000 集。若要支持超大型节目：

- DAO 改为 Paging 3。
- 列表按发布时间分页。
- 刷新时优先处理新单集，在遇到已知 GUID 后停止。
- 为 `PodcastEntity.feedUrl` 和 `SongEntity.albumId/date` 评估索引。
- 避免在 Compose 层排序完整列表。

### 7.5 私人订阅

私人 feed 涉及凭据。实现时：

- 凭据放 Android Keystore/EncryptedSharedPreferences，不写数据库明文 URL。
- 日志不得输出带 token 的完整 URL。
- 分享时移除查询参数中的密钥。
- 下载任务需要可安全恢复的认证头。
- 明确禁止把认证 URL同步到 Listen Together 或外部服务。

## 8. 测试策略

### 8.1 自动测试

专项命令：

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
.\gradlew.bat :app:testFossDebugUnitTest --tests "com.metrolist.music.podcast.PodcastParsingTest"
```

`PodcastParsingTest` 覆盖：

- URL scheme 白名单。
- 节目和单集 ID 稳定性。
- GUID 缺失回退。
- 常见 RSS 时长格式。
- RFC 1123 与 ISO 日期。
- RSS 元数据写入 `SongEntity`。
- 持久队列 Java 序列化。

建议后续补充：

- MockWebServer 下的 Apple search/lookup JSON 测试。
- 多种真实 RSS/Atom fixture。
- Room 38 到 39 以及 39 到 40 的 instrumentation migration test。
- DownloadUtil 直链 resolver 测试。
- Compose 导航和订阅状态测试。
- MediaSession 播客按钮测试。

### 8.2 当前验证结果

已验证：

- `:app:compileFossDebugKotlin` 通过。
- 播客专项单元测试通过。
- `:app:lintFossDebug` 完成；0.2.0 报告为 65 errors、297 warnings，65 个 error 是继承代码的基线债务，播客新增文件没有 lint error。
- `:app:assembleFossDebug` 通过。
- 调试 APK 已生成。
- R8 压缩后的 `:app:assembleFossRelease` 通过，正式 APK 已使用本机 MetroVerse RSA 4096 release key 签名，并通过 APK v2 签名校验。
- Debug APK 包名为 `com.rizklee.metroverse.debug`，Release APK 包名为 `com.rizklee.metroverse`，最低 API 26，目标 API 36。
- 全量单元测试执行完成，但原有 `YouTubeUtilsTest` 有 4 个与图片 URL 改写相关的失败。

产物：

```text
app/build/outputs/apk/foss/debug/app-foss-debug.apk
app/build/outputs/apk/foss/release/app-foss-release.apk
```

0.2.0 最终产物为 49,166,219 bytes（Debug）和 24,165,051 bytes（Release）。Release SHA-256 为 `bca51bbf0128d59d4bcd8efb1223812945076772f8d7ee042ee33bbc25620dfa`；源码或构建环境变化后必须重新计算，不能把这个值当作后续版本固定校验值。

设备手工矩阵建议至少包含：

- API 26 手机。
- API 35/36 手机。
- 平板或横屏。
- Wi-Fi、移动网络和断网。
- 在线流、完整下载、下载中断后恢复。
- App 进程被系统终止后的队列和进度恢复。

## 9. Git 检查点与回退

MetroVerse 默认分支：

```text
main
```

分阶段提交：

```text
14cf578a9 feat(podcast): add RSS discovery and persistence layer
696f30444 feat(podcast): add navigation discovery and subscription UI
eb1962b53 feat(podcast): route RSS episodes through shared playback
fa6aa0945 docs(podcast): add user build and architecture guides
ee8bb2ba2 fix(podcast): refresh subscriptions and playback sources
d655337a4 docs(podcast): record integration checkpoints
fff650eea feat(brand): establish MetroVerse app identity
8c50b9ff7 feat(brand): replace upstream project and update surfaces
6964c7fee docs(brand): reset MetroVerse changelog
e43696442 feat(podcast): add regions and complete player adaptation
88a4e2e33 fix(brand): remove stale runtime upstream links
ce8cc1135 build(release): add private signing workflow and guide
```

查看提交：

```powershell
git log --oneline --decorate -10
```

查看某个阶段而不改动当前分支：

```powershell
git show --stat 14cf578a9
git show --stat 696f30444
git show --stat eb1962b53
```

推荐使用 `git revert` 创建可追踪的反向提交，而不是 `reset --hard`：

```powershell
git revert eb1962b53
```

如果需要依次撤销播放器和 UI 两个阶段：

```powershell
git revert eb1962b53
git revert 696f30444
```

仅临时查看旧阶段可新建分支：

```powershell
git switch -c inspect-podcast-data 14cf578a9
```

返回 MetroVerse 主分支：

```powershell
git switch main
```

不要把本机 `lastfm-api-key.txt`、`local.properties`、keystore 或密码加入提交。执行 `git status --short` 时，`lastfm-api-key.txt` 保持未跟踪是预期状态。

## 10. 修改数据库时的规则

未来修改 `@Entity` 后必须：

1. 增加 `MusicDatabase` version。
2. 添加 AutoMigration 或手写 Migration。
3. 重新编译以导出 schema JSON。
4. 检查新 schema 是否进入 Git。
5. 增加从上一正式版本迁移的测试。
6. 在保留真实旧数据的设备上手工升级验证。

不要使用 `fallbackToDestructiveMigration()` 掩盖迁移问题，因为它会删除用户的音乐资料库、订阅和播放历史。

## 11. 安全与隐私边界

- 只接受 `http` 和 `https` feed URL，拒绝 `file://` 等本地 scheme。
- Apple 搜索词发送到 Apple；RSS URL 和请求发送到节目服务器。
- RSS 单集不会发送到 YouTube、Last.fm 或歌词提供商。
- 普通 HTTP feed 受项目现有 cleartext 网络配置支持，但应优先使用 HTTPS。
- 日志和错误界面不应显示私人订阅 token。
- 新增网络重定向、认证或导入功能时必须重新检查 SSRF、凭据泄漏和恶意 XML 风险。

## 12. 许可证

MetroVerse、上游 Metrolist 和参考项目 Podium 都采用 GPL-3.0。MetroVerse 继续受 GPL-3.0 约束，并在 `NOTICE.md`、Git 历史和继承的源码头中保留上游归属。不要把第三方不兼容许可证的代码或资源直接复制进项目。
