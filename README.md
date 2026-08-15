# MetroVerse

MetroVerse is a personal Android learning project that combines a YouTube Music client experience with open RSS podcasts in one app.

> **Important:** this repository is for study, experimentation, and personal testing. It is not an official Metrolist or Podium release, it is not affiliated with Apple or YouTube, and it should not be treated as a production-supported application. Review the source, build it yourself, and test with non-critical data first.

中文说明：MetroVerse 是由 `@Rizklee` 维护的个人学习练手项目，目标是在同一个 Android App 中整合音乐与开放 RSS 播客。项目仍处于实验阶段，请谨慎尝试并自行承担使用风险。

## What it includes

### Music

- YouTube Music browsing, search, library, playlists, queue, and background playback inherited from Metrolist.
- Media3 player, notifications, Android Auto, caching, and offline downloads.
- Lyrics, audio controls, themes, local history, and the existing Metrolist settings system.

### Podcasts

- A dedicated Podcast destination on phone, tablet, and landscape navigation.
- Apple Podcasts search and charts for discovering public RSS feeds.
- Configurable Apple Podcasts storefront region.
- Direct RSS/Atom feed import, subscriptions, refresh, and episode lists.
- Episode artwork, descriptions, publication dates, sharing, saving, downloads, and resume position.
- Podcast-aware player controls with 10-second rewind and forward.
- The same queue, mini player, full player, notification, cache, and download engine used by music.
- Podcast-only behavior hides lyrics, Start Radio, Listen Together, View Artist, and other YouTube-specific actions where they do not apply.

Apple Podcasts is used only for public discovery. MetroVerse does not log in to Apple Podcasts or synchronize an Apple account.

## Current limitations

- No periodic background podcast refresh. Subscriptions refresh when the Podcast page opens or when refresh is requested.
- No OPML import/export, podcast chapters, transcripts, or private-feed credential manager yet.
- DRM, login-protected, expiring, or unsupported RSS audio may not play.
- RSS episodes are intentionally excluded from YouTube history, YouTube radio, lyrics lookup, Last.fm scrobbling, and Listen Together.
- The repository still inherits upstream lint debt and several unrelated baseline unit-test failures documented in the development guide.
- RSS feeds do not provide a standard episode pagination API. Episode pages use lazy rendering, but importing a feed still requires downloading and parsing its XML response.
- Only English and Simplified Chinese are maintained for MetroVerse-specific and podcast-specific text. Other inherited translations may be incomplete or refer to upstream terminology.

## Project lineage

MetroVerse is not a from-scratch application:

- [Metrolist](https://github.com/MetrolistGroup/Metrolist) provides the music architecture, Compose UI foundation, player, queue, download system, and most inherited functionality.
- [Podium](https://github.com/aimok04/podium) was studied as a GPL-3.0 reference for Apple Podcasts discovery, RSS parsing, subscription, and podcast update concepts.
- MetroVerse integrates those podcast concepts into Metrolist's existing architecture instead of embedding Podium as a second app.

Both upstream projects are GPL-3.0. Their copyright history remains in Git history and source headers. See [NOTICE.md](NOTICE.md) and [LICENSE](LICENSE).

## Package and version

```text
App name:       MetroVerse
Application ID: com.rizklee.metroverse
Debug ID:       com.rizklee.metroverse.debug
Current version: 0.2.0
Minimum Android: 8.0 / API 26
```

The Kotlin namespace remains `com.metrolist.music` to preserve compatibility with the inherited codebase. The installed Android package is the independent MetroVerse application ID shown above.

## Build quickly

Requirements:

- Android Studio with JDK 21 selected as the Gradle JDK.
- Android SDK Platform 37 and Platform Tools.

Debug APK:

```powershell
.\gradlew.bat :app:assembleFossDebug
```

Signed release APK requires a private keystore and local `keystore.properties`:

```powershell
.\gradlew.bat :app:assembleFossRelease
```

## Verification commands

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
.\gradlew.bat :app:testFossDebugUnitTest --tests "com.metrolist.music.podcast.PodcastParsingTest"
.\gradlew.bat :app:lintFossDebug
.\gradlew.bat :app:assembleFossDebug
```

Real podcast feeds vary substantially. A successful build does not replace testing search, RSS parsing, playback, resume, download, and process-restoration behavior on a real Android device.

## Updates

MetroVerse does not automatically check for or install updates. The app's Settings and About pages link to this repository's Releases page:

<https://github.com/Rizklee/MetroVerse/releases>

Only install APKs that you built yourself or that are published by the repository owner and whose signature you trust.

## Contributing

This is primarily a personal learning repository. Issues and pull requests may be used for study, but there is no response-time or release-support commitment. Do not send credentials, private RSS URLs, account cookies, or signing keys in issue reports.

## License

MetroVerse is distributed under the [GNU General Public License v3.0](LICENSE). If you distribute modified APKs, you must follow the GPL source-availability and license requirements and preserve upstream attribution.

## Disclaimer

Use MetroVerse at your own risk. You are responsible for complying with the terms of the services and podcast feeds you access. MetroVerse provides no warranty and no guarantee of availability, data preservation, compatibility, or account safety.
