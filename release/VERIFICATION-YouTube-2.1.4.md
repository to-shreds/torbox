# TorBox Drop YouTube 2.1.4 verification

## Delivered
Web 2.1.4 is published at https://to-shreds.github.io/Misc/TorboxDrop/ with a dedicated YouTube action. Android 2.1.4 is delivered as `TorBox-Drop-v2.1.4-preview.apk`, a separately signed debug preview installed beside the existing app. The original signed 2.1.1 production APK and its signing identity remain unchanged.

The fix normalizes single-video YouTube links, starts preparation immediately, follows the exact returned TorBox web-download ID, requires both finished and present, and obtains a fresh exact-file link before saving. It handles false-valued error fields correctly and does not select an unrelated list item. The browser offers Download video when ready; Android hands the selected video to the existing DownloadManager implementation. Provider failure, cancellation and timeout are visible. Playlist/channel-only links are rejected and status checks do not re-add jobs.

## Verified gates
- [Native and web run 38654410657](https://github.com/to-shreds/torbox/actions/runs/38654410657), native source `ad9cd8253844fb3a6ab80bb496d5438342712d98`: **277 native tests, 37 suites, zero failures/errors/skips**. Lint: **zero errors, 24 warnings**. Debug and minified unsigned production release builds passed, followed by the separately packaged preview build.
- Preview APK signature verification, application ID/version/label inspection and 16 KB alignment check passed. Full diagnostic text is retained in `VERIFICATION-2.1.4-preview-signature.txt`, `-manifest.txt` and `-alignment.txt`. Signature verification emitted normal META-INF warnings; it was not warning-free.
- **43 core/relay checks and 31 Chromium browser scenarios passed.** Coverage includes canonical URLs, single-video validation, preparation state, exact IDs, provider errors, cancellation, free-plan blocking, privacy/session races, and actual fixture-byte browser downloads. Existing explorer/date/density/quotas and stress scenarios remained enabled.
- [Finalization run 38655568161](https://github.com/to-shreds/torbox/actions/runs/38655568161) retained that exact APK, corrected documentation and stale login labels, regenerated the standalone page and reran all 43 core and 31 browser checks successfully. Finalized source checkpoint: `52b9dac3bef1c79a4168540f9a4a53942cf5c6f2`.
- [Live publication run 38655276961](https://github.com/to-shreds/Misc/actions/runs/38655276961) succeeded. Both public HTML copies matched the checksum below. A real Chromium mobile browser confirmed web 2.1.4, enabled password entry, configured relay and zero JavaScript errors at 2026-10-14T23:39:38.965Z. Misc publication: `d7d67488f4ca2414cb7cf62cd3544bab09b91998`.

## Artifact identities
| Artifact | Bytes | SHA-256 |
|---|---:|---|
| TorBox-Drop-v2.1.4-preview.apk | 21,878,774 | `2164cb841063b9a33b8cbd3f4540000a867b9e18d28970822711225a80d3604f` |
| web/index.html and both public HTML copies | 91,188 | `e3711ff8db8c1e256d3571dcf30818caa323d5f83355316d7855eac21b524e86` |

Preview: package `app.jabs.torboxdrop.preview`, label TorBox Drop Preview, versionName 2.1.4, versionCode 20104, min SDK 23 and target SDK 36. Certificate SHA-256: `61faf324d97a7ba86321291ec8a9cc770322b24eb10789993f42b6ec1feac442`.

Original signed production 2.1.1 SHA-256 remains `bcac40b3e556bff2c4da25a172e3d85aa9c1c8c0e2d8f270169d49dca83f687f`. The preview is not an in-place production update. Its separate storage requires entering the API key again. The existing app must not be uninstalled to install the preview. Google Drive OAuth has not been verified for the preview's new package and certificate.

## Boundaries and remaining acceptance
No live TorBox key or physical Android device was available. Automated YouTube tests use controlled TorBox responses and real fixture bytes, not an authenticated extraction from YouTube. End-to-end acceptance of a particular YouTube video with the user's account remains unverified. [TorBox's official hoster documentation](https://support.torbox.app/en/articles/9837721-debrid-hosters) requires a paid plan for web downloads and does not guarantee hoster availability. Keep the browser tab or Android file panel open during preparation. Playlist downloading is not implemented.

The original production signing key was unavailable in this environment. An in-place production 2.1.4 update requires that same private key, not a replacement certificate. No private credentials or signing keys were committed. The API-only relay and separate Web Player were not changed or redeployed. Prior release artifacts and native Drive/background-monitoring code were preserved.
