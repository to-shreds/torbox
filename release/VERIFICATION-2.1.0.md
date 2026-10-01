# TorBox Drop 2.1.0 verification

Verified 2026-10-01. Android artifact is ready for a private in-place update. The web frontend and free stateless relay are activated and verified. Jon confirmed the workspace, the relay deployed successfully, and the published page now enables API-key sign-in. See ACTIVATION-2.1.0.md for the live checks.

## Changes

- Fixed the Android URL-validation boundary: validated TorBox storage links can enter Android DownloadManager, while credential-bearing copy/open actions remain blocked and external sharing keeps its existing consent path.
- Added visible preparation, handoff, progress and actionable errors inside the file panel, plus Open Android Downloads.
- Added real added/cache dates and age to every Android list density and the web explorer. Unknown dates stay unknown; cache dates are never labeled completion dates.
- Added a self-contained responsive web explorer with API-key login, no persistent browser storage, folders, breadcrumbs, natural sorting, filters, paging, selected files, streamed saves, browser-save fallbacks, queue and account-item controls.
- Kept native Drive automation, notification monitoring, Delete correction and share-to-Add confirmation.

## Evidence

| Gate | Result |
| --- | --- |
| Android JVM/Robolectric suite | 268 tests, 35 XML suites, zero failures/errors/skips |
| Android lint | Passed; zero errors, 12 non-blocking style/version/API-23 warnings |
| Minified resource-shrunk release assembly | Passed |
| Core/API/security/relay suite | 30 passed, zero failed/skipped |
| Chromium browser scenarios | 17 passed, zero failed/skipped |
| Explorer stress | 50,000 files across 500 folders |
| API paging and rendering | All 2,500 synthetic items loaded; DOM bounded to 100 rows |
| Relay isolation stress | 48 concurrent independent synthetic account keys |
| Actual browser downloads | Exact fixture bytes matched; streamed-save completion and failed-write abort/fallback verified |
| UI review | Desktop 1440px and mobile 390px screenshots inspected; mobile Download fully visible |
| APK signatures | v1/v2/v3 verified with retained private certificate |
| APK contents | All 157 unsigned ZIP entries identical after signing |
| APK package and flags | app.jabs.torboxdrop, 2.1.0 / 20100, non-debuggable, min SDK 23, target SDK 36 |
| APK alignment | 16 KB page alignment verified with Build Tools 36 |

Native production source matches commit `80e3d2937e74e18e0f43550696d91d1d2a8c6861`. The full local command `testDebugUnitTest lintDebug assembleRelease` completed successfully with JDK 17, Gradle 8.13 and Android Build Tools 36. Web/UI production source was verified by [GitHub run 36929555047](https://github.com/to-shreds/torbox/actions/runs/36929555047); artifact 11195631241 contains the four inspected screenshots. A later native-only change did not alter the web source. CI also runs these gates on PR/main.

The evidence ZIP contains the actual Android XML results, lint report, web/core output, browser TAP output and desktop/mobile screenshots. All requests in automated tests use fixtures; no real TorBox item was added, edited or deleted.

## Distribution integrity

- APK: `TorBox-Drop-v2.1.0.apk`, 2,535,916 bytes.
- APK SHA-256: `6a6a8101eec1979105896e4c9ee9904698a6140f34dede85186df19923309b3d`.
- Unsigned APK SHA-256: `bf0a74d6b84a4753ecd7bfdd48968502a02e817524a16eaf2727c5af7817939f`.
- Standalone HTML SHA-256: `1b308d632fc680aee964c023fda83376dacce1c84cf3b321e126df899564dacb`.
- Signer SHA-1: `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`.

This matches the retained private 2.0.6/2.0.7 certificate, so install over the matching private app without uninstalling or changing Google OAuth. No signing keys or passwords are published.

## Remaining live checks and activation

No live TorBox API key or physical Android device was supplied. Account-specific download permissions, real TorBox storage-link expiry/CORS, Android DownloadManager/device storage settings and Google Drive delivery still need live acceptance. Automated success is not described as a live-account or physical-device test.

TorBox's live CORS preflight rejects GitHub Pages origins. The deployment is therefore a free stateless Node API relay, with direct TorBox file downloads and no credential database. Jon confirmed **My Workspace**, and the free relay is live at https://torbox-drop-api.onrender.com. Health, CORS and authentication rejection were checked against the deployed service, and both GitHub Pages HTML files match the activated build. The original evidence ZIP records the pre-activation verification; ACTIVATION-2.1.0.md and activation-live-checks.json record the completed rollout. A successful real-account download remains a live acceptance check. Do not ask Jon to post his TorBox key in chat.
