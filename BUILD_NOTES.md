# TorBox Drop 2.0.4 release-candidate build notes

## Build identity

| Property | Value |
| --- | --- |
| Application ID | `app.jabs.torboxdrop` |
| Version | `2.0.4` (`versionCode 20004`) |
| Minimum Android | API 23 |
| Target and compile SDK | API 36 |
| Build Tools | 36.0.0 |
| Language and UI | Kotlin 2.2.21, Jetpack Compose, Material 3 |
| Java toolchain | JDK 17 |
| Android Gradle Plugin | 8.13.2 |
| Gradle wrapper | 8.13 |

Build and verification commands:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The CI workflow runs the same clean unit-test, lint, debug-APK, and release shrinking tasks on pushes and pull requests. It uploads test reports and the debug APK as workflow artifacts. The CI release output is an unsigned verification artifact and is not distributed.

## v2.0.4 Google Drive automation release candidate

- Settings connects Google Drive using Google Play services authorization with only the `drive.file` scope and configures one global app-managed destination folder.
- Add exposes a per-torrent **Send to Google Drive when ready** choice without rewriting the remembered default.
- Cached torrents can be acted on promptly; unfinished and queued torrents remain durable until TorBox reports both `download_finished` and `download_present`.
- Multi-file torrents are submitted as individual files to TorBox's Google Drive integration. Media bytes do not pass through Android for this automation.
- Drive state is account-scoped and durable across process restart. Ambiguous remote POST results are quarantined and reconciled instead of blindly replayed.
- Raw magnet URLs are not retained in the Drive automation journal. Credential-bearing Google and TorBox Drive clients reject redirects and automatic request replay.
- Drive-only monitoring works without arming a completion notification. WorkManager remains the durable fallback; the faster foreground loop is available only when Android permits its visible monitoring notification.

Automated verification passed the complete JVM suite, Android lint, debug assembly and minified release assembly after the final pre-adversarial repairs. Separate security/OAuth and state-machine/recovery adversarial gates then passed. See [GOOGLE_DRIVE_VERIFICATION.md](GOOGLE_DRIVE_VERIFICATION.md).

The automated source is ready for deployment validation, but a production distribution is not claimed yet. The Google Cloud Android OAuth client must be registered for `app.jabs.torboxdrop` and the existing v2 release certificate SHA-1, and the APK must be signed with the existing v2 private key. CI does not possess that private key. A live consent flow, live TorBox-to-Drive transfer and physical-device background check therefore remain deployment tests.

## v2.0.3 targeted corrective release

- Per-file Share now requests the required new temporary CDN URL directly from the already-loaded ready file record. It no longer waits for redundant torrent and complete-file-list refreshes that could return stale data and stop the share flow before `requestdl`.
- The file sheet shows progress while TorBox prepares the link and shows any sanitized failure inside the sheet instead of sending it to an obscured snackbar.
- On success, the file sheet closes before Android's normal share chooser opens. Existing temporary-link credential checks remain unchanged.

Verified 2026-09-07 artifacts:

| Artifact | Result |
| --- | --- |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `96a3f427ab21521615e0d050ed1fa297199b8a440c0a18a1cd8591cbc93c8a58` |
| Unsigned minified APK | `app/build/outputs/apk/release/app-release-unsigned.apk`, SHA-256 `7fea047439b5b79b3df768bebcea0e97dd9ae2ae77d496d772024f63c7ce9e19` |
| Signed distribution APK | `release/TorBox-Drop-v2.0.3.apk`, 2,214,573 bytes, SHA-256 `0073b3a6b43d87d012a65c3a93c5a55141c9ac4a575d9b975367e7a7adec1d77` |

`apksigner verify --verbose --print-certs` passed for the distribution APK with v1, v2, and v3 signatures. `zipalign -c -p 4` also passed.

The v2.0.3 APK uses the same signing certificate as every prior v2 release, so it is a valid in-place update for an installed v2 build. The unavailable v1.0 private key limitation remains unchanged.

## v2.0.2 corrective release

- A successful manual Add now clears the candidate, closes Add, returns to Downloads, selects Active or Queue as appropriate, and shows TorBox's success detail in the app snackbar.
- Clipboard detection while Add is visible now fills the field but never submits it. Choosing a `.torrent` file from Add likewise stages the file until the user presses Add to TorBox. Android share-intent auto-send remains governed by its existing setting.
- A server-accepted active or queued item is inserted into the visible list immediately from the create response.
- Until TorBox's aggregate list includes a newly accepted active item, refresh also requests that returned item ID directly and retains the most recent real record. This prevents an eventually consistent list response from making a successful addition disappear.
- Downloads and queue refreshes are isolated. A transient queue failure no longer prevents a successful torrent and web-download refresh from reaching the screen.

## v2.0.1 corrective release

