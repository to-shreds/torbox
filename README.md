# TorBox Drop

## 2.1.0 downloads and explorer

[Download the Android APK](release/TorBox-Drop-v2.1.0.apk) · [Web client](https://to-shreds.github.io/Misc/TorboxDrop/) · [Standalone HTML](web/index.html)

The web page is published with sign-in disabled until its stateless API relay is activated. It must not collect keys using an unverified service URL. See [web/README.md](web/README.md) for activation and privacy details.

Android Download now accepts validated TorBox storage links, displays preparation and errors inside the file panel, and tracks the Android DownloadManager handoff. Every list density shows the actual added date and age; cache dates are shown separately. Ready rows open the folder browser. This APK uses the existing private-use signing certificate and can update the matching 2.0.7 build in place.

The responsive web client adds session-only API-key login, an explorer with folders/breadcrumbs, natural sorting, dates, search, type filters, multi-file selection, ZIP and individual downloads, queue controls, rename/tags, AirLock, and add/delete/pause/resume/reannounce where TorBox supports them. Native Drive automation and background notifications remain available in the APK.

## Preserved manual Google Drive delivery

Ready torrent rows now include a Drive button. It opens a folder-name confirmation prompt, then queues a copy to that folder without changing the automatic-upload default or deleting the TorBox original. See [MANUAL_DRIVE.md](MANUAL_DRIVE.md) for the per-folder queue and the limitation on concurrent uploads from other TorBox clients.

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
- Anything shared or externally opened into TorBox Drop is staged on Add and waits for **Add to TorBox**, so per-item options such as Google Drive can be reviewed before creation.
- Completion watches are durable and use a user-started visible foreground service with a slower WorkManager fallback.
- Google Drive automation can send a torrent's individual files through TorBox directly to one configured Drive folder as soon as TorBox says the torrent is truly ready. The Drive action is durable and independent of the completion-notification toggle.
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

## Google Drive automation

Settings can connect one Google Drive account and one global destination folder. Add then exposes **Send to Google Drive when ready** for each torrent, with a separate remembered default. Cached torrents can proceed promptly; ordinary and queued torrents wait for the same authoritative readiness rule used elsewhere in the app. TorBox performs the file transfer server-side.

Google Android OAuth configuration is required before a signed app can authorize Drive. See [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md) for the package and certificate values for the current private build, and [GOOGLE_DRIVE_VERIFICATION.md](GOOGLE_DRIVE_VERIFICATION.md) for the completed automated and adversarial checks.

## Architecture

| Area | Implementation |
| --- | --- |
| UI | Kotlin, Jetpack Compose, Material 3, edge-to-edge layout, stable-key lazy lists |
| App state | `MainViewModel` with immutable UI state and lifecycle-aware collection |
| TorBox API | Native OkHttp client and repository; the browser never owns the token |
| Local data | SQLite for last-known records, files, bookmarks, recent sends, notification subscriptions, and durable Drive automation state |
| Preferences | Native `SharedPreferences` for add, browser, and list-view choices |
| Credential | Non-exportable Android Keystore AES key plus AES-GCM ciphertext in `noBackupFilesDir` |
| Browser | One dedicated hardened WebView controlled by native Compose chrome |
| Background work | Shared user-started `dataSync` live monitor plus constrained WorkManager fallback for completion and Drive automation |

The browser has no JavaScript bridge. File and content access are disabled in WebView, mixed content is rejected, certificate errors fail closed, Safe Browsing remains enabled where available, popups are suppressed, and third-party cookies are off by default.

## TorBox data semantics

The implementation follows the current official TorBox Main API rather than inferring behavior from display labels.

- Ready means both `download_finished` and `download_present` are true. A `download_state` value such as `completed` is not accepted as proof that files are ready.
- TorBox's `progress` value is a `0.0..1.0` fraction. For unfinished items, the UI prefers a valid `total_downloaded / size` ratio, then falls back to that raw fraction. A ready-and-present item is authoritative at 100%. No value is advanced locally.
- Before a live torrent refresh, the app can make the credential-free Relay request listed in TorBox's current official Postman workspace to ask the service to refresh that torrent's server-side statistics. Relay requests are best-effort and coalesced per account and torrent for 10 seconds across foreground and background callers. The route is not part of the Main API OpenAPI document, so the following authenticated `mylist?bypass_cache=true` response remains the only displayed truth.
- Foreground Active refresh runs approximately every five seconds. Values are never interpolated between Main API responses.
- Stable finished data uses normal cached list requests. Manual refresh and active monitoring ask TorBox for fresh data only where it helps.
- Download-link requests use `redirect=false`. The API credential is used only in the private native request. Credential-free returned HTTPS URLs are allowed for normal copy/open actions. A narrow allowlist also permits TorBox-owned credential-bearing storage links for private Android DownloadManager delivery. External sharing retains its separate explicit API-key disclosure and confirmation. No generated link is cached as file metadata.
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

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The 2.1.0 distribution artifact is `release/TorBox-Drop-v2.1.0.apk`, minified, resource-shrunk, zip-aligned, and signed with the retained private-use certificate. Private signing keys and passwords are never committed. CI publishes unsigned release builds; signing takes place outside CI.

Package: `app.jabs.torboxdrop`; versionCode: `20100`; minimum Android SDK: 23; target SDK: 36. Certificate SHA-1: `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`.

See [BUILD_NOTES.md](BUILD_NOTES.md) for environment and signing details and [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md) for the current verification matrix.

## Installation and signing

Android requires an installed package update to be signed by a compatible signing identity. If the matching private key is unavailable, it cannot be recovered from the APK.

For current private-use builds, an unavailable old key is not a build blocker. Generate a replacement signing key, sign the verified APK, uninstall the differently signed installed copy, and install the replacement fresh. Uninstalling removes local app data, so the TorBox API token and preferences must be entered again.

If you still have the signing key for the installed private build, reuse it for an in-place update. If not, fresh-install the newly signed APK and retain its new key when convenient for later private updates. Never commit private signing keys or passwords to this public repository.

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

TorBox Drop has no analytics or tracking SDK. The native app encrypts its saved token with Android Keystore. The web client retains its token only in tab memory, clears it on sign-out/reload, and uses no localStorage, sessionStorage, cookies, analytics, or database. Its relay keeps credentials only while forwarding the current TorBox API request and never proxies file bytes. Direct TorBox links can include your key; Android/browser download history can retain those URLs. External sharing requires the existing native confirmation; the web client refuses credential-bearing copy/open/share actions.
