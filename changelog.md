# Changelog

All notable MetroVerse-specific changes are recorded here. Upstream Metrolist history remains available in Git history.

## 1.2.0 - 2026-10-26

- Add Mandarin Chinese, Arabic, Tagalog, and Vietnamese on-device subtitles for RSS podcasts.
- Offer Tiny or Small recognition models for English, German, Spanish, and Japanese through one settings entry per language. Keep existing models and completed same-language subtitles when switching.
- On Android 11 or newer, show available system exit diagnostics after interrupted recognition. Suggest Tiny when Android confirms memory pressure while Small was in use.
- Fix subtitle position flicker while recognition fills earlier sections of an episode.
- Let the playing RSS episode buffer further ahead within the audio memory budget.
- Fix opening Apple News Today and playing episodes from its official public RSS feed.

## 1.1.1 - 2026-10-17

- Fix podcast repeat-one playback restarting near the end instead of from the beginning.
- Add an optional local queue mode that stops after each song or episode. Enable it in Player & audio settings before cycling to it with the Shuffle button; it is off by default.
- Prefer removing cached spans for non-playing podcasts before non-playing music. Protect the current item, allowing the streaming cache to temporarily exceed its limit. Downloads are unaffected.

## 1.1.0 - 2026-10-14

### Added

- English, German, Spanish, and Japanese on-device recognition for RSS podcasts, with episode language selection, channel defaults, and separate model management.
- DeepSeek, Z.ai, MiniMax, Kimi, SiliconFlow, and ModelScope translation presets, custom API URL completion, and direct custom model entry.
- Independent podcast translation target language and system prompt, plus a temporary translation switch for the current episode.
- Subtitle-menu actions to re-recognize the current episode or copy its original subtitles. Re-recognition replaces that episode’s saved subtitles, progress, and translations.

### Changed

- Increased nonurgent subtitle translation batches and dismiss translation feedback after six seconds.
- Refined recognition controls, language/model dialogs, model readiness and size labels, and subtitle timing adjustment.
- Moved model management into AI settings and removed folder import from the model-download dialog.
- Renamed the AI settings entry to cover lyrics and podcast subtitles, and clarified podcast applicability of playback settings.
- Display Mainland China in the podcast region picker.

### Fixed

- Restore compatible saved subtitles before feed discovery and model checks when reopening unfinished recognition.
- Retry recoverable recognition failures once while preserving completed subtitles; stop obsolete work when recognition is cancelled or the episode changes.
- Keep subtitle checkpoints tied to their recognition language, model, and audio source.
- Preserve episode identity and direct RSS playback for podcast tiles in Speed dial.
- Pause the local player at the end of each item when autoplay is disabled, including when sleep-timer state changes.
- Clear a previous track’s normalization gain for RSS playback without format metadata.
- Preserve the selected App language, including regional Chinese, when initializing the separate podcast translation target.

## 1.0.0 - 2026-10-11

### Added

- Podcast subtitles from RSS transcript files, with timed highlighting, tap-to-seek, timing adjustment, fullscreen viewing, and selected-line sharing.
- Experimental on-device English subtitle generation for RSS podcasts with Moonshine Small Streaming, including model download, local import, and deletion.
- On-device subtitles prioritize the listening position, restart slightly ahead after settled seeks, and fill missing sections afterward.
- Bilingual AI podcast subtitles, with current-position translation and batched prefetching through the configured translation service.
- A separate storage limit and cleanup controls for podcast subtitles, lyrics, and translations, with protection for manually edited lyrics and active content.

### Changed

- Ported selected Metrolist 13.7.0 improvements and adopted InnerTubeX for YouTube playback, stream fallback, and download resolution.
- Reordered player controls; podcasts show playback speed in place of repeat. Adjusted lyric and subtitle icons within their buttons.
- Audio quality now defaults to High while preserving saved choices.
- Enlarged the subtitle edge fades to keep text farther from the status bar and episode information.
- Cached music lists update directly when cached content changes.
- Moved podcast subtitle settings into AI lyrics and podcast subtitle translation settings; translated subtitles always display bilingually.
- Both player translation menus remain available before setup and open AI settings with the API key item brought into view and highlighted.
- Podcast playback opens on the cover view; subtitle generation explains the model, English-only support, and device requirements before download.
- Added Moonshine and ONNX Runtime attribution in About and made more room for long bilingual subtitles.
- Widened lyric-share images to fit longer song titles and updated the notification icon.
- Restored compact subtitle recognition progress and matched subtitle fullscreen controls to lyric controls.

### Fixed

