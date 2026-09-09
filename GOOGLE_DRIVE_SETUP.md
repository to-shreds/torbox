# Google Drive setup for TorBox Drop 2.0.4

TorBox Drop uses Google Play services authorization and TorBox's server-side Google Drive integration. The phone authorizes access, then TorBox performs the file transfer directly to Google Drive. Media files are not downloaded to the Android device merely to be re-uploaded.

## One-time Google Cloud configuration

Before a signed app can authorize Google Drive, create an Android OAuth client in a Google Cloud project that has the Google Drive API enabled.

Google identifies an Android OAuth client by the app package and the signing certificate SHA-1. Use the certificate of the APK you actually install.

### Current private fresh-install 2.0.4 build

- Android package name: `app.jabs.torboxdrop`
- Signing certificate SHA-1: `6A:A2:64:52:85:F1:38:A3:83:F4:40:9E:C4:88:88:9C:73:46:48:B8`
- Requested Google Drive scope: `https://www.googleapis.com/auth/drive.file`

This certificate belongs to the private-use key generated for the fresh-install 2.0.4 build on September 9, 2026. It does not match the older v2.0.3 signing certificate, so an older installed build must be uninstalled before this private build is installed.

Do not put a Google client secret in the APK. If the OAuth consent configuration is in testing mode, make the Google account that will use TorBox Drop an allowed test user when Google requires it.

### Private-build signing policy

TorBox Drop builds are currently personal/private-use builds unless Jon expressly says otherwise. Preserve the current private signing key when convenient so later APKs can update this fresh-install line in place. If that key is unavailable later, do not block the build: generate a new signing key, sign the APK, clearly state that uninstall/reinstall is required, and register the replacement certificate SHA-1 with the Google Android OAuth client.

Public or third-party distribution is a separate signing/release decision and should not be inferred from this private-build policy.

## In the app

1. Open **Settings**.
2. Under **Google Drive**, enter the one global destination folder name you want TorBox Drop to use.
3. Tap **Connect Google Drive** and complete Google's authorization prompt.
4. Choose whether **Send new torrents to Google Drive by default** should be on or off.
5. When adding a torrent, the **Send to Google Drive when ready** choice can be changed for that torrent without changing the remembered global default.

The app creates or reuses one app-managed destination folder and saves its Drive folder ID locally. If TorBox is already holding the torrent, automation can begin promptly. Otherwise, the completion monitor waits until TorBox reports both `download_finished` and `download_present`.

Multi-file torrents are submitted as individual files. TorBox performs the cloud transfer and TorBox Drop tracks TorBox integration jobs. A request whose remote outcome is uncertain is not blindly repeated, because repeating it could create a duplicate Drive upload.

## Background behavior

Drive automation does not require **Notify when complete** to be enabled. A newly armed Drive request receives an immediate WorkManager pass and the ordinary periodic fallback. When Android permits the app's visible live-monitoring foreground service, Drive work can also use that faster polling loop.

If Android notification permission or the monitoring notification channel is disabled, Android cannot run that visible foreground service. The Drive request remains durable and continues through WorkManager, but completion detection may be slower because periodic WorkManager is deferrable.

## Release and troubleshooting notes

A debug APK uses a different signing certificate, so an Android OAuth client registered only for the private release certificate above will not authorize a debug build. For debug testing, create a separate Android OAuth client for the debug certificate.

If Google authorization reports a developer-configuration error, first verify the package name and SHA-1 registered for the Android OAuth client. Google notes that OAuth client configuration changes can take time to propagate.

The automated suite validates request construction, credential boundaries, durable recovery, duplicate suppression, account replacement and failure handling. A real Google consent flow, a live TorBox-to-Drive transfer and physical-device background behavior still require the configured environment and cannot be truthfully simulated by CI alone.
