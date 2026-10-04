# TorBox Drop web 2.1.2 verification

Verified 2026-10-04 UTC. Request: make account quotas and usage prominent above the file explorer.

## Result
The authenticated page has five responsive summary cards: plan/expiry, active slots including seeding, AirLock storage used and available against the published plan allowance, rolling 30-day bandwidth with a dynamic fair-use baseline, and the torrent/web queue. The cards remain above every library, folder and device-download view. Filters and pagination do not change account totals.

Refresh reads the account and full lists even while browsing a folder. Confirmed additions, edits and controls also refresh usage. Failed reads retain visibly stale last known values; missing or incomplete values are Not reported rather than invented zeroes. Pro slot and AirLock totals include a read-only, paginated Usenet list. No Usenet management UI or mutations were added.

## Source and versions
- PR [#9](https://github.com/to-shreds/torbox/pull/9), final source checkpoint `8a8f854133de943d8987b5cd5ca79cb577b1cb95`, merged as `be37ed40f8c237bd36fbcc4b1736f6597e77aa12`.
- Post-publication numeric hardening source: `34dba2f8e529ece0e9916a98c4d653225a83f963`. The live API relay's routes and behavior are unchanged by this frontend parser correction.
- Web/package/relay version: 2.1.2. Android remains 2.1.1 / 20101, package app.jabs.torboxdrop. This change does not rebuild or replace the signed APK.
- Generated self-contained HTML: 76,965 bytes, SHA-256 `a698c38c5775cdb159066cca9cf92b356bb6f0ec6ddee5368dbad2eb0836b4be`.
- Misc publication commit: `bb736b388812d943dfbbd58771b3e21e0aa2bd0a`. Both HTML copies match the generated source.

## Automated verification
- Final numeric-hardening [main CI 37243904839](https://github.com/to-shreds/torbox/actions/runs/37243904839) passed deterministic generation, **37 core/API checks** and all **23 browser scenarios** for source `34dba2f8e529ece0e9916a98c4d653225a83f963`. Malformed Boolean/array/object/blank numeric values remain unknown after normalization, while valid zero stays zero. Latest [screenshot artifact 11318651497](https://github.com/to-shreds/torbox/actions/runs/37243904839/artifacts/11318651497), SHA-256 `3e2355e6a3ef1f22d9f9fc4f70a6dd5f89fe64d09e07c14eb667cee723210ac8`, expires 2027-01-02. The earlier reviewed screenshots below show the same unchanged layout.
- Final PR [web CI 37242984622](https://github.com/to-shreds/torbox/actions/runs/37242984622): deterministic build check, **36 core/API/stress checks**, **23 Chromium scenarios**, all passed without skips.
- Merged-main [web CI 37243067085](https://github.com/to-shreds/torbox/actions/runs/37243067085): all gates passed again.
- [Chromium evidence](https://github.com/to-shreds/torbox/actions/runs/37242984622/artifacts/11318570264): eight desktop/mobile/tablet screenshots, reviewed. Artifact SHA-256 `603ba8c3d21b378f0561ae7d0589d1f4140a3ce034317db0f902e61ea44da9c5`; GitHub retention expires 2027-01-02.
- New coverage: correct plan ID mapping, numeric zero versus missing/malformed values, seeding versus cached inactive items, AirLock-only byte totals, actual rolling bandwidth versus lifetime totals, read-only Usenet pagination, allowance changes during folder browsing, failed refresh/recovery, unknown plans, sign-out/reload, and delayed quota replies across sessions.
- Responsive widths: 320, 390, 740, 1024 and 1440 px; summary cards remain contained and the existing sortable date columns retain their alignment.
- Existing real-byte browser downloads, streaming writes and disk-failure fallback, rejected deletion, preserved edit metadata, safe filenames and expired-key recovery still pass.
- Existing stress cases: 50,000 files / 500 folders, 2,500 paginated records / at most 100 rendered rows, and 48 isolated synthetic relay accounts.
- Automatically triggered [Android main CI 37243067216](https://github.com/to-shreds/torbox/actions/runs/37243067216) passed unit tests, lint, debug build and release shrink/build. Native application code and signed release artifact are unchanged.

## Published deployment
- Free API-only relay service `srv-davdjv0u01pc73ec1phg` in the previously authorized My Workspace, `tea-dakujgmk1f9s73d2v8ng`.
- Render deploy `dep-db1dsdmgekts73dc9ej0` is live from merged source `be37ed40f8c237bd36fbcc4b1736f6597e77aa12`. A cache-cleared deploy was used after the service remained on its original 2.1.0 build despite autoDeploy=yes.
- The only added routes are authenticated GET user/stats and GET usenet/mylist; bandwidth query values are validated. File/media bytes still go directly from TorBox to the device. No database, stored key, paid tier or separate Web Player changes.
- **11 live relay smoke checks passed**: health/version 200; stats/Usenet/GitHub/file-origin preflights 204; missing-key requests 401; real upstream invalid test-key rejection 403/BAD_TOKEN; invalid grouping 400; hostile origin 403; Usenet POST 405; unknown route 404. See [live JSON evidence](VERIFICATION-Web-2.1.2-live.json).
- Recent Render error logs were empty.
- Misc [Pages run 37243232913](https://github.com/to-shreds/Misc/actions/runs/37243232913) passed on attempt 2 after an initial deployment OIDC-token timeout. No product code changed for the retry.
- A live browser confirmed web version 2.1.2, enabled password-style API-key entry, configured relay, the existing Android 2.1.1 link, and zero populated quota cards before authentication.

- Final numeric-hardened HTML [Pages run 37244025258](https://github.com/to-shreds/Misc/actions/runs/37244025258) passed on its first attempt; both current HTML copies match the hash above.

## Allowance semantics
Published limits were checked on 2026-10-04 using TorBox's [account restrictions](https://support.torbox.app/en/articles/9836418-account-restrictions), [AirLock guide](https://support.torbox.app/en/articles/15417147-torbox-airlock), [abuse-system guide](https://support.torbox.app/en/articles/10336778-the-torbox-abuse-system), [official user stats response](https://www.postman.com/torbox/torbox-api/request/7wd3xgu/get-user-stats), and [official plan IDs](https://www.postman.com/torbox/torbox-api/request/rf7iu10/get-user-data).

| API plan | Name | Base active slots | AirLock allowance | Dynamic fair-use baseline |
| --- | --- | --- | --- | --- |
| 0 | Free | 1 | 0 B | 5 TB |
| 1 | Essential | 3 | 300 GB | 10 TB |
| 2 | Pro | 10 | 1 TB | 30 TB |
| 3 | Standard | 5 | 500 GB | 20 TB |

Reported additional concurrent slots are included, subject to TorBox's published 10-slot maximum. Usage follows the API's active/AirLock flags, including seeding, and requires all relevant lists. Unknown flags, sizes and plans stay unknown. Bandwidth sums the endpoint's documented 30-day buckets; it never substitutes lifetime downloaded bytes. Fair-use baselines are not fixed quotas, so no invented remaining bandwidth is shown. Published plan allowances are labeled as such. Current official docs disagree about maximum Pro download size, so that unsupported quota is omitted.

## Practical limits
No real TorBox account key or physical phone was supplied. Authenticated quota values and download permissions are tested using deterministic fixtures, not Jon's account. Live smoke checks use a deliberately invalid test key only. The API key is never requested in chat or saved by the page. Android Drive automation, Delete markers, share confirmation and background monitoring remain on the existing signed 2.1.1 release.
