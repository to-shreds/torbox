# TorBox Drop 2.0.3 release

`TorBox-Drop-v2.0.3.apk` is the current minified, resource-shrunk, zip-aligned distribution build.

- Application ID: `app.jabs.torboxdrop`
- Version: `2.0.3` (`versionCode 20003`)
- Minimum Android: API 23
- Target Android: API 36
- APK SHA-256: `0073b3a6b43d87d012a65c3a93c5a55141c9ac4a575d9b975367e7a7adec1d77`
- Signing certificate SHA-256: `AA:AE:1A:1A:53:DE:80:CA:FE:F3:FF:98:B9:30:BF:83:A6:34:F8:6C:8B:F9:5F:D1:88:92:3B:4F:1A:C2:07:F7`

The public signing certificate is included for verification. The private key is deliberately not in this repository.

The supplied v1.0 APK used a different certificate. Uninstall v1.0 before installing the v2 line, then re-enter the TorBox token and preferences. Any installed v2 build can be updated directly to v2.0.3 because all v2 APKs use the same certificate.

v2.0.3 is a targeted per-file Share repair. Share now requests a new temporary CDN URL directly for the selected file, displays progress and failures inside the file sheet, closes that sheet on success, and then opens Android's normal share chooser. Existing token-leak protections remain in force.
