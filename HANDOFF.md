# TorBox Drop: Handoff

Repository source, CI, release artifacts and real deployments control implementation. `to-shreds/ProjectStatus/projects/torbox/STATUS.md` controls readiness. Update HANDOFF first; update STATUS as the final persistence step.

## Current state: 2026-10-16

**Android 2.1.7 preview is built, tested and published. The concrete regional-CDN download blocker is corrected; a real file transfer on Jon's phone remains unverified.** The latest report was an ordinary torrent's Files panel displaying `TorBox returned a URL containing the API credential.` This exception occurs before Android DownloadManager can enqueue a transfer.

[Install Android 2.1.7 preview](https://github.com/to-shreds/torbox/raw/refs/heads/main/release/TorBox-Drop-v2.1.7-preview.apk) · [Detailed verification](release/VERIFICATION-Android-2.1.7.md)

The new app is labeled **TorBox Drop Preview 2.1.7**, package `app.jabs.torboxdrop.preview217`, versionCode `20107`. It installs separately; enter the key in this new app. Do not uninstall the original production app. The matching production private signing key is unavailable, and preview Google Drive OAuth is not verified.

## Latest correction and verification

`util/UrlSafety.kt` previously recognized older TorBox storage/CDN hosts but omitted TorBox's officially documented regional CDN families. It now accepts exact roots and DNS-label-boundary subdomains of `tb-cdn.cx`, `.io`, `.pw`, `.sh`, `.st`, `.to` and `.earth`. Official source: https://support.torbox.app/en/articles/13181686-accessing-torbox-with-nextdns . It still requires HTTPS, no user-info and the exact configured token in the expected token parameter for credential-bearing destinations. Lookalike hosts, API origins and unexpected credential locations remain rejected. Copy/Open remain blocked for master-key links; Share retains its existing separate explicit disclosure/consent path.

Only the common URL allowlist changed in functional app code. Version/package and publication filenames advanced to 2.1.7, and two regression-test files were added. MainViewModel, file readiness/identity checks, Android DownloadManager, MP4 processing, web source and Render backend were otherwise untouched. No real account key or download URL was retrieved. Synthetic official-domain URLs reproduce the old error; no unseen real hostname is asserted.

- Tested implementation commit: `8d6e624f4de8d391e54fec1eefd837e80187403b`. Fixed UrlSafety blob: `3535c5265a7163bd8359895529a3e80fd9402407`.
- Local Kotlin regression: **193 checks; old code failed 84, fixed code failed zero**. Covers all seven CDN families, root/regional/ZIP/case variants, disclosure protection, encoding and hostile hosts.
- [Android CI 38905718353](https://github.com/to-shreds/torbox/actions/runs/38905718353), job `116988658154`, completed successfully: unit tests, lint, debug build, minified/shrunk unsigned release build, separately installable preview build and publication.
- Downloaded CI reports: **287 tests, 40 suites, zero failures/errors/skips**. Seven new test methods in `RegionalCdnApiTest.kt` and `RegionalCdnDeviceDownloadsTest.kt` cover exact torrent/web/file IDs and actual simulated Android queue insertion for direct MKV/ZIP URLs. Lint passed with 24 nonblocking warnings. These are controlled API/Robolectric tests, not a real media transfer.
- Release commit: `b3e41007a9096856fd4818df83d88549907c719a`. APK: `release/TorBox-Drop-v2.1.7-preview.apk`, **25,270,703 bytes**, SHA-256 `48a8ad6677ac95e217e26bf43003a5ffb4a93c750ac14f6c2d23f348200b994a`, Git blob `0c56878c81ae4e39aa1a9dd78b0b5322b77351d6`.
- Preview artifact `12048388388` was downloaded and extracted into the conversation. Its APK hash and Git blob match the published release; archive integrity, manifest identity strings and the seven compiled CDN strings were checked. See `release/SHA256SUMS-Preview-2.1.7.txt`.

## Preserved MP4 and other components

**Android MP4:** The 2.1.6 feature remains: explicit YouTube-only **Save as MP4 (background)** on Add or eligible finished web-download file rows. `YouTubeMp4Policy` gates eligibility; `YouTubeMp4Worker` uses account-scoped unique WorkManager jobs, waits for exact ready/present source identity, downloads original bytes directly from TorBox to local staging, converts WebM with Media3 H.264/AAC or copies genuine MP4, and automatically publishes the completed output to Android Downloads. No keys or CDN URLs in WorkData/logs. Normal Download must never convert. Android 10+ and enough space for original plus export are required. System background limits, codecs, interruption/retry and large-file conversion on a physical handset remain unverified. Original MP4 implementation was tested at `853ba4aade555e5ee01c943af90c4078622486de`, CI `38667447751`.

**Old APKs:** Production 2.1.1 and previews 2.1.4, 2.1.5 and 2.1.6 remain untouched. Production certificate SHA-1 `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`; no matching private key is available here. The 2.1.6 APK remains 25,270,697 bytes, SHA-256 `1df970ad3e30aa4df45b03074a7c85a53ce468411ee76f2ca8c54ecf53da3133`.

**Web and Render:** Not modified or redeployed for this Android fix. Last verified web is 2.1.5 at https://to-shreds.github.io/Misc/TorboxDrop/ , mirrored from `web/index.html` into `to-shreds/Misc/TorboxDrop/index.html` and `TorBox-Drop.html`, blob `c74a84ed95c3bf726f1a01715d0f137733eb9884`. Prior web CI `38658559679` passed 45 core/relay checks and 31 browser scenarios; Pages run `38658816222` succeeded. Last recorded Render backend is 2.1.5 at https://torbox-drop-api.onrender.com/ , service `srv-davdjv0u01pc73ec1phg`, deployment `dep-db81ljbncjis73e9batg`, workspace `tea-dakujgmk1f9s73d2v8ng`. Its existing web download relay can stream media through Render. That is a separate path: native ordinary and MP4 transfers go directly from TorBox to the phone.

## Do not break and next action

Preserve credentials secrecy, exact TorBox IDs, ready AND present gating, source formats, optional YouTube-only MP4 conversion, quotas/dates/density, Drive and notification integrations, and prior binaries. No new native media relay or Render bandwidth. Official API contract: https://api.torbox.app/openapi.json . Do not equate CI success with a real download or physical-phone conversion.

**Next:** Jon opens **TorBox Drop Preview 2.1.7**, enters his key there and taps the normal download arrow on the previously failing completed torrent file. Confirm Android progress and the original file in Downloads; a ZIP is also covered by the fix. Do not use an older installed preview to test this patch. If failure persists, collect the visible error/version and Android download status, never a raw credential-bearing link. Physical-phone YouTube MP4 acceptance remains a separate unresolved check.