- Word-timed lyrics stay on the current line during short gaps until the next line starts.
- YouTube Music podcast episodes no longer offer an on-device recognition action that cannot start; RSS subtitle generation remains available.
- Backward seeks and short gaps in line-timed lyrics no longer reset or stall lyric scrolling; realignment follows the current playback time.
- Account changes and cancelled Wrapped playback requests no longer prepare audio from an outdated request.
- YouTube download retries respect disabled stream sources and handle streams with incomplete size information.
- Lyrics can scroll back to the first line without excessive empty space after the last line. Lyric positioning and word highlighting remain aligned as layouts change.
- Restored a brief whole-line highlight animation without shifting individual letters or hyphenated words.
- Tapping lyrics or subtitles follows the selected line directly, without first returning to the old playback position.
- Interrupted subtitle generation preserves completed sentences and resumes from a safe recognition boundary; translations stay matched to their source sentences.
- Subtitle recognition keeps media timestamp gaps intact and seeks to saved progress when the audio source supports it; recovery stages are shown separately.
- Subtitle translation no longer refills every window with single-sentence requests, loses requests while busy, or repeatedly retries without a cooldown. Requests stop when cancelled.
- Podcast playback controls no longer alternate with music controls when playback progress is saved.
- Open RSS episodes saved for later remain intact during YouTube library synchronization.
- Corrected lyric versus subtitle translation labels and completed translations for the supported App languages.

## 0.5.8 - 2026-09-13

### Changed

- RSS podcast episode details no longer repeat the podcast name in the metadata row, keeping the publication date and duration visible.

### Fixed

- RSS podcast playback now uses the resolved stream duration instead of stale feed metadata when available.
- Podcast seek buttons and progress-bar seeking now keep the requested position while ExoPlayer applies the seek.

## 0.5.7 - 2026-09-06

### Added

- Added translations for the background lyrics loading setting in Simplified Chinese, Traditional Chinese, German, Spanish, French, Italian, Japanese, Korean, Brazilian Portuguese, and Russian.

### Changed

- RSS podcast episode rows now show two-line titles with larger artwork across podcast details, Continue listening, Latest episodes, and Liked/Downloaded collections.
- Podcast Continue listening now shows three episodes.
- RSS episode menus now show full titles and a three-line description preview with an expandable, scrollable full view.

### Fixed

- Aligned short Show more labels with the description heading while preserving a full touch target.

## 0.5.6 - 2026-08-29

### Added

- Added an optional setting to translate lyrics automatically when the lyrics view is open. It is off by default and does not run during background lyrics loading.
- Added 1.15x, 1.30x, 1.40x, and 1.60x podcast playback speeds.

### Changed

- Removed the 2.75x, 3.25x, 3.50x, and 3.75x podcast playback speeds.

### Fixed

- Songs now return to 1.0x speed after switching from a podcast played at a different speed.
- End-of-song, end-of-episode, and end-of-collection sleep timers now stop at the correct time without briefly playing the next item.
- Automatic AI lyrics translation now follows the current song and can be turned off for the current song without immediately turning itself back on.

## 0.5.5 - 2026

### Important

- Update immediately and do not continue using v0.5.4 or earlier because those versions can remove Open RSS podcast subscriptions during a Library refresh.
- Shows already removed by an earlier version must be added to the library again after updating.

### Changed

- Update notices now render Markdown headings, lists, links, compact tables, and important warnings.

### Fixed

- Open RSS podcast subscriptions no longer disappear after Library initialization or refresh.

## 0.5.4 - 2026

### Added

- Added optional background lyrics loading for the current and next song, enabled by default.

### Changed

- Update notifications now follow only GitHub's stable Latest Release and ignore pre-releases.

### Fixed

- End of queue now pauses Similar content only while active and restores it after cancellation or completion.
- Made the podcast End of episode sleep-timer action compact and responsive to translated text.

## 0.5.3 - 2026

### Added

- Added an End of queue sleep-timer mode for music collections and a Normal speed action to restore playback speed to 1.00x.
- Added a complete light adaptive launcher icon set, themed monochrome icon, fallback round icon, and notification icon.
- Added the maintainer profile image to About.

### Changed

- End of song/episode and End of queue modes no longer apply the final twenty-second sleep-timer fade; only direct timed countdowns fade out.
- End-based sleep timers temporarily disable repeat and shuffle, restoring both when completed or cancelled.
- Podcast playback omits the collection queue sleep-timer action.
- Removed personal-learning wording from About while preserving independent-project and upstream attribution notices.
- Launcher installation now consistently uses the light icon instead of selecting a color variant from install-time night mode.

### Fixed

- End of queue now stops at the original album/playlist boundary even when Similar content was already appended or finishes loading in flight.
- Playlist continuation extends the collection boundary, while album radio recommendations do not.
- Automatic timed sleep timers now honor Finish current item when timer expires.
- Preserved query parameters while resizing googleusercontent and ggpht artwork URLs.

## 0.5.2 - 2026

### Added

- Added compact Buy Me a Coffee actions to Settings, About, and the README.
- Added shared app-language defaults for AI lyrics translation and Romanization.

### Changed

- Moved the permanent Changelog entry from Settings into Updater and removed the Updater description from Settings.
- AI lyrics translation now defaults to the current effective app language for users without a saved target language.
- Romanization now defaults the current app language to off when it maps to a supported script; existing selections remain unchanged.
- Moved KuGou to the end of the default lyrics provider priority while preserving saved custom orders.

### Fixed

- Restored square CDN crops for artist artwork so Library and Search render circular avatars without horizontal stretching or clipping.
- Preserved the ggpht, ytimg, and aspect-ratio fixes used by playlist and other YouTube artwork.

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
