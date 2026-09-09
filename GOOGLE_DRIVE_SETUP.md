# Google Drive setup for TorBox Drop 2.0.4

TorBox Drop uses Google Play services authorization and TorBox's server-side Google Drive integration. The phone authorizes access, then TorBox performs the file transfer directly to Google Drive. Media files are not downloaded to the Android device merely to be re-uploaded.

## One-time Google Cloud configuration

Before the production-signed app can authorize Google Drive, create an Android OAuth client in a Google Cloud project that has the Google Drive API enabled.

Use these exact production values:

- Android package name: `app.jabs.torboxdrop`
- Production signing certificate SHA-1: `A2:96:72:48:6C:21:30:67:76:10:A6:3B:2D:EC:E1:6A:49:C4:00:A0`
- Requested Google Drive scope: `https://www.googleapis.com/auth/drive.file`

Google identifies an Android OAuth client by its package name and signing-certificate SHA-1. Do not put a client secret in the APK. If the OAuth consent configuration is in testing mode, make the Google account that will use TorBox Drop an allowed test user when Google requires it.

The production SHA-1 above comes from the existing TorBox Drop v2 public release certificate. The corresponding private signing key is still required to produce an update-compatible APK.

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

A debug APK uses a different signing certificate, so the production OAuth client above will not authorize a debug build. For debug testing, create a separate Android OAuth client for the debug certificate.

If Google authorization reports a developer-configuration error, first verify the package name and SHA-1 registered for the Android OAuth client. Google notes that OAuth client configuration changes can take time to propagate.

The automated suite validates request construction, credential boundaries, durable recovery, duplicate suppression, account replacement and failure handling. A real Google consent flow, a live TorBox-to-Drive transfer and physical-device background behavior still require the configured production environment and cannot be truthfully simulated by CI alone.
