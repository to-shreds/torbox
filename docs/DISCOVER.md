# Cached movie and TV discovery

Open **Discover** in the bottom navigation. Choose Movies or TV, browse a supported Cinemeta catalog or search for a title, and select a cached release. Compact is the default view; Cozy and Detailed are available. Quality filters and release sorting let you narrow the results without opening the TorBox website.

Only torrent hashes reported cached by TorBox appear in the list. This is a browsable, filtered catalog, not an inventory of TorBox's entire cache. Each automatic batch checks at most 12 titles and up to 200 distinct torrent candidates per title. **Check next 12 titles** continues through the current catalog. **Stop checking** cancels the current batch; opening a result pauses the scan so that you can add a release immediately.

TV results apply to the season and episode shown above the list, initially S1 E1. They do not establish that a whole series is cached. A selected release may be a season pack, and its displayed size is the size of the whole torrent, not just that episode. The existing Files screen is available after the torrent has been added.

**Add cached torrent** rechecks the hash, then calls the existing Add flow with `add_only_if_cached=true`. That server-side restriction remains important because a cache check is a report of availability, not a reservation. Failed lookups and service outages are reported as errors, not silently treated as uncached results. Quality and codec are inferred from provider release labels; missing information stays unknown.

## Sources and privacy

Cinemeta supplies title metadata, and Torrentio's unconfigured public stream endpoint supplies candidate torrent hashes. The app uses TorBox's existing Main API client for cache checks and adds. It does not depend on Voyager or require a TMDB key.

The public-source HTTP client has no TorBox token provider, cookies, logging interceptor, or redirect following. TorBox credentials stay in the existing encrypted storage and native TorBox API path. Cinemeta and Torrentio still receive your IP address and the titles you request. No torrents are added while browsing. Add constructs a new magnet from the validated hash rather than forwarding a third-party URL or tracker list.

Search results are held in memory, not written to disk. Requests stop when Discover is closed or the app goes into the background. Returning after an account change clears the prior session's results.

Provider contracts: [Cinemeta](https://v3-cinemeta.strem.io/), [official Cinemeta catalog descriptors](https://github.com/Stremio/stremio-official-addons/blob/master/index.json), [Torrentio](https://torrentio.strem.fun/), and the [Stremio addon protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/advanced.md). TorBox's cache and cached-only Add behavior is implemented in `data/TorBoxApiClient.kt`.

## Builds and validation

Debug builds are named **TorBox Discover Preview**, with application ID `app.jabs.torboxdrop.preview`. They install alongside an existing release and have separate settings, so enter your TorBox key in the preview's Settings. Do not uninstall your current app to test this feature. Release builds keep the original application ID and require the existing release signing key for an in-place update; that key is not in the repository.

Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` for the automated checks and build outputs. Discovery has pure model tests and MockWebServer tests covering identifier validation, exact episode requests, duplicates, cache confirmation, encoded search paths, source errors, redirects, rate limits, and credential-free public requests.

`python3 tools/check_discovery_sources.py` is a separate read-only live probe of public metadata and torrent candidates. It does not query an authenticated TorBox account or add a torrent. CI reports that probe separately because a third-party outage should not be confused with a compilation or unit-test failure.

Before releasing, test on a device with a real TorBox account: browse and search both media types; select the intended TV episode; change density and quality; stop and resume a batch; open and dismiss a release sheet during a request; rotate and background the app; change accounts; add a cached torrent; then confirm that Downloads, Files, and Share still behave correctly. A successful build is not evidence that these authenticated device checks were performed.
