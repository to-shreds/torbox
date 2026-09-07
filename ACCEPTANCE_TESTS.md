# Acceptance test report

Date: 2026-09-04

## Status definitions

| Status | Meaning |
| --- | --- |
| Automated / pass | A JVM, mock-server, or static invariant test executed and passed for the stated behavior. |
| Emulator / pass | The complete behavior was exercised on an Android emulator and passed. |
| Requires real TorBox account/device/manual | Supporting code may be present, but the complete flow still needs the named runtime environment and human verification. It is not claimed as passed. |
| API limitation | The current official TorBox or Android contract prevents the exact requested behavior; the closest legitimate behavior is documented. |

The final JVM suite contains 141 tests with 0 failures, 0 errors, and 0 skipped tests. A clean GitHub Actions build produced both debug and minified release APKs. Full Android lint and release lint-vital completed with 0 errors; `lintDebug` reported 22 non-blocking dependency, API-level, and KTX suggestions. An API 36 emulator was started twice during the original v2.0 verification under software-only emulation; it reached ADB, zygote, service manager, and SurfaceFlinger but did not reach `sys.boot_completed` within the bounded test window. No physical device or live TorBox account was available for this patch. Accordingly, no row is marked Emulator / pass, and complete Android or account flows remain manual even when unit tests cover part of the implementation.

## Criteria 1 through 50

