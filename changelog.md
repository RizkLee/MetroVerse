# Changelog

All notable MetroVerse-specific changes are recorded here. Upstream Metrolist history remains available in Git history.

## 0.5.1 - 2026

### Fixed

- Podcast home now shows the first Apple Podcasts discovery load in the Discover section instead of the top pull-to-refresh indicator.
- Podcast category pages now reserve the top indicator for manual refreshes after category content is available.

## 0.5.0 - 2026

### Added

- Added podcast interface translations for German, Spanish, French, Italian, Japanese, Korean, Brazilian Portuguese, Russian, and Traditional Chinese.
- Added four current app screenshots and direct FOSS/GMS/checksum download links to the README.

### Changed

- Restored fixed default icons for the Liked, Downloaded, and Uploaded Library collections while retaining live item counts.
- Library pull-to-refresh now stays quiet after success and only reports offline, signed-out, or failed results.
- Retained only current English Fastlane listing metadata because GitHub Releases do not consume the inherited store-localization folders.

### Fixed

- Fixed duplicate saved YouTube Music playlists by querying existing rows directly by browse ID and restoring missing bookmark state during synchronization.
- Improved duplicate cleanup to preserve the row with usable artwork and more complete song metadata.
- Fixed official playlist and other YouTube artwork failures caused by malformed Google thumbnail resize URLs.
- Removed the second loading indicator shown during the initial load of a podcast category.

## 0.4.0 - 2026

### Added

- Automatic GitHub Release checks with an explicit user confirmation before downloading and opening Android's installer.
- Update verification for SHA-256, package name, version code, matching FOSS/GMS flavor, and the installed signing certificate.
- Clear Library refresh results for completed, offline, signed-out, and failed states.

### Changed

- Reduced published variants to FOSS and GMS; removed the redundant Izzy flavor and source set.
- Renamed RSS podcast subscription actions to Add to library / Remove from library, matching YouTube podcast details and mini-player icons.
- Kept the full-player Play/Pause button labels in English in every app language.
- Added a dedicated no-buffer MetroVerse artwork resource for About and README while retaining the padded launcher artwork.

### Fixed

- Added extra bottom clearance on Podcast and Storage so their final content stays above the new mini player.
- Fixed Liked, Offline, and Uploaded automatic Library collections always using placeholder covers instead of their songs' artwork.
- Made Library pull-to-refresh report when online synchronization could not run because the device is offline or YouTube Music is signed out.
- Fixed the updater's previous inability to discover pre-releases or recognize versioned MetroVerse APK filenames.

## 0.3.0 - 2026

### Added

- Complete subscribed podcast show results in Library search, with a dedicated Podcasts filter.
- Explicit Listen Together server selection on the main Listen Together page, including the optional Metrolist official endpoint and custom URLs.
- Centered loading feedback while opening Apple Podcast discovery and category results.

### Changed

- Restored Listen Together in the top bar as the fresh-install default.
- Left the Listen Together server URL empty until the user selects or enters one.
- Matched Podcast section headings to Home section typography and spacing.
- Restyled YouTube Music's New Episodes auto-playlist to match Liked and Downloaded podcast collections.
- Aligned podcast and collection detail headers with Album detail top spacing.

### Fixed

- Fixed RSS and YouTube podcast subscription controls in the mini player so they update the correct database entity, synchronize where applicable, and reflect current state.
- Fixed pull-to-refresh indicators being obscured by the top app bar or disappearing too quickly to confirm Library refresh.
- Fixed scrollable content overlapping the new mini player by including its bottom spacing in shared player-aware insets.
- Fixed podcast search and Library search results omitting player-aware bottom padding.

## 0.2.0 - 2026

### Added

- Two-row, horizontally scrolling Apple Podcasts discovery matching Home section behavior.
- Apple Podcasts Categories with a complete genre index and per-category charts.
- Library-wide pull-to-refresh across every Library tab.
- Approximately 20-second Sleep Timer volume fade before pause.
- Bilingual English and Simplified Chinese README with in-page language navigation.
- Editable search field on Apple Podcasts result pages.
- Source-aware YouTube Music and podcast search history.
- Liked and Downloaded podcast episode collections in Library.
- Podium-inspired playback-speed and sleep-timer bottom sheets.
- Common podcast container support through Media3 default extractors, including MP3, AAC/ADTS, Ogg, FLAC, WAV, and MP4.

### Changed

- Replaced the Podcast icon with the filled Material Symbols Podcasts icon.
- Replaced the launcher and in-app brand artwork with the fixed MetroVerse logo and removed the Dynamic icon colors setting.
- Moved Podcast before Search in primary navigation and Podcasts to second position in Library.
- Reduced podcast seek controls from 30 seconds to 10 seconds in the player, notification, and MediaSession.
- Limited Latest episodes on the Podcast page to five items.
- Consolidated Podcast actions into one compact row and moved Apple Podcasts attribution to About.
- Renamed the online search source to YouTube Music and its podcast result filter to YouTube Podcasts.
- Renamed song cache settings to audio cache and applied the cache toggle consistently to music and podcast streams.
- Changed fresh-install defaults to Blur player background, standard refresh rate, hidden Listen Together top-bar action, and Big grid cells.
- Restyled podcast Liked and Downloaded collections to match automatic Playlists, including collection artwork and playback actions.

### Fixed

- Fixed `PARSING_CONTAINER_UNSUPPORTED (3003)` for standard foreign RSS podcast audio.
- Fixed coroutine cancellation being shown as `StandaloneCoroutine was cancelled` after region changes or navigation.
- Fixed podcast searches appearing in YouTube Music history.
- Fixed subscribed, liked, and downloaded podcast episodes being absent from Library search.
- Fixed RSS liked episodes opening the YouTube `Episodes for later` collection.
- Restored the same responsive seek and play/pause button animations used by the music player.
- Fixed Library pull-to-refresh being unavailable outside individual child screens.
- Fixed Podcast storefront changes retaining stale Discover content while the new region loaded.
- Matched the Podcast results search field to the YouTube Music result field.
- Fixed GitHub Actions debug builds failing when the runner had no default debug keystore.

### Upgrade note

Room schema 40 adds a search-history source. Version 0.1 history had no source information, so ambiguous legacy rows are hidden during migration; new YouTube Music and podcast searches are stored separately.

## 0.1.0 - 2026

### Added

- MetroVerse application identity and independent Android application ID.
- Dedicated Podcast navigation destination for phone and large-screen layouts.
- Apple Podcasts search, charts, lookup, and configurable storefront region.
- RSS/Atom feed import, subscriptions, manual refresh, and episode discovery.
- RSS episode artwork, descriptions, publication dates, stable IDs, and sharing.
- Shared Media3 playback, queue persistence, cache, downloads, notifications, Cast URL support, and episode resume.
- Podcast-aware 30-second rewind/forward controls and source-specific menus.
- English and Simplified Chinese text for MetroVerse and podcast features.
- User, architecture, release-signing, testing, and installation documentation.

### Changed

- Disabled all automatic update checks and update notifications.
- Settings, About, and changelog links now point to `Rizklee/MetroVerse`.
- Removed upstream donation and community promotion from the app and README.
- Reworked About and attribution information to explain the relationship with Metrolist and Podium.

### Known limitations

- No scheduled background RSS refresh, OPML import/export, podcast chapters, transcripts, or private-feed credential storage.
- Real-device podcast workflow testing is still required for each release candidate.
- The inherited project has existing lint findings and unrelated baseline unit-test failures.
