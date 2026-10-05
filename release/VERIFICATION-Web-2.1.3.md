# TorBox Drop web 2.1.3 verification

Verified 2026-10-05. Web version 2.1.3; Android remains signed 2.1.1 / 20101; the API-only relay remains 2.1.2.

## Requested result

List spacing beside Sort offers Compact · one line, Cozy and Detailed. Compact is the default and puts library names/type/tags, absolute dates/ages, and status/progress on one line. File names, folder counts and search paths also stay inline. Cozy preserves the existing spacing. Detailed adds space for wrapping names and supporting information.

The control changes CSS on the existing table, with no API calls or row rebuild. Page, filters, sort, folder and selection remain intact. The same spacing applies to library, queue and folder/file browsing; it survives refresh and navigation in the tab and resets to Compact on sign-out/reload. No browser storage was added.

Added/Cached remain real columns, Name remains pinned during library scrolling, full names/paths/status remain available through accessible text, titles and details, and file actions remain usable. Compact file More is an ellipsis button with the accessible name More. Account & quotas stays prominently above every explorer view.

## Source and artifact

- Source checkpoint: `eee4599c7ec5cf8b1cab0677e2c9d7d034d82959`, [PR #10](https://github.com/to-shreds/torbox/pull/10).
- Self-contained generated HTML: `web/index.html`, 82,067 bytes.
- SHA-256: `f9bea683089a9838dfba330fc9852c0c365d7dfb3ac9419e2b17ac03d6dab9b0`.
- Publication targets: `to-shreds/Misc/TorboxDrop/index.html` and `TorBox-Drop.html`.
- No Android source, APK, signing identity, relay routes or API behavior changed.

## Automated verification

Final [PR web CI 37259488088](https://github.com/to-shreds/torbox/actions/runs/37259488088) and [main web CI 37259648606](https://github.com/to-shreds/torbox/actions/runs/37259648606) passed deterministic HTML regeneration, **37 core/API/security/stress checks** and **27 Chromium scenarios**, with zero failures or skips. PR #10 merged as `3882cbdc6ffbf64293710c899819fb7b60943975`.

The suite includes 37 core/API/security/stress checks and 27 Chromium scenarios. New coverage verifies:

- Compact rows stay at or below 40 px in the 1440×960 synthetic library and fit more fully visible entries than Cozy/Detailed. Both metadata and dates/ages share a line; a long filename is ellipsized while retaining its complete accessible name and title. Active progress and speed remain textual inline information.
- All choices preserve page/search/sort without API requests, preserve folder and selected files, survive refresh and queue navigation, and reset with the session.
- Every choice retains Added/Cached alignment, pinned names and contained scrolling at 320, 390, 740, 1024 and 1440 px. File actions fit inside their own column and retain enabled accessible controls.
- Actual browser downloads return the exact synthetic bytes in Compact, Cozy and Detailed.

Existing scenarios cover streaming saves, aborted partial writes and working browser fallback, credential-link disclosure, failed deletion, safe filename rendering, metadata-preserving rename, single add actions, selected-file link preparation, partial service failures, rejected credentials, late-response races, and quota completeness/staleness/session clearing.

Stress coverage remains 50,000 files in 500 folders, 2,500 API records with at most 100 DOM rows, and 48 isolated synthetic relay accounts.

## Visual verification

Desktop Compact/Cozy/Detailed, phone library/files and tablet screenshots are produced by Chromium CI. Final [artifact 11323738833](https://github.com/to-shreds/torbox/actions/runs/37259488088/artifacts/11323738833) contains 17 screenshots, expires 2027-01-03, and has SHA-256 `c7b552467930d1365a712ff8a5f0f9eec38509520fc5498e27ec591465200fd6`. Its archive hash was verified; desktop and representative phone/tablet layouts were reviewed. A first passing run identified a slightly clipped phone file action, corrected by allocating room for the buttons and reducing file-cell padding; the final scenario explicitly checks action containment.

## Published verification

Both Misc HTML copies were published in `ed3c2d106ed9942b283315f49607f9a841497308`. [Pages run 37259650624](https://github.com/to-shreds/Misc/actions/runs/37259650624) succeeded on its first attempt. Both public URLs returned HTTP 200 and exactly 82,067 bytes with the matching SHA-256 above. The live browser confirmed web 2.1.3, enabled key entry, the configured relay, the three spacing options with Compact selected, the Android 2.1.1 APK link, and zero populated account rows/quota cards before authentication.

The live page requires the key in its password field before populating account information. The choice and all account state stay in memory. No real TorBox key was supplied; real-account permissions/downloads and physical Android use remain user-side checks. The native 2.1.1 APK, Drive routing, deletion/share confirmations and background monitoring retain their previously verified baseline.

## Prior verified baseline

Quota semantics, official allowance sources and live relay checks are preserved in `release/VERIFICATION-Web-2.1.2.md` and `release/VERIFICATION-Web-2.1.2-live.json`. The relay was already deployed and verified for those APIs; no new relay deployment is required for spacing.
