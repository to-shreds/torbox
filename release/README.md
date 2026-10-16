# TorBox Drop release downloads

## Android 2.1.7 download-fix preview

[Download Android 2.1.7 preview](TorBox-Drop-v2.1.7-preview.apk) · [Verification](VERIFICATION-Android-2.1.7.md) · [Checksums](SHA256SUMS-Preview-2.1.7.txt)

Fixes the native security check that rejected credential-bearing links from TorBox's documented regional CDN domains before Android Downloads could start. Ordinary files and whole-torrent ZIPs remain direct TorBox-to-device downloads, without Render media routing or conversion. The explicit YouTube-only background Save as MP4 feature from 2.1.6 is preserved.

This installs separately as **TorBox Drop Preview 2.1.7**, package `app.jabs.torboxdrop.preview217`, versionCode `20107`. Open the new preview and enter your API key there. Keep the original app installed; this does not update its package or verify preview Google Drive OAuth. The production private signing key is unavailable. All earlier binaries remain intact.

Verified: 287 Android tests passed, lint and APK builds passed, and 193 local URL-safety checks passed. A real download on Jon's phone remains the final acceptance check. APK SHA-256: `48a8ad6677ac95e217e26bf43003a5ffb4a93c750ac14f6c2d23f348200b994a`.

## Retained production 2.1.1

[Android APK](TorBox-Drop-v2.1.1.apk) · [Web client](https://to-shreds.github.io/Misc/TorboxDrop/) · [Verification](VERIFICATION-2.1.1.md)

Added and Cached now have their own explorer columns on phones, tablets and desktops. Swipe sideways for more columns. The native Queue also has a Queued date column.

This is the current minified, resource-shrunk and zip-aligned release: package `app.jabs.torboxdrop`, versionCode `20101`, minimum Android API 23, target API 36. It uses the retained private signing certificate and installs over the matching 2.0.7 or 2.1.0 build without uninstalling.

APK SHA-256: `bcac40b3e556bff2c4da25a172e3d85aa9c1c8c0e2d8f270169d49dca83f687f`. Certificate SHA-1: `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`.

Historical APKs remain available. They may use a different signing identity; the current release must be compared with the installed app's signer. Private keys and passwords are never published.
