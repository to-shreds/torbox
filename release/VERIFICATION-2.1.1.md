# TorBox Drop 2.1.1 verification

## Change
Added and Cached are now separate aligned columns beside Name in both clients. Web columns remain present at every responsive breakpoint; Name stays pinned during horizontal scrolling. Native Active, Finished and AirLock use one horizontally scrollable explorer table with a fixed header at every density; Queue has its own Queued column. Density choices, file entry, share, Drive, AirLock, notifications and menus remain available.

The timestamp source is unchanged. Missing dates remain unknown. Cached means TorBox's cache timestamp, not an invented completion timestamp. Per-file timestamps are not fabricated.

## Source and checks
- Production source: `8c27b9d74300d08526760e8bf6d832bf1a3e674a`; final test/source checkpoint: `f6365116af51d05dbde9cb0f989eebdb375d011f`, PR #8.
- Native clean build: `clean testDebugUnitTest lintDebug assembleRelease`, successful. 267 tests in 35 suites, zero failures/errors/skips. Lint: 0 errors, 11 warnings. The obsolete assertion requiring tables only above 840dp was retired; table columns now apply at all widths.
- Web/relay: 30 tests passed, including 50,000 files/500 folders and 48 independent synthetic relay credentials.
- Chromium: all 17 scenarios passed, CI [36935900633](https://github.com/to-shreds/torbox/actions/runs/36935900633), screenshot artifact 11197279976. Checks cover real browser download bytes, streaming saves, failure fallback, sign-out/reload, expired keys, mutations and a 2,500-item account with only 100 DOM rows.
- Responsive assertions cover 320, 390, 740 and 1024px; desktop is 1440px. Date cells align with their headers; Added fits beside Name at 390px; horizontal scrolling exposes Cached while retaining Name; no page-level overflow; mobile file Download buttons stay inside the viewport. Phone, tablet and desktop screenshots were visually reviewed.

## Signed release
- Package `app.jabs.torboxdrop`, version 2.1.1 / 20101, min SDK 23, target SDK 36, non-debuggable.
- Signed APK: 2,535,916 bytes; SHA-256 `bcac40b3e556bff2c4da25a172e3d85aa9c1c8c0e2d8f270169d49dca83f687f`.
- Unsigned APK SHA-256: `9edb77b90ba862857bade92450a176b6ff9b5c477c2f1feb39f3f88904b4cdab`.
- Verified v1/v2/v3 signatures and 16 KB alignment. All 157 unsigned ZIP entries are byte-identical after signing.
- Certificate SHA-1 `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`, matching the existing private 2.0.7/2.1.0 signing identity. No uninstall is needed for that installed identity. Private signing material remains outside Git.
- Standalone HTML SHA-256: `4537b72f325d0b2db2f975537c47d57b492dab1b01d6ce53dfb9cb5f7bf06cd9`.

## Practical limits
No real TorBox API key or physical phone was supplied. Native tests include Robolectric behavior checks; the native table was compiled and linted, not exercised on a physical phone. Browser tests use the real relay against synthetic TorBox responses and synthetic file bytes. No real account item was changed. Existing live relay activation checks remain recorded in ACTIVATION-2.1.0.md; this update does not alter relay behavior.
