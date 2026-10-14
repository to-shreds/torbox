# TorBox Drop

Repository code and release evidence control implementation. `to-shreds/ProjectStatus/projects/torbox/STATUS.md` controls readiness. Update this HANDOFF before updating STATUS last.

## Current state
Web **2.1.4** is published at https://to-shreds.github.io/Misc/TorboxDrop/. The YouTube fix is also available as **TorBox Drop Preview 2.1.4**, installed beside the existing Android app. The original signed Android **2.1.1** remains unchanged. The matching production signing key was unavailable in this workspace, so an in-place 2.1.4 production update has not been delivered. Do not uninstall or overwrite the existing app to work around that.

The current request was to fix YouTube URLs being accepted without a device download and provide the same function on GitHub. Both clients now normalize single-video YouTube URLs, start the web download immediately, follow the exact returned ID, require finished AND present, obtain an exact-file link, and expose preparation/failure rather than treating URL acceptance as completion. Android hands the video to its existing DownloadManager path. The website shows Download video when the prepared file can be saved. Keep the tab or Android file panel open during preparation. A check never submits a duplicate job.

## Controlling source and publication
- Source repository: `to-shreds/torbox`; native verification checkpoint `ad9cd8253844fb3a6ab80bb496d5438342712d98`; finalized runtime/artifact checkpoint `52b9dac3bef1c79a4168540f9a4a53942cf5c6f2`.
- GitHub Pages mirror: `to-shreds/Misc/TorboxDrop/index.html` and `TorBox-Drop.html`, publication `d7d67488f4ca2414cb7cf62cd3544bab09b91998`. No other Misc sites changed.
- Edit `web/src` and `web/index.template.html`, then run `node web/build.mjs` and synchronize both published HTML copies. Do not edit only the generated page.
- Official behavior references: https://api-docs.torbox.app/ and https://support.torbox.app/en/articles/9837721-debrid-hosters. Web downloads require a paid TorBox plan. Particular videos remain subject to TorBox/provider availability.
- Free stateless API-only relay remains `https://torbox-drop-api.onrender.com/api/`, service `srv-davdjv0u01pc73ec1phg`, authorized workspace `tea-dakujgmk1f9s73d2v8ng`. Relay version 2.1.2/behavior unchanged; no relay deployment or paid resource was needed. Separate Web Player services were untouched.

## Key completed work
Single watch, share, Shorts, live-video and embed links are canonicalized without share tracking; malformed video IDs, playlist-only/channel links and hostile hosts are rejected. Explicit YouTube preparation overrides Queue/Cached Only for that action, not generic Add. The API no longer mistakes JSON `error:false` for an error or selects an unrelated first list record when the requested ID is missing. Failed preparations, timeouts, cancellation, session/account changes and not-yet-present files are covered. Provider errors do not appear as completed downloads.

The file explorer, Added/Cached columns, Compact/Cozy/Detailed spacing and prominent account/quotas panel remain intact. Compact stays the default. Missing dates/metrics remain unknown and stale quota reads remain visibly stale. The native Drive destinations, account switching, delete markers, explicit share confirmation, encrypted credentials and existing background monitoring code remain preserved. Drive and durable background monitoring remain native-only; the new preparation flow itself needs the tab/file panel open.

## Verification
[Native and web workflow 38654410657](https://github.com/to-shreds/torbox/actions/runs/38654410657) passed: **277 native tests / 37 suites**, zero failures/errors/skips; lint zero errors and **24 warnings**; debug build and minified unsigned production release build; separately packaged preview build; APK signature, package/label and 16 KB alignment checks. The same checkpoint passed **43 core/relay checks** and **31 Chromium browser scenarios**, including actual fixture-byte saving through the new YouTube flow and mobile layout checks. New YouTube readiness, exact-ID, failure, cancellation and free-plan tests augment the preserved explorer/security/stress coverage.

[Finalization 38655568161](https://github.com/to-shreds/torbox/actions/runs/38655568161) retained the exact tested APK, corrected only documentation/login version labels, regenerated HTML, and repeated all 43 core and 31 browser tests successfully. Temporary repair workflows and transport files were removed from the final source tree. [Live publication 38655276961](https://github.com/to-shreds/Misc/actions/runs/38655276961) verified both public HTML hashes and a real 390px Chromium login: version 2.1.4, enabled key field, correct relay and no JavaScript errors. Detailed evidence is in `release/VERIFICATION-YouTube-2.1.4.md` and `.json`.

These are controlled API/byte-transfer tests, not an authenticated live YouTube extraction or physical-phone acceptance. No live TorBox key was supplied. Do not claim either happened.

## Deliverables and signing
- `release/TorBox-Drop-v2.1.4-preview.apk`: 21,878,774 bytes, SHA-256 `2164cb841063b9a33b8cbd3f4540000a867b9e18d28970822711225a80d3604f`. Debug preview package `app.jabs.torboxdrop.preview`, label TorBox Drop Preview, version 2.1.4 / 20104, min SDK 23, target 36. Certificate SHA-256 `61faf324d97a7ba86321291ec8a9cc770322b24eb10789993f42b6ec1feac442`. It uses separate private app storage and requires entering the API key again.
- `web/index.html`: 91,188 bytes, SHA-256 `e3711ff8db8c1e256d3571dcf30818caa323d5f83355316d7855eac21b524e86`, matching both published pages.
- Original production `release/TorBox-Drop-v2.1.1.apk`: SHA-256 `bcac40b3e556bff2c4da25a172e3d85aa9c1c8c0e2d8f270169d49dca83f687f`, production package `app.jabs.torboxdrop`. Production certificate SHA-1 `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`. Original private signing material stays outside Git and must be recovered for an in-place production update.
- Preview Google Drive OAuth is not verified for its new package/certificate. Keep using the original app for established Drive integrations. Do not claim preview Drive works merely because its source is retained.

## Do not break
Preserve exact TorBox file IDs, never infer an index; readiness requires finished AND present. Label `cached_at` Cached, not completion. Keep explicit share/add confirmation and single-item mutations; never blindly retry an uncertain mutation. Never send a raw key to an unrelated download host or copy/share credential-bearing URLs without consent. Native DownloadManager receives validated device-download URLs. Web keys/account data remain in tab memory only, never localStorage, sessionStorage, cookies, URL state, analytics or databases; sign-out/reload/account changes invalidate late responses. Direct download history may retain TorBox storage URLs, disclosed by the existing fallback confirmation. The relay allowlist handles API calls only and never file/media bodies. Preserve prior release artifacts and the original signing identity.

## Next action
Jon can open the live website, enter the key in its password field, choose YouTube, paste a single video URL, and save when Download video appears. Alternatively install the separate preview, enter the key inside it, and choose Download YouTube video on Add. The remaining acceptance step is a real public-video download with his paid TorBox account and phone. Capture the visible error if the provider rejects it. In-place production delivery separately requires the existing private signing key; never request the TorBox key in chat.