- Removed the fixed bottom-navigation height that compressed Material 3 content into Samsung's three-button system-navigation inset, and explicitly selected dark system-bar icon styling.
- Corrected TorBox progress handling to its documented `0.0..1.0` fraction. For unfinished downloads, a valid server `total_downloaded / size` ratio takes precedence so the displayed percentage and byte totals agree. The reported `303 MB of 592 MB` case now resolves to 51%.
- Rebuilt the file sheet as a folder browser with breadcrumbs, Android Back-to-parent handling, recursive search, exact extension filtering, and name, size, or type sorting.
- Moved each file's compact Open, Download, Share, and Copy actions underneath a filename area that can use the row width. Visible icons remain small while touch targets remain 48dp.
- Preserved infected-file restrictions across navigation, sorting, filtering, selection, and ZIP eligibility.

## Supplied v1.0 APK inventory

The supplied APK was inspected rather than treated as disposable.

| Property | Observed value |
| --- | --- |
| Package | `app.jabs.torboxdrop` |
| Version | `1.0` (`versionCode 1`) |
| SDK range | minSdk 23, targetSdk 35 |
| APK SHA-256 | `6a02eb89cedbcf45d4861b8562381ed6158581247b2006d91f61deebe18c1272` |
| Components | One exported, `singleTop` `MainActivity`; no service, receiver, or provider |
| Permission | `android.permission.INTERNET` only |
| UI architecture | One full-screen WebView containing app UI and the mini-browser |
| Local data | WebView local storage for token, settings, recent sends, and browser home |

Confirmed working v1.0 behavior:

- launcher app
- `ACTION_SEND` `text/*` through `EXTRA_TEXT`
- browsable `magnet:` `ACTION_VIEW`
- foreground clipboard detection for magnet text
- magnet and HTTP or HTTPS distinction
- TorBox magnet creation and web-download creation
- Queue and Cached Only options
- recent successful-send history capped at 30
- mini-browser, Downloads, and Settings views
- token validation through `/user/me`
- torrent and web-download list requests

Confirmed v1.0 limitations that are intentionally replaced:

- token and app logic shared a WebView storage and execution context
- third-party cookies were enabled in the browser
- no request-level blocker, bookmarks, `.torrent` intent, file actions, queue management, AirLock, or notifications
- no durable native download cache or active polling loop
- readiness could be inferred from `progress >= 100` or display states such as completed, cached, or uploading

## Native replacement summary

The replacement separates responsibilities:

- `TorBoxApiClient` performs authenticated Main API requests and parses typed models.
- `TorBoxRepository` coordinates remote data, stable refreshes, and the local cache.
- `SecureTokenStore` keeps only AES-GCM ciphertext on disk, outside backup scope. Its key is non-exportable and held by Android Keystore.
- `LocalStore` keeps download snapshots, queue records, file metadata, recent sends, bookmarks, and durable notification-delivery state in SQLite.
- `AppPreferences` persists density, tab, sort, add defaults, and browser privacy choices.
- `MainViewModel` owns application state and foreground polling.
- `SecureBrowserController` owns the dedicated browser WebView. It receives no API client or token.
- `CompletionMonitorService` and `CompletionMonitorWorker` share one durable, duplicate-resistant monitor runner.

The native UI uses four bottom destinations: Downloads, Add, Browser, and Settings. Downloads is the initial destination.

## API behavior verified against current official documentation

