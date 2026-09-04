# TorBox Drop 2.0.1 release

`TorBox-Drop-v2.0.1.apk` is the current minified, resource-shrunk, zip-aligned distribution build.

- Application ID: `app.jabs.torboxdrop`
- Version: `2.0.1` (`versionCode 20001`)
- Minimum Android: API 23
- Target Android: API 36
- APK SHA-256: `5bac134ce344b317cc7c8cb037b4f4b14484238b6bb9a63491201c941541c4d9`
- Signing certificate SHA-256: `AA:AE:1A:1A:53:DE:80:CA:FE:F3:FF:98:B9:30:BF:83:A6:34:F8:6C:8B:F9:5F:D1:88:92:3B:4F:1A:C2:07:F7`

The public signing certificate is included for verification. The private key is deliberately not in this repository.

The supplied v1.0 APK used a different certificate. Uninstall v1.0 before installing the v2 line, then re-enter the TorBox token and preferences. An installed v2.0.0 build can be updated directly to v2.0.1 because both APKs use the same certificate.

v2.0.1 fixes system-navigation overlap, byte-consistent progress percentages, and file-list readability. The file sheet now supports folder navigation, breadcrumbs, recursive search, exact extension filters, and name, size, or type sorting.
