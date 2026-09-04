# TorBox Drop

TorBox Drop is a native Android client for TorBox. It keeps the original app's fast share-to-TorBox flow, then adds a dense download manager, completion monitoring, file actions, queue and AirLock management, and a small privacy-focused browser.

The application ID remains `app.jabs.torboxdrop`. The project contains no Usenet UI, filters, queue requests, or management calls.

## What is included

- Downloads opens first, with Active, Finished, Queue, and AirLock tabs.
- Torrent and web downloads appear in one searchable, sortable list with optional type and state filters.
- Compact is the default density. Cozy and Detailed can be selected from Downloads without refetching data.
- Active rows show server-derived progress, speed, ETA, and a thin progress bar. Valid downloaded/total byte counts take precedence so the percentage agrees with the transfer totals.
- A detail screen provides diagnostics, rename, tags, AirLock, Files, Share, Download/Open, Reannounce, Pause, Resume, and Delete only where supported.
- The file browser presents a real folder tree with breadcrumbs and Android Back-to-parent behavior. It also supports recursive search, exact extension filters such as MKV, name/size/type sorting, readable full-width filenames, per-file temporary links, Android sharing, Android DownloadManager, and external media apps.
- Infected files are blocked from Share and Open; Download requires an explicit warning confirmation.
- The queue is fetched separately for torrent and web-download records and supports Start and Delete.
- Add accepts magnets, HTTP or HTTPS links, and `.torrent` content URIs from the picker or Android intents.
- Completion watches are durable and use a user-started visible foreground service with a slower WorkManager fallback.
- The dedicated browser has native controls, bookmarks, magnet handoff, long-press link actions, download choices, and request-level host blocking from a bundled ruleset.
- The last download and queue snapshot is cached locally so Downloads can render before a network refresh.

## Behavior preserved from v1.0

Inspection of the supplied APK confirmed these behaviors, which remain represented in the native replacement:

- Android launcher entry point
- `ACTION_SEND` text handling
- browsable `magnet:` deep links
- foreground clipboard detection
- magnet versus HTTP or HTTPS classification
- torrent and web-download creation
- Queue and Cached Only add options
- up to 30 recent successful sends
- mini-browser, Downloads, and Settings areas
- TorBox token validation through the current-user endpoint

The old app kept the API token, preferences, history, and browser state in WebView local storage. The replacement does not reuse that storage. The token is encrypted with an Android Keystore AES-GCM key, and app data is held in native preferences and SQLite.

## Architecture

| Area | Implementation |
| --- | --- |
| UI | Kotlin, Jetpack Compose, Material 3, edge-to-edge layout, stable-key lazy lists |
| App state | `MainViewModel` with immutable UI state and lifecycle-aware collection |
| TorBox API | Native OkHttp client and repository; the browser never owns the token |
| Local data | SQLite for last-known records, files, bookmarks, recent sends, and notification subscriptions |
| Preferences | Native `SharedPreferences` for add, browser, and list-view choices |
| Credential | Non-exportable Android Keystore AES key plus AES-GCM ciphertext in `noBackupFilesDir` |
| Browser | One dedicated hardened WebView controlled by native Compose chrome |
| Background work | User-started `dataSync` foreground service plus constrained periodic WorkManager fallback |

The browser has no JavaScript bridge. File and content access are disabled in WebView, mixed content is rejected, certificate errors fail closed, Safe Browsing remains enabled where available, popups are suppressed, and third-party cookies are off by default.

## TorBox data semantics

The implementation follows the current official TorBox Main API rather than inferring behavior from display labels.

- Ready means both `download_finished` and `download_present` are true. A `download_state` value such as `completed` is not accepted as proof that files are ready.
- TorBox's `progress` value is a `0.0..1.0` fraction. For unfinished items, the UI prefers a valid `total_downloaded / size` ratio, then falls back to that raw fraction. A ready-and-present item is authoritative at 100%. No value is advanced locally.
- Before a live torrent refresh, the app can make the credential-free Relay request listed in TorBox's current official Postman workspace to ask the service to refresh that torrent's server-side statistics. Relay requests are best-effort and coalesced per account and torrent for 10 seconds across foreground and background callers. The route is not part of the Main API OpenAPI document, so the following authenticated `mylist?bypass_cache=true` response remains the only displayed truth.
- Foreground Active refresh runs approximately every five seconds. Values are never interpolated between Main API responses.
- Stable finished data uses normal cached list requests. Manual refresh and active monitoring ask TorBox for fresh data only where it helps.
- Download-link requests use `redirect=false`. The API credential is used only in the private native request. Only a returned HTTPS temporary URL that does not contain the raw or encoded token may leave the API layer.
- AirLock, rename, and tag edits first fetch current editable state and submit the complete name, tags, alternative hashes, and AirLock tuple so unrelated values are preserved.

Useful source material: [TorBox API documentation](https://api-docs.torbox.app/), [TorBox OpenAPI document](https://api.torbox.app/openapi.json), [official TorBox Postman workspace](https://www.postman.com/torbox/torbox-api/overview), [TorBox API rate limits](https://support.torbox.app/en/articles/13726368-api-rate-limits), and [TorBox AirLock](https://support.torbox.app/en/articles/15417147-torbox-airlock).

## Build

Requirements:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0

From the repository root in a networked build environment:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The verified distribution artifact for this release is `release/TorBox-Drop-v2.0.1.apk`; it is minified, resource-shrunk, zip-aligned, and signed with the TorBox Drop v2 release key. The private key is deliberately not committed.

See [BUILD_NOTES.md](BUILD_NOTES.md) for environment and signing details and [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md) for the current verification matrix.

## Installation and v1.0 signing

The supplied v1.0 APK's private signing key was not provided and cannot be recovered from the APK. Android will reject an APK with the same application ID when it is signed by a different key.

Uninstall the original WebView-based v1.0 before installing the native v2 line:

```bash
adb uninstall app.jabs.torboxdrop
adb install release/TorBox-Drop-v2.0.1.apk
```

Uninstalling removes the old WebView local-storage token, settings, and history. Enter the TorBox API token again in Settings. This project does not bypass Android signature verification and does not claim an in-place upgrade path.

If TorBox Drop v2.0.0 is already installed, v2.0.1 uses the same package and signing certificate and can update it in place:

```bash
adb install -r release/TorBox-Drop-v2.0.1.apk
```

## Platform limits

- WorkManager periodic work has a 15-minute minimum interval and is deferrable. It cannot promise exact completion timing.
- Android 15 limits background `dataSync` foreground-service time. The service handles timeout and leaves slower monitoring to WorkManager.
- A foreground service is started only from a visible user action that arms a watch, including Add with Notify When Complete, and always carries an ongoing notification. Boot restoration schedules WorkManager instead of illegally starting that foreground service.
- TorBox currently provides no documented push channel with stable item IDs for this third-party client, so completion detection requires polling.
- Android does not provide one atomic transaction spanning SQLite and `NotificationManager`. Durable claims and stable notification identities suppress normal duplicates, but a process death at the post/commit boundary prevents a mathematical exactly-once guarantee.
- WebView host blocking removes many common ad and tracker requests but cannot guarantee complete ad removal or cosmetic filtering.
- The app does not present an official AirLock remaining-capacity number because the current account response used here does not provide an authoritative quota field.

Android references: [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), and [persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent).

## Privacy

TorBox Drop has no analytics or tracking SDK. It does not log API tokens, place them in browser pages, send them in Android share intents, or retain generated CDN links as file metadata.
