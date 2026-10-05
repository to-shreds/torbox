# TorBox Drop web client

Web version 2.1.3; Android APK version 2.1.1. Source belongs to `to-shreds/torbox`; the GitHub Pages entry is `https://to-shreds.github.io/Misc/TorboxDrop/`.

## Use

Enter your TorBox API key in the password field. The current-user API validates it before any account lists populate. Sign out or reload to clear the session. Your key is not saved. The Android APK is linked from the login page and desktop header.

A prominent Account & quotas panel appears directly under the app header after login. It shows your reported plan and expiry, active slots (including seeding), AirLock storage used and available against the published plan allowance, bandwidth consumed in the past 30 days, and the torrent/web queue. Values cover the full loaded account, independent of filters, pagination and folder navigation. Pro account slot and AirLock totals also include a read-only, paginated Usenet list; Usenet management is not added to the library UI.

Refresh reloads account information, bandwidth and complete account lists even while browsing a folder. Successful additions, controls and edits refresh usage too. Failed reads keep last known values with a stale notice; unreported values and incompletely loaded lists stay “Not reported”, including missing active/AirLock flags. Zero usage remains zero. Sign out, reload, expired authentication and late-response races clear all metrics as well as credentials.

Allowances were checked against TorBox’s official documentation on 2026-10-04. API plan IDs are 0 Free, 1 Essential, 2 Pro, 3 Standard. Slot base allowances are 1/3/10/5 (plus reported extra concurrency, subject to the documented 10-slot maximum); AirLock allowances are 0/300 GB/1 TB/500 GB. These are published plan allowances, not a fabricated account quota endpoint. `user/stats?general=false&bandwidth=true&bandwidth_grouping=day` supplies the rolling bandwidth buckets. Fair-use baselines are 5/10/30/20 TB; they are minimum baselines within TorBox’s dynamic abuse system, not fixed caps. Never subtract usage from a baseline to claim remaining bandwidth. Lifetime transfer totals are not used as rolling usage. Unknown plans receive no invented Free limits. Current docs disagree about the Pro maximum individual-download size, so no unsupported file-size quota is displayed.

Sources: [account restrictions](https://support.torbox.app/en/articles/9836418-account-restrictions), [AirLock allowances](https://support.torbox.app/en/articles/15417147-torbox-airlock), [fair use](https://support.torbox.app/en/articles/10336778-the-torbox-abuse-system), [official bandwidth response](https://www.postman.com/torbox/torbox-api/request/7wd3xgu/get-user-stats), and [official plan IDs](https://www.postman.com/torbox/torbox-api/request/rf7iu10/get-user-data).

Open a collection, then folders. Use breadcrumbs or Backspace to go up; search includes subfolders, and extension filters match exact extensions. The table supports natural names, size, added and cache dates. Added and Cached always occupy separate, sortable columns, including on phones and tablets. Swipe the table sideways to see more columns; Name stays pinned so dates remain associated with the correct item. Missing dates are explicitly unknown. Individual files do not have fabricated timestamps; their collection's dates remain above the file table. A cache timestamp may predate adding a cached item, so it is never labeled Completed.

Use **List spacing** beside Sort to choose **Compact · one line**, **Cozy**, or **Detailed**. Compact is the default: tighter rows with name/type/tags, date/age and status/progress inline. Cozy uses the familiar spacing; Detailed gives wrapped names and supporting information more room. The same choice applies to the library, queue and file browser. Switching spacing preserves filters, sort, page, folder and file selection without refetching data. Long text retains its full accessible name and hover text; item/file details also show it in full. The choice lasts only for this tab session and resets to Compact on sign out or reload, with no browser storage. All date and action columns remain available.

Download uses the browser's streaming file picker when supported. The stream is written in chunks without loading the whole file into memory, and a failed partial write is aborted. Otherwise the browser receives a direct TorBox link and retains a visible Save file fallback. Some TorBox storage URLs include your key, so browser-history disclosure must be confirmed first. Multi-file selection prepares one save link per file instead of triggering blocked download storms. Device downloads only tracks this tab's actions; the browser owns progress after a normal download handoff.

## Features and scope

| Feature | Android | Web |
| --- | --- | --- |
| Torrents and web downloads, folders, search, dates, sorting | Yes | Yes |
| Compact, Cozy and Detailed list spacing | Yes | Yes |
| Individual files, ZIP where supported, selection | Yes | Yes |
| Add magnet, URL or torrent file; queue/cached options | Yes | Yes |
| Queue start/delete, item delete, rename/tags, AirLock | Yes | Yes |
| Torrent pause/resume/reannounce | Yes | Yes |
| Google Drive folder routing and durable delivery | Yes | Requires Android app |
| Background completion monitoring/notifications | Yes | Requires Android app |
| Saved credentials | Android Keystore encryption | Never saved |

No live TorBox API key is included in fixtures or source. Tests use deterministic TorBox responses and synthetic file bytes. Real account permissions, quota, cache availability and device download settings still affect live actions.

## Hosting and maintenance

Live TorBox CORS checks on 2026-10-01 rejected GitHub Pages origins. A static page alone cannot authenticate. `relay/server.mjs` is a dependency-free Node 22 relay with a fixed upstream, route/method allowlists, per-request bearer authentication, bounded request/response sizes, timeout, strict origins, and no storage or media proxy. `render.yaml` specifies a free Render service.

Jon confirmed **My Workspace** on 2026-10-01. The free service `srv-davdjv0u01pc73ec1phg` is live at `https://torbox-drop-api.onrender.com`, deployed from this repository's main branch. Health, GitHub Pages/file-origin preflight, missing-key and real upstream invalid-key rejection, hostile-origin rejection, and unknown-route rejection were verified. No TorBox key is configured on the server.

The verified `/api/` address is in the template's torbox-api meta value. After edits, run `node web/build.mjs` and synchronize `web/index.html` to `Misc/TorboxDrop/index.html` and `Misc/TorboxDrop/TorBox-Drop.html`. If this service is ever removed/replaced, empty that meta value to disable key entry until the new deployment URL is verified. Never guess an unclaimed relay hostname.

No TorBox key belongs in Render environment variables. The relay accepts the key only on the current user's request and forwards it to TorBox. Default allowed origin is `https://to-shreds.github.io`; file-origin HTML is supported with `Origin: null` and still requires authentication. A free service may need a minute to wake up.

## Development and verification

```sh
npm ci
npm run build:web
npm test
npx playwright install --with-deps chromium
npm run test:browser
DROP_LOCAL=1 PORT=8765 npm start
```

Local mode serves the self-contained HTML at localhost and routes its API to the same server. Tests run the actual relay against synthetic TorBox responses, verify exact downloaded bytes, exercise failures and session clearing, and save desktop/mobile screenshots. GitHub Actions runs both suites. No browser runtime dependencies or third-party scripts are loaded by the published HTML.
