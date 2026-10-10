# Working with MetroVerse as an AI agent

MetroVerse is an independently maintained Android music and podcast app based on Metrolist. It keeps Metrolist's Kotlin, Jetpack Compose, Material 3, Media3, Room, Hilt, download, and queue foundations while integrating Open RSS podcast discovery and playback. MetroVerse is not an official Metrolist release and is not affiliated with Apple, Google, or YouTube.

## Project basics

- App name: MetroVerse
- Application ID: `com.rizklee.metroverse` (`com.rizklee.metroverse.debug` for Debug builds)
- Inherited Kotlin namespace: `com.metrolist.music`
- Supported variants: FOSS (without Google Cast) and GMS (with Google Cast)
- Android baseline: minimum API 26, target API 36, compile SDK 37
- Primary stack: Kotlin, Jetpack Compose, Material 3, Media3, Room, Hilt, OkHttp, and WorkManager
- Podcast support: YouTube podcasts plus public RSS/Atom feeds, with Apple Podcasts used only for public discovery metadata
- License and lineage: GPL-3.0; preserve inherited source headers, attribution, contribution history, and MetroVerse's independent package identity

## Rules for working on the project

1. Always pull the latest changes from `main` before starting your work to minimize merge conflicts.
2. Commit names should be clear and follow the format: `type(scope): short description`. For example: `feat(ui): add dark mode support`. Including the scope is optional.
3. Add or edit App-owned strings in `Metrolist/app/src/main/res/values*/metrolist_strings.xml`, never in any `strings.xml` file. New user-visible text outside the Changelog and About screens must be localized in the default English resources plus Simplified Chinese (`values-zh-rCN`), Traditional Chinese (`values-zh-rTW`), German (`values-de`), Spanish (`values-es`), French (`values-fr`), Italian (`values-it`), Japanese (`values-ja`), Korean (`values-ko`), Brazilian Portuguese (`values-pt-rBR`), and Russian (`values-ru`). Do not add the new string to other locale files; those locales must fall back to English.
4. You are to follow best practices for Kotlin and Android development.
5. DO NOT EDIT THE APP'S DATABASE SCHEMA.

## AI-only guidelines

1. Do not change README or other Markdown documentation unless a human explicitly requests it. An explicitly authorized MetroVerse release must follow the documentation and metadata checklist below, including README and changelog updates.
2. Unless explicitly requested, you are not allowed to commit, push, or merge any changes to any branch. If you are explicitly requested and authorized to commit/push/merge, you have the right to do so; the responsibility then lies with the author who requested it.
   - You should absolutely NOT use any commands that would modify the git history, do force pushes (except for rebases on your own branch), or delete branches without explicit instructions from a human.
3. Always follow the guidelines and instructions provided by human contributors.
4. Ensure the absolutely highest code quality in all contributions, including proper formatting, clear variable naming, and comprehensive comments where necessary.
5. Comments should be added only for complex logic or non-obvious code. Avoid redundant comments that simply restate what the code does.
6. Prioritize performance, battery efficiency, and maintainability in all code contributions. Always consider the impact of your changes on the overall user experience and app performance.
7. If you have any doubts ask a human contributor. Never make assumptions about the requirements or implementation details without clarification.
8. If you do not test your changes using the instructions in the next section, you will be faced with reprimands from human contributors and may be asked to redo your work. Always ensure that you test your changes thoroughly before asking for a final review.
9. Do not bump the app version unless a human explicitly authorizes a release and specifies or approves the target version. Version changes must be completed as part of the release checklist below.
10. Treat every feature addition or modification conservatively: many App behaviors are tightly coupled. Before editing, assess affected callers, shared state, persistence, playback, queue, downloads, background work, synchronization, and both variants as applicable. Explicitly weigh implementation difficulty against the potential for disruption, data loss, and regressions; choose whether to make a bounded change, narrow its scope, or abandon it based on that assessment. Prefer the smallest localized change and avoid broad changes or refactoring core mechanisms. If assessment shows that a request is difficult to implement safely, requires invasive core changes, or has regression risks that cannot be adequately bounded and verified, do not implement it. Explain the difficulty, affected behavior, and any lower-risk alternative to the human contributor, and wait for an explicit decision before proceeding. A feature request is not permission to force a risky implementation.
11. For feature work, commit coherent progress checkpoints locally as implementation and validation advance. Do not push those commits to GitHub until the requested changes are complete, tested, and manually verified without known issues, unless a human explicitly directs otherwise.
12. After producing a requested Debug APK, deliver it through the current phone-accessible handoff channel so a human can test it on a physical Android device. Do not use a Windows-hosted AVD for manual validation unless a human explicitly requests emulator testing.

## Building and testing your changes

1. After making changes to the code, you should build the app to ensure that there are no compilation errors. Use the following command from the root directory of the project:

```bash
./gradlew :app:assembleFossDebug
```

2. If the build is not successful, review the error messages, fix the issues in your code, and try building again.
3. Once the build is successful, you can test your changes on an emulator or a physical device. Install the generated APK located at `app/build/outputs/apk/foss/debug/app-foss-debug.apk` and ask a human for help testing the specific features you worked on.

## MetroVerse feature-to-release workflow

Follow this workflow for MetroVerse changes. Do not skip release metadata because the code change is small.

