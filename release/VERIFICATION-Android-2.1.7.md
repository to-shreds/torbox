# Android 2.1.7: regional CDN download fix

Verified 2026-10-16. This is a targeted Android fix, not a web or Render deployment.

## Failure and correction

The reported Files-panel error was `TorBox returned a URL containing the API credential.` The exception is thrown by `TorBoxApiClient.validateTemporaryUrl` before Android DownloadManager receives a request. The previous credential-bearing URL allowlist covered older `storage*.torbox.app` and `cdn.torbox.app` addresses but omitted TorBox's documented regional CDN domains.

`UrlSafety.isTorBoxDownloadHost` now also accepts the roots and proper subdomains of `tb-cdn.cx`, `tb-cdn.io`, `tb-cdn.pw`, `tb-cdn.sh`, `tb-cdn.st`, `tb-cdn.to`, and `tb-cdn.earth`. This list comes from [TorBox's official documentation](https://support.torbox.app/en/articles/13181686-accessing-torbox-with-nextdns). DNS-label boundary matching rejects lookalikes such as `tb-cdn.io.evil.test` and `evil-tb-cdn.io`. Existing HTTPS, user-info, API-origin, exact token-parameter and disclosure checks remain intact.

No real account URL or API key was captured. The old-code failure was reproduced with synthetic URLs under these documented domains. This demonstrates a concrete defect producing the reported error; it is not a completed download from Jon's account.

## Checks actually executed

- Standalone Kotlin baseline/regression harness: 193 checks. The old source failed 84 checks, corresponding to 28 legitimate host variants rejected at three internal download boundaries. The corrected source passed all 193 checks. Baseline blob `0076ffa51eae4a34673e9e48ed48ef68223e9531`; corrected blob `3535c5265a7163bd8359895529a3e80fd9402407`. These matched the repository source blobs.
- [Android CI run 38905718353](https://github.com/to-shreds/torbox/actions/runs/38905718353), job `116988658154`, completed successfully for source commit `8d6e624f4de8d391e54fec1eefd837e80187403b`: unit tests, lint, debug APK, unsigned minified/resource-shrunk release APK, separately installable preview APK, artifact uploads and release-directory publication all passed.
- Downloaded and parsed the CI JUnit reports: **287 tests in 40 suites, zero failures, zero errors, zero skipped**. This includes four new `RegionalCdnApiTest` methods and three new `RegionalCdnDeviceDownloadsTest` methods. Android lint passed with 24 nonblocking warnings; it was not warning-free.
- API fixtures cover individual torrent files, whole-torrent ZIPs and original-format web downloads across all seven domain families. They check exact torrent/web/file identifiers, `redirect=false`, original download URLs, credential redaction, and rejection of lookalike or untrusted destinations.
- Robolectric verifies that individual MKV and ZIP URLs actually enter Android DownloadManager's queue with the direct TorBox URL and the original extension, rather than a Render address. Other tests cover uppercase/trailing-dot hosts, percent-encoded credentials, credential disclosure blocks, wrong credential fields, cleartext and user-info rejection. These are simulated Android queue tests, not live media transfers.

## Published APK

[Install Android 2.1.7 preview](TorBox-Drop-v2.1.7-preview.apk)

- Package: `app.jabs.torboxdrop.preview217`; label: **TorBox Drop Preview 2.1.7**; versionCode `20107`; versionName `2.1.7`.
- Publication commit: `b3e41007a9096856fd4818df83d88549907c719a`.
- APK size: **25,270,703 bytes**.
- APK SHA-256: `48a8ad6677ac95e217e26bf43003a5ffb4a93c750ac14f6c2d23f348200b994a`.
- APK Git blob: `0c56878c81ae4e39aa1a9dd78b0b5322b77351d6`.
- Downloaded preview artifact `12048388388`, verified its ZIP digest, extracted the APK and checked its archive integrity, manifest identity strings and all seven CDN strings in compiled code. The APK's computed SHA-256 and Git blob match the published release and checksum file. The conversation attachment is this same APK.

The preview installs separately. Enter the key in the new preview, not in chat. Do not uninstall the original production app. The original production private signing key remains unavailable, so this is not an in-place update; preview Google Drive OAuth is unverified. All older release binaries remain unchanged.

## Preserved behavior and remaining acceptance

Native ordinary Download still sends the original file directly from TorBox to Android Downloads; it does not convert MKV/WebM files or send media through Render. The explicit YouTube-only background Save as MP4 flow introduced in 2.1.6 remains implemented and was not otherwise changed. Copy/Open still reject master-key-bearing links, and external Share retains its existing separate explicit disclosure/consent path. Readiness and exact file-identity checks are unchanged.

The website and Render backend were not changed. The older web relay's media routing is separate from this native download fix.

**Remaining acceptance:** open **TorBox Drop Preview 2.1.7**, sign in, use the ordinary download arrow for the previously failing finished torrent file, and confirm that Android reports progress and saves the original file in Downloads. A real transfer on Jon's phone and a physical-phone large-video MP4 conversion have not been tested here. Do not represent CI or simulated queue success as either result.
