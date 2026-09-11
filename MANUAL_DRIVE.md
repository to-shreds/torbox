# TorBox Drop 2.0.6: manual Google Drive sends

Tap the Google Drive icon next to a ready torrent in Finished or AirLock. The icon appears in Compact, Cozy, Detailed and wide layouts. Enter a destination folder name and press **Send to Drive**. Opening the prompt or pressing Cancel does not authorize, create a folder, submit a job, add a torrent or delete anything.

The app creates or reuses an app-managed folder in My Drive and copies individual files. It does not delete the TorBox original. This is a folder-name prompt, not a browser for arbitrary pre-existing Drive folders. Connect Google Drive in Settings first. Progress and any errors appear under Settings > Google Drive, including the selected destination. The global automatic-upload choice and destination are not changed by a manual request.

## Folder isolation

TorBox's published `AddToGoogleDrive` request schema has `id`, `file_id`, `zip`, `type` and `google_token`, but no per-upload folder argument. Its documented destination is the account-wide `google_drive_folder_id`. This implementation does not invent an unsupported folder parameter or redirect media through the phone.

The app persists the chosen folder with each request and reserves the TorBox account destination for accepted uploads until their jobs are terminal. Different requests wait their turn. The previous account folder is saved before any setting change and restored once uploads finish; uncertain responses, failed lookups or a restart do not allow it to switch destinations underneath a possibly active job. App defaults stay unchanged. Automatic requests snapshot their destination when submission begins. The app also checks active TorBox integration jobs and will not change the destination around uploads it can see from other clients.

**Do not concurrently start Drive uploads or change TorBox's Drive destination through the website or another app while these requests are pending.** TorBox has no documented cross-client lock or atomic per-job destination API. The app can detect observed setting changes and stop making conflicting writes, but it cannot prevent another client from changing TorBox's shared setting between requests. Restoring a temporary folder after completion requires the app's foreground or scheduled background check to run.

Repeated confirmations for the same torrent hash and folder reuse its existing request rather than uploading the same files again. A different folder creates a separate request that waits its turn. Uncertain uploads are never blindly resubmitted. Files TorBox flags as infected and files above its documented Google integration limit are not sent.

## Preservation and verification

Built on the existing 2.0.5 external-share confirmation fix. Incoming shares still stop at Add before creation. The Drive journal migrates from schema 2 to 3 without resetting old transfer claims, saved connection settings or notifications. No new Google permission or OAuth client is needed when the build uses the existing private signing key.

Targeted automated tests cover manual admission, cancel/no-action boundaries, folder-name validation, a stale/replaced/deleted torrent, account changes, duplicate confirmation, retained folder IDs after failed setup/restart, default preservation, schema migration, REST contract/error boundaries, serialized manual and automatic submissions, uncertain remote outcomes, and restoration. Full JVM regression, lint and both APK builds are required before release. Separate final security/credential and state/recovery checks are run on the same candidate. The real Google/TorBox account and physical-device UI checks cannot be claimed as passed by these mocks.

## Official contracts consulted

- https://api.torbox.app/openapi.json
- https://support.torbox.app/en/articles/9837704-torbox-google-drive-integration
- https://developers.google.com/workspace/drive/api/guides/folder

The APK is for Jon's private use. Reuse the current private key when available, otherwise generate a new one and clearly require a fresh install rather than blocking release. Never commit the key or its credentials.
