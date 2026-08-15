# Changelog

All notable MetroVerse-specific changes are recorded here. Upstream Metrolist history remains available in Git history.

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