Documentation was rechecked on 2026-09-04 against the [TorBox API documentation](https://api-docs.torbox.app/), [official OpenAPI document](https://api.torbox.app/openapi.json), [official Postman workspace](https://www.postman.com/torbox/torbox-api/overview), and current TorBox support articles.

### Readiness and freshness

- TorBox documents `completed` as a qBittorrent state and warns clients not to use it as download-completion status.
- This app conservatively requires both `download_finished` and `download_present` before an item moves to Finished or triggers a notification.
- For active torrents, the client can first call the credential-free Relay route listed in TorBox's current official Postman workspace to request a server-side statistics refresh. The validated account user ID and torrent ID are path segments; the API token is never attached to this request. This Relay route is not included in the Main API OpenAPI document.
- Relay is therefore strictly best-effort. Repository requests for the same account and torrent are atomically coalesced within 10 seconds across foreground and background callers. The visible foreground monitor waits approximately 15 seconds after each completed pass. The following authenticated `mylist?bypass_cache=true` response, at an approximately five-second Active-screen cadence, is the only source used to display state and progress.
- The progress field is TorBox's `0.0..1.0` fraction. For unfinished items, a valid server byte ratio takes display precedence; ready-and-present state is authoritative at 100%. Values are clamped for display and never advanced based on elapsed time.
- Current published rate limits include 300 requests per minute per endpoint and stricter creation limits. See [API rate limits](https://support.torbox.app/en/articles/13726368-api-rate-limits).

### Supported actions

| Record | Operations represented in the app |
| --- | --- |
| Torrent | Reannounce, Pause, Resume, Delete |
| Web download | Delete |
| Queue | Start, Delete |
| Torrent and web edit | Rename, tags, AirLock through full-state read-modify-write |

Queue list calls are made separately with `type=torrent` and `type=webdl`.

### Temporary file URLs

TorBox `requestdl` authenticates with a query token. To keep that request private, the app:

1. calls `requestdl` inside the native API layer with `redirect=false`;
2. extracts the returned temporary URL;
3. requires HTTPS and rejects URL user-info;
4. rejects the raw token, URL-encoded token, and repeatedly decoded forms;
5. returns only the checked temporary URL to Share, Copy, DownloadManager, or `ACTION_VIEW`;
6. does not store the URL in durable file metadata.

Current TorBox documentation is inconsistent about whether a generated URL lasts one hour or three hours. The app therefore requests a new URL for every action and makes no lifetime promise.

## Android completion monitoring

When the user first enables a completion alert, the app asks for Android notification permission at that moment.

While the app is foregrounded on Active, refresh runs on an approximate five-second cadence and detects readiness. An explicitly armed unfinished item can also start a visible `dataSync` foreground service from that user action. The service waits 15 seconds after each completed pass before starting another, displays an ongoing notification, and stops once nothing remains armed.

WorkManager schedules a network-constrained periodic fallback. Its minimum repeat interval is 15 minutes, and Android may defer execution for battery and system conditions. It is not exact or second-by-second monitoring.

Normal duplicate delivery is suppressed with durable claim state and a stable per-subscription notification identity. Android does not offer one atomic transaction that both posts through `NotificationManager` and commits SQLite state. A process death inside that narrow post/commit boundary makes a strict mathematical exactly-once guarantee impossible. End-to-end duplicate behavior across process death remains a device acceptance test.

For Android 15 and later:

- background `dataSync` foreground-service time is limited to six hours in a rolling 24-hour period;
- `onTimeout` schedules the fallback and stops the service promptly;
- the boot receiver schedules WorkManager and does not launch a prohibited `dataSync` foreground service from `BOOT_COMPLETED`.

See Android's official [service-type requirements](https://developer.android.com/develop/background-work/services/fgs/service-types), [foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), and [persistent-work guidance](https://developer.android.com/develop/background-work/background-tasks/persistent).

TorBox's notification feed does not currently provide a documented third-party push hook with stable item IDs. Polling is therefore the closest legitimate behavior.

## Browser boundary

- The blocker evaluates canonical hosts and subdomains from a bundled baseline list. It does not use unsafe substring matching.
- Third-party cookies default off.
- WebView file access, content access, file-URL cross-origin access, mixed content, popup windows, and automatic JavaScript windows are disabled.
- Safe Browsing is enabled where available.
- SSL errors call `cancel()` with no Continue Anyway path.
- There is no `addJavascriptInterface` bridge.
- Magnets and long-pressed HTTP or HTTPS links cross into the native Add flow only through narrow callbacks.

This design cannot provide browser-extension-level cosmetic filtering or guarantee that every ad is removed.

## Signing and migration

The v1.0 APK has a valid self-signed RSA-3072 certificate using APK Signature Scheme v1 and v2. Its subject is `CN=TorBox Drop, O=Jabs`.

Certificate fingerprints:

- SHA-256: `D7:5E:5A:55:AC:62:1F:51:96:4D:21:FF:A5:84:50:02:2D:53:96:A9:82:23:47:58:B5:1A:C7:42:21:97:A5:9B`
- SHA-1: `D3:D7:17:EB:6C:D7:DD:C4:07:94:E0:2C:A6:E4:28:09:CB:D2:E8:38`

The matching private key and keystore were not present in the APK or supplied workspace. A new build cannot update v1.0 in place even though the package name is unchanged. Android requires v1.0 to be uninstalled before installing a differently signed replacement.

There is no legitimate automatic migration from the old WebView local storage after uninstall. The user must re-enter the API token and preferences. No code attempts to bypass Android signature checks.

The v2 distribution APK is signed with a newly generated RSA-4096 release key whose certificate subject is `CN=TorBox Drop, O=Jabs`. Its certificate SHA-256 fingerprint is `AA:AE:1A:1A:53:DE:80:CA:FE:F3:FF:98:B9:30:BF:83:A6:34:F8:6C:8B:F9:5F:D1:88:92:3B:4F:1A:C2:07:F7`. The private key and credentials are preserved separately and are not committed. Future v2 updates must use this exact key.

## Baseline v2 verification scope

GitHub Actions completed `testDebugUnitTest`, full `lintDebug`, debug assembly, release lint-vital, and minified/resource-shrunk release assembly with 141 tests, 0 failures, 0 errors, and 0 skipped. Android lint completed with 0 errors and 22 non-blocking dependency, API-level, and KTX suggestions.

JVM tests cover TorBox request construction and parsing, readiness truth tables, byte-consistent progress, AirLock full-state editing, queue type isolation, temporary-link token rejection, sanitized errors, completion claim and duplicate-suppression behavior, link and file-name parsing, folder-tree navigation, path normalization, extension filtering, file sorting, bencode validation, and static manifest and source security invariants.

The API 36 emulator reached ADB and core Android services under software-only emulation but did not reach `sys.boot_completed` within the bounded test window, so no emulator row is claimed as passed. JVM tests do not prove Android UI geometry, WebView behavior, background execution across device vendors, DownloadManager handoff, external-app intents, or live TorBox account behavior. Those remain device or account tests and are labeled that way in [ACCEPTANCE_TESTS.md](ACCEPTANCE_TESTS.md).
