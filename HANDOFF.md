# TorBox Drop: Handoff

Repository source, CI, release artifacts and real deployments control implementation. `to-shreds/ProjectStatus/projects/torbox/STATUS.md` controls readiness. Update HANDOFF first; update STATUS as the final persistence step.

## Latest release and current state (2026-10-15T01:59:31.160Z)

**Android 2.1.6 MP4 preview was built and published. It is fit for initial testing, but a real large-video MP4 conversion on Jon's phone has not been verified.** This release responds to Jon's narrower final requirement: MP4 conversion **in the APK only**, entirely on the phone in the background, launched **only by an explicit Save as MP4 button for YouTube videos**. The website and Render server were not changed for MP4. Original Download remains unchanged.

Download tested preview: https://github.com/to-shreds/torbox/raw/refs/heads/main/release/TorBox-Drop-v2.1.6-preview.apk

- APK path: `to-shreds/torbox/release/TorBox-Drop-v2.1.6-preview.apk`
- APK size: **25,270,697 bytes**; SHA-256: **1df970ad3e30aa4df45b03074a7c85a53ce468411ee76f2ca8c54ecf53da3133** from `release/SHA256SUMS-Preview-2.1.6.txt`; Git blob `fda2ddd3f56b7056513821489a2cf0255ecd3c8e`.
- Built with versionCode **20106**, versionName **2.1.6**, preview app ID `app.jabs.torboxdrop.preview216`, label `TorBox Drop Preview 2.1.6`. Installs independently of signed production app and older previews.
- [GitHub Android CI run 38667447751](https://github.com/to-shreds/torbox/actions/runs/38667447751), job `116202517589`: unit tests, Android lint, debug APK, shrink-checked unsigned release APK, separate preview debug build, artifact upload, and release Git push all **succeeded**. Release commit `7bf3c7250a...`. Earlier failed run `38667071454` detected Android API-29 MediaStore lint issue, fixed before this successful run. No physical handset or live TorBox account conversion test occurred.
- MP4 implementation source latest tested commit `853ba4aade555e5ee01c943af90c4078622486de`. README refreshed for 2.1.6 afterward.

## New Android-only MP4 behavior

- `util/YouTubeMp4Policy.kt` strictly gates the MP4 button to finished/present YouTube `WEB` items, safe exact file identity, and actual video filenames `.webm`/`.mp4`. YouTube URL normalization rejects hostile hosts, playlists and ordinary sites.
- `ui/screens/AddScreen.kt` shows a distinct **Save as MP4 (background)** button only for a recognized single-video YouTube URL. Clicking queues work immediately when TorBox accepts the active web-download ID. `MainViewModel` also supports already-completed YouTube items through details and individual file rows; no MP4 conversion occurs merely from adding or ordinary Download.
- `mp4/YouTubeMp4Worker.kt` queues durable, unique WorkManager jobs under a SHA-256 account scope. Only identity/filename/canonical YouTube source are stored; no API token, decrypted CDN URL or media is persisted in WorkData. The worker rechecks account/source/ID and waits for TorBox ready state with retry if needed. It obtains TorBox temporary URLs from native API, verifies HTTPS destination/redirects and downloads source **directly from TorBox CDN to phone**, not Render. Transfer can resume from partial bytes using Range, with verification.
- Media3 Transformer decodes/encodes WebM to actual **H.264 video / AAC audio MP4**, controlled from the Media3 thread required by the library, with progress via WorkManager foreground notification. If source is genuine MP4, copies without re-encoding. A partial conversion is never published. On success it copies the verified MP4 to Android **Downloads** through MediaStore then removes temporary files and reports completion. Device storage must accommodate original plus MP4 output temporarily. Android 10+ required for this background MP4 path.
- The background job outlives the app Activity, but **Android may defer/stop work due to foreground-service/media-processing limits, force-stop, system constraints or insufficient resources**. Long videos, unsupported codecs, physical-device hardware acceleration and any claim of uninterrupted multi-hour work remain **untested**. WorkManager can retry network failures and reattempt conversion after interruption. No absolute guaranteed turnaround time.
- Foreground media-processing service declaration, permissions and optional progress/cancel notification are in manifest. Security checks and `YouTubeMp4PolicyTest.kt` added. Existing browser/ordinary download behavior unchanged.

## Preserved web and native releases

- **Web 2.1.5** live and unchanged: https://to-shreds.github.io/Misc/TorboxDrop/; source `to-shreds/torbox/web/index.html`; publication `to-shreds/Misc/TorboxDrop/index.html` and `TorBox-Drop.html`, both Git blob `c74a84ed95c3bf726f1a01715d0f137733eb9884`; Pages deployment `38658816222` succeeded. The web regular-download secure relay path can stream media via Render, but **the new Android MP4 pathway does not use it**.
- **Render backend** https://torbox-drop-api.onrender.com/ remains version 2.1.5, deployment `dep-db81ljbncjis73e9batg`, service `srv-davdjv0u01pc73ec1phg`, workspace `tea-dakujgmk1f9s73d2v8ng`. This release did not change/deploy Render.
- **Older signed Android production 2.1.1** `release/TorBox-Drop-v2.1.1.apk` remains unchanged. Signing cert SHA-1 `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`; its matching private key is unavailable. No in-place update can be claimed. Keep it installed, especially for existing Google Drive OAuth. Older preview packages `app.jabs.torboxdrop.preview` (2.1.4) and `app.jabs.torboxdrop.preview215` (2.1.5) remain unchanged; 2.1.6 preview's separate Google Drive OAuth is **not verified**.
- Previous web 2.1.5 controlled suite [38658559679](https://github.com/to-shreds/torbox/actions/runs/38658559679) passed 45 core/relay checks and 31 Chromium browser scenarios; prior native 2.1.5 browser/download improvements retained. Real-account file-transfer acceptance on Jon's phone/browser remains unverified.

## Invariants and next action

Do not overwrite the production app or expose credentials; do not add keys or credential URLs to repo, WorkManager data, logs or clipboard. Never silently convert regular downloads or non-YouTube sources. No new server or Render media bandwidth for MP4. Preserve exact TorBox IDs, ready AND present gating, original download options, quota/dates, layouts, Drive/notification integrations and all prior releases. Do not invent successful real YouTube/MP4 conversion from CI build results. Official API contract: https://api.torbox.app/openapi.json.

**Next:** Jon installs the 2.1.6 preview side-by-side, enters his key, taps Save as MP4 on a YouTube item or Add screen, can leave the app after it says queued, and confirms file in Android Downloads. Request a screenshot of MP4 job status/notification and actual file only if failed. For a 2.9GB WebM first confirm enough internal storage, available network and Android notification/background constraints. If it fails after app closure or restarts, inspect WorkManager state, Media3 export diagnostics and Android OS limits on a real device. Consider optional small-media then large-media staged tests. Website/Render remain unchanged for this feature.