| # | Acceptance criterion | Status | Evidence or remaining check |
| ---: | --- | --- | --- |
| 1 | Android text share to TorBox still works | Requires real TorBox account/device/manual | Manifest and `MainActivity` handle `ACTION_SEND text/*`; live chooser-to-API flow remains to be exercised. |
| 2 | `magnet:` deep link still works | Requires real TorBox account/device/manual | Browsable magnet intent and native handoff are declared; live deep-link launch remains. |
| 3 | Ordinary web URL can be added | Requires real TorBox account/device/manual | Mock-server creation body is covered; current production account response remains. |
| 4 | `.torrent` file can be selected and uploaded | Requires real TorBox account/device/manual | OpenDocument, secure content-URI reader, bencode validation, and multipart creation are present; picker flow remains. |
| 5 | `.torrent` intent is accepted | Requires real TorBox account/device/manual | Manifest SEND and VIEW torrent intent filters exist; provider-to-app interoperability remains. |
| 6 | Active rows render API progress, speed, and ETA | Requires real TorBox account/device/manual | Mock parsing verifies all three fields and Compose rows consume them. Unit coverage proves the reported 303 MB of 592 MB case displays 51%; real account rendering remains. |
| 7 | Progress refresh never fabricates values | Automated / pass | Unfinished items use a valid server `total_downloaded / size` ratio, then TorBox's raw `0.0..1.0` fraction. Omitted values remain unknown, display values are safely clamped, and source invariants exclude elapsed-time interpolation. |
| 8 | Ready item automatically moves Active to Finished | Requires real TorBox account/device/manual | Tab derivation is readiness-based and five-second refresh updates the list; live transition remains. |
| 9 | Completion follows current readiness semantics | Automated / pass | Truth-table tests require `download_finished && download_present`; completed display state alone is rejected. |
| 10 | Notify When Complete can be armed | Requires real TorBox account/device/manual | UI, permission request, SQLite subscription, and service start are present; Android runtime flow remains. |
| 11 | Completion notification occurs exactly once | Requires real TorBox account/device/manual | Runner tests prove claim, post, mark, and disarm logic once in-process. Android has no atomic SQLite plus NotificationManager transaction, so the narrow process-death boundary needs explicit device testing. |
| 12 | Background monitoring complies with modern Android limits | Requires real TorBox account/device/manual | Declared dataSync FGS, visible notification, API 35 timeout, and 15-minute WorkManager fallback pass static review; OS runtime behavior remains. |
| 13 | Multi-file Share chooses a file and opens Android share sheet | Requires real TorBox account/device/manual | File sheet and per-file temporary-link flow exist; full selection and chooser flow remains. |
| 14 | Shared temporary URL contains no API token | Automated / pass | URL-safety and API tests reject raw and encoded token leakage; static tests require `redirect=false` and validation before a URL leaves the API layer. |
| 15 | Download to Device starts | Requires real TorBox account/device/manual | DownloadManager handoff exists; a real content download remains. |
| 16 | Media Open or Play invokes an external app | Requires real TorBox account/device/manual | MIME-aware `ACTION_VIEW` handoff exists; external app resolution remains. |
| 17 | Queue retrieves only torrent and web-download records | Automated / pass | Mock requests assert exactly `type=torrent` then `type=webdl`; production source invariant excludes other queue types. |
| 18 | Start Now works for a queued item | Requires real TorBox account/device/manual | Current queue start operation is implemented; live API success and movement remain. |
| 19 | AirLock tab shows actual AirLocked items | Requires real TorBox account/device/manual | Tab filtering uses the server `airlocked` field; real account list remains. |
| 20 | AirLock changes preserve editable state | Automated / pass | Torrent and web mock-server tests prove read-modify-write of name, tags, alternative hashes, and AirLock. |
| 21 | Tags and rename persist | Requires real TorBox account/device/manual | Full-state edit path is implemented; live server round trip remains. |
| 22 | Controls appear only for supported operations | Requires real TorBox account/device/manual | Operation enums and request tests allow torrent Reannounce, Pause, Resume, Delete; web Delete; queue Start, Delete. Conditional Compose visibility and live API behavior remain to be exercised. |
| 23 | Browser blocks representative ads and trackers | Requires real TorBox account/device/manual | Bundled host rules and request-level interception exist; representative WebView sites remain. |
| 24 | Browser magnets hand off to TorBox | Requires real TorBox account/device/manual | Native interception is present; live WebView navigation remains. |
| 25 | Long-press browser link offers Send to TorBox | Requires real TorBox account/device/manual | hit-test handling and native action dialog exist; gesture behavior remains. |
| 26 | Browser content cannot obtain the API token | Automated / pass | Static tests find no JavaScript bridge; browser controller has no API client/token dependency; token store is native. |
| 27 | Bad token shows a useful state | Requires real TorBox account/device/manual | Mock API test proves typed, sanitized bad-token handling; Compose state presentation remains. |
| 28 | Offline launch shows honest last-known data | Requires real TorBox account/device/manual | SQLite-first load and stale/offline state exist; airplane-mode relaunch remains. |
| 29 | No Usenet UI, calls, or terminology | Automated / pass | Static production-source test and mock queue requests exclude Usenet and NZB paths or terms. |
| 30 | Project builds from clean state | Automated / pass | `clean testDebugUnitTest lintDebug assembleDebug assembleRelease` completed. Debug and minified release APKs were produced; the signed APK separately passed `zipalign` and APK Signature Scheme v1, v2, and v3 verification. |
| 31 | Downloads exposes Compact, Cozy, Detailed directly | Requires real TorBox account/device/manual | Toolbar density control and all row implementations exist; interaction remains. |
| 32 | Compact is the initial default | Automated / pass | `DownloadsUiState` and native preference fallback both use Compact. |
| 33 | Density persists across navigation and restart | Requires real TorBox account/device/manual | Preference write/read path exists; process restart remains. |
| 34 | Density reflows loaded data without refetch | Requires real TorBox account/device/manual | `setDensity` updates preference and UI state only; rendered transition remains. |
| 35 | Compact Active shows about six or more rows | Requires real TorBox account/device/manual | Debug visual-QA data exists; target phone viewport measurement remains. |
| 36 | Compact Finished shows about seven to nine rows | Requires real TorBox account/device/manual | Debug visual-QA data exists; target phone viewport measurement remains. |
| 37 | Compact keeps progress, percentage, speed, and ETA | Requires real TorBox account/device/manual | Compact row renders these fields; visual verification with live values remains. |
| 38 | Compact avoids large repeated button bars | Requires real TorBox account/device/manual | Source uses row tap, limited icons, and overflow; visual review remains. |
| 39 | Cozy remains materially dense | Requires real TorBox account/device/manual | Separate Cozy row exists; viewport measurement remains. |
| 40 | Detailed provides richer presentation without becoming default | Requires real TorBox account/device/manual | Separate Detailed row and Compact default exist; runtime switching remains. |
| 41 | Header and controls preserve list space | Requires real TorBox account/device/manual | Compact toolbar, summary, tabs, and transient search are implemented; viewport measurement remains. |
| 42 | Long names truncate intelligently and remain available in detail | Requires real TorBox account/device/manual | Compact-name suffix preservation is unit tested and detail uses the full name; final Compose overflow behavior remains. |
| 43 | Tablet and landscape use width for information | Requires real TorBox account/device/manual | An 840dp wide/table-like branch aligns progress/status, size, speed or retention, ETA/files, and actions; tablet and rotation rendering remain. |
| 44 | Live refresh preserves scroll position | Requires real TorBox account/device/manual | stable item keys and remembered list states are used; polling under scroll remains. |
| 45 | Live refresh avoids visible full-list flashing | Requires real TorBox account/device/manual | stable merge and equality reuse are implemented; live visual behavior remains. |
| 46 | Density switching while scrolled is sensible | Requires real TorBox account/device/manual | Each Downloads tab has an independent remembered list state; deep-scroll density transitions remain to be exercised. |
| 47 | Search, sorting, and filters work across loaded records | Requires real TorBox account/device/manual | Production Downloads routes through the tested filter/sort helper and unions locally cached file-name matches; the interactive Compose path and a large live library remain. |
| 48 | Substantial history remains responsive | Requires real TorBox account/device/manual | pagination and lazy lists are implemented; profiling with a large real or representative library remains. |
| 49 | Compact enables fast account scanning | Requires real TorBox account/device/manual | This is a usability outcome and requires target-device evaluation. |
| 50 | Default layout does not regress to two or three large cards | Requires real TorBox account/device/manual | Compact is default and rows are restrained; target viewport measurement remains. |

