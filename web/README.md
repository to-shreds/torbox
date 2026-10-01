# TorBox Drop web client

Version 2.1.0. Source belongs to `to-shreds/torbox`; the GitHub Pages entry is `https://to-shreds.github.io/Misc/TorboxDrop/`.

## Use

After activation, enter your TorBox API key in the password field. The current-user API validates it before any account lists populate. Sign out or reload to clear the session. Your key is not saved. The Android APK is linked from the login page and desktop header.

Open a collection, then folders. Use breadcrumbs or Backspace to go up; search includes subfolders, and extension filters match exact extensions. The table supports natural names, size, added and cache dates. Missing dates are explicitly unknown. Individual files do not have fabricated timestamps; their collection's dates remain above the file table. A cache timestamp may predate adding a cached item, so it is never labeled Completed.

Download uses the browser's streaming file picker when supported. The stream is written in chunks without loading the whole file into memory, and a failed partial write is aborted. Otherwise the browser receives a direct TorBox link and retains a visible Save file fallback. Some TorBox storage URLs include your key, so browser-history disclosure must be confirmed first. Multi-file selection prepares one save link per file instead of triggering blocked download storms. Device downloads only tracks this tab's actions; the browser owns progress after a normal download handoff.

## Features and scope

| Feature | Android | Web |
| --- | --- | --- |
| Torrents and web downloads, folders, search, dates, sorting | Yes | Yes |
| Individual files, ZIP where supported, selection | Yes | Yes |
| Add magnet, URL or torrent file; queue/cached options | Yes | Yes |
| Queue start/delete, item delete, rename/tags, AirLock | Yes | Yes |
| Torrent pause/resume/reannounce | Yes | Yes |
| Google Drive folder routing and durable delivery | Yes | Requires Android app |
| Background completion monitoring/notifications | Yes | Requires Android app |
| Saved credentials | Android Keystore encryption | Never saved |

No live TorBox API key is included in fixtures or source. Tests use deterministic TorBox responses and synthetic file bytes. Real account permissions, quota, cache availability and device download settings still affect live actions.

## Activation

Live TorBox CORS checks on 2026-10-01 rejected GitHub Pages origins. A static page alone cannot authenticate. `relay/server.mjs` is a dependency-free Node 22 relay with a fixed upstream, route/method allowlists, per-request bearer authentication, bounded request/response sizes, timeout, strict origins, and no storage or media proxy. `render.yaml` specifies a free Render service.

The Render connector requires explicit confirmation of **My Workspace** before creation. No service has been created or selected. Keep `<meta name="torbox-api" content="">` empty and login disabled until a deployment URL has been returned and its health/preflight/auth boundary verified. Then set the returned URL plus `/api/`, run `node web/build.mjs`, and synchronize `web/index.html` to `Misc/TorboxDrop/index.html` and `Misc/TorboxDrop/TorBox-Drop.html`. Never guess an unclaimed relay hostname.

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