### 1. Confirm scope and repository state

1. Restate the requested behavior, constraints, affected variants, and whether a release is authorized.
2. Pull `main` with a fast-forward-only update and inspect `git status` before editing.
3. Preserve unrelated user changes. Never revert or stage unrelated files just to obtain a clean tree.
4. Never read, modify, stage, print, or disclose signing material, API keys, cookies, private RSS URLs, `local.properties`, `keystore.properties`, or release keystores.
5. Keep FOSS and GMS support unless a human explicitly changes the supported variants. Do not restore removed variants such as Izzy.

### 2. Investigate and implement

1. Trace the complete behavior path before editing: UI, ViewModel, repository/service, Room, player, background refresh, and synchronization ownership as applicable.
2. Reuse the existing Compose, Room, Hilt, Media3, cache, download, queue, and notification architecture. Do not create parallel players or databases.
3. Keep Open RSS media out of YouTube-only decryption, radio, lyrics, Listen Together, Last.fm, and account synchronization paths.
4. Do not modify the Room schema unless a human explicitly approves it.
5. Keep edits narrow. Add comments only for non-obvious ownership, concurrency, or compatibility rules.
6. Add focused regression tests for every confirmed bug and pure decision helper where practical.

### 3. Validate before release preparation

1. Run focused tests while iterating.
2. Run the complete FOSS JVM suite:

```powershell
.\gradlew.bat :app:testFossDebugUnitTest
```

3. Build FOSS Debug after the final source edit:

```powershell
.\gradlew.bat :app:assembleFossDebug
```

4. Build GMS Debug locally when Cast, flavor-specific resources, manifests, or shared playback code are affected.
5. Run lint or other relevant checks when the change touches shared UI, resources, manifests, networking, security, or release infrastructure. Record inherited baseline findings separately from new findings.
6. Inspect the generated APK with Android SDK tools. Confirm application ID, version name/code, min/target SDK, expected flavor metadata, signature scheme, and SHA-256.
7. Use a read-only reviewer for substantial playback, synchronization, updater, security, or release changes. Resolve blocking findings before committing.

### 4. Complete every release metadata surface

Only perform this section after a human authorizes a release. Keep all version references consistent.

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update version-bearing runtime identifiers such as `PODCAST_USER_AGENT`.
3. Update the default English App changelog in `app/src/main/res/values/metrolist_strings.xml`. Do not edit translated string files unless explicitly requested.
4. Add the new release at the top of `changelog.md`.
5. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
6. Update every version number and FOSS/GMS download URL in `README.md`.
7. Update `.github/workflows/release.yml`: input examples, concise user-visible changes, download links, and any urgent notices.
8. Keep the App changelog, `changelog.md`, Fastlane notes, README release summaries, GitHub Release notes, and workflow release text short, factual, and limited to changes users can notice. Use plain language; do not use decorative, promotional, or exaggerated wording. Include internal details only when they affect installation, compatibility, privacy, or data safety.
9. For an urgent updater notice that must be readable by older Apps, put a plain-text warning at the very beginning of the GitHub Release body before Markdown. A new renderer cannot change the UI code already installed on older versions.
10. Run `git diff --check`, validate workflow YAML, search for stale version strings, and inspect the complete staged file list.

### 5. Commit and push safely

1. Stage files by explicit path. Confirm that unrelated assets, private files, build output, signing files, and local configuration are not staged.
2. Use a conventional commit message such as `fix(podcast): preserve RSS subscriptions` or `chore(release): prepare MetroVerse 0.x.y`.
3. Do not commit, push, tag, or publish without explicit human authorization.
4. Push `main`, wait for Push CI to finish, and confirm the CI `headSha` matches the intended release commit.
5. Create an annotated version tag only after Push CI succeeds. Verify `HEAD`, `origin/main`, and the dereferenced tag all point to the same commit.
6. Never move or force-push an already published tag unless a human explicitly authorizes rewriting that tag and its Release.

### 6. Publish the GitHub Release

1. Before publishing, confirm that the tag and GitHub Release do not already exist, or determine the explicitly authorized corrective-release procedure.
2. Trigger the signed release workflow with explicit stable parameters. Do not rely on workflow defaults:

```powershell
gh workflow run release.yml -R RizkLee/MetroVerse --ref main -f tag=v0.x.y -f publish=true -f prerelease=false
```

3. Wait for FOSS build, GMS build, signing, certificate verification, artifact upload, checksum generation, and publish jobs to succeed.
4. Confirm the Release is `draft=false`, `prerelease=false`, and returned by GitHub `/releases/latest` so stable-only updaters can discover it.
5. Confirm assets contain exactly the supported FOSS APK, GMS APK, and SHA256SUMS file unless a human approves something else.

### 7. Verify the public release

1. Download public assets without authentication.
2. Verify both APKs against the published SHA256SUMS file.
3. Confirm package name, version name/code, min/target SDK, source revision, FOSS/GMS metadata differences, v2/v3 signatures, signer count, and the long-term release certificate SHA-256.
4. Confirm the GitHub Release body, urgent notice, links, and Latest status are correct.
5. Report the commit, tag, Push CI URL, Release workflow URL, Release URL, APK SHA-256 values, certificate verification, tests, and any residual warnings.
6. Leave unrelated working-tree files untouched and disclose them in the final status.