## Current API and platform limitations

No acceptance row is silently omitted because of an API limitation. The closest legitimate implementations have these unavoidable constraints:

- The credential-free TorBox Relay route is listed in TorBox's current official Postman workspace but is absent from the Main API OpenAPI document. It is used only as a best-effort force-update request, with repository-wide 10-second per-account/per-torrent coalescing. The subsequent authenticated Main API list response with `bypass_cache=true` supplies all displayed values, which are never interpolated.
- TorBox does not document a push channel with stable download IDs for this third-party app. Completion monitoring polls.
- WorkManager periodic work is deferrable and has a 15-minute minimum interval. It cannot provide exact completion timing.
- Android has no atomic transaction spanning SQLite and NotificationManager, so strict exactly-once proof across a crash at the post/commit boundary is not available.
- Android 15 applies a rolling background time budget to `dataSync` foreground services. The service stops on timeout and falls back to WorkManager.
- WebView request interception supports useful host blocking, not complete extension-style or cosmetic ad blocking.
- Current TorBox sources disagree on temporary-link lifetime. The app generates a fresh link for every action and does not persist it.

## v2.0.1 screenshot-regression checks

| Reported issue | Status | Evidence or remaining check |
| --- | --- | --- |
| Bottom navigation is obscured by Samsung three-button navigation | Automated source guard / device recheck recommended | The fixed 64dp `NavigationBar` height was removed, Material 3 now owns its system inset, and both system bars explicitly use dark styling. A source invariant prevents reintroducing the fixed height. Samsung device geometry should be visually rechecked after install. |
| 303 MB of 592 MB displays as 1% | Automated / pass | Formatter regression test asserts the server byte ratio displays 51%, and list/detail progress bars and labels share the same helper. Ready-and-present state remains authoritative at 100%. |
| Filenames are unreadable beside four actions | Build and source review / device recheck recommended | Filenames now receive the row width with up to three lines; compact 20dp action icons are placed in a separate row underneath with 48dp touch targets. Final display-scale QA remains visual. |
| Files are not separated by folder | Automated / pass | Seventeen file-browser tests cover TorBox path cleanup, root/direct-child views, nested folders, breadcrumbs, parent navigation, custom titles, directory-only paths, duplicate filenames, and stable keys. |
| Need MKV filtering and sorting | Automated / pass | Exact case-insensitive extension filtering, recursive search, dynamic type counts, and name/size/type sorting are unit tested. |

## v2.0.2 refresh and Add-flow regression checks

| Reported behavior | Status | Evidence or remaining check |
| --- | --- | --- |
| Successful Add remains on the form | Automated source guard / pass | Manual Add success clears the candidate and pending file, returns to Downloads, and emits TorBox's success detail. |
| Add occurs before pressing its button | Automated source guard / pass | Clipboard detection inside Add only populates the candidate; the in-app `.torrent` picker only stages its payload. |
| Newly accepted torrent is absent while the aggregate list lags | Automated / pass | The create-response ID produces an immediate provisional row; refresh supplements the aggregate list with a direct ID lookup until the server list includes it. |
| Queue failure blocks otherwise valid downloads | Build and source review / pass | Download and queue refresh results are isolated, with an explicit partial-refresh warning. |

## Automated suite coverage

Executed JVM test classes:

- `AdBlockEngineTest`: 3 tests
- `BrowserNavigationPolicyTest`: 11 tests
- `RelayRequestCoalescerTest`: 4 tests
- `TorBoxApiClientTest`: 19 tests
- `CompletionMonitorRunnerTest`: 8 tests
- `CompletionNotificationIdentityTest`: 2 tests
- `NotificationCapabilitySnapshotTest`: 3 tests
- `QueuedSubscriptionMatcherTest`: 8 tests
- `ProjectSecurityInvariantsTest`: 10 tests
- Downloads UI, activity inset, and formatter tests: 15 tests
- parser, URL-safety, display, file, folder-browser, list, and torrent-payload utility tests: 53 tests
- Add completion and recent-addition reconciliation regression tests: 5 tests

The static invariant suite checks manifest share and torrent intents, foreground-service declarations, cleartext disablement, lack of a JavaScript bridge, readiness conjunction, server-derived progress, non-redirecting temporary-link requests, representable wire-operation vocabulary, and production exclusion of Usenet and NZB code paths.
