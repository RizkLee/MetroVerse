# Changelog

All notable MetroVerse-specific changes are recorded here. Upstream Metrolist history remains available in Git history.

## 0.2.0 - 2026

### Added

- Multi-row Apple Podcasts discovery with incremental loading up to the storefront chart limit.
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

### Fixed

- Fixed `PARSING_CONTAINER_UNSUPPORTED (3003)` for standard foreign RSS podcast audio.
- Fixed coroutine cancellation being shown as `StandaloneCoroutine was cancelled` after region changes or navigation.
- Fixed podcast searches appearing in YouTube Music history.
- Fixed subscribed, liked, and downloaded podcast episodes being absent from Library search.
- Fixed RSS liked episodes opening the YouTube `Episodes for later` collection.
- Removed the resize animation from podcast seek buttons to avoid a visible hitch while seeking.

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
