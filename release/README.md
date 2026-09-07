# TorBox Drop 2.0.2 release

`TorBox-Drop-v2.0.2.apk` is the current minified, resource-shrunk, zip-aligned distribution build.

- Application ID: `app.jabs.torboxdrop`
- Version: `2.0.2` (`versionCode 20002`)
- Minimum Android: API 23
- Target Android: API 36
- APK SHA-256: `51a74a4306acc7c826cdb27eac01aaa9111426ee2c4c6aab314a0b0ed8094b77`
- Signing certificate SHA-256: `AA:AE:1A:1A:53:DE:80:CA:FE:F3:FF:98:B9:30:BF:83:A6:34:F8:6C:8B:F9:5F:D1:88:92:3B:4F:1A:C2:07:F7`

The public signing certificate is included for verification. The private key is deliberately not in this repository.

The supplied v1.0 APK used a different certificate. Uninstall v1.0 before installing the v2 line, then re-enter the TorBox token and preferences. An installed v2.0.0 or v2.0.1 build can be updated directly to v2.0.2 because all v2 APKs use the same certificate.

v2.0.2 fixes download-list reconciliation and the Add completion flow. A successful manual Add now returns to Downloads with an immediate visible row, while direct ID reconciliation covers TorBox aggregate-list lag. Clipboard detection and the in-app `.torrent` picker no longer submit anything until Add to TorBox is pressed. Queue refresh failures no longer suppress successful download refreshes.
