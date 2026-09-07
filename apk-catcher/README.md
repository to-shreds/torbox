# APK Catcher

Share-only Android helper. There is no launcher screen, account, background service, analytics, or broad storage permission.

APK Catcher 1.2 adds a proper adaptive app icon so the share target is recognizable instead of appearing as a blank white system icon.

The GitHub Android app commonly shares a GitHub file-view URL instead of the APK bytes. APK Catcher accepts either form:

- an actual shared APK or ZIP containing exactly one APK; or
- a public GitHub HTTPS link shared by the GitHub app.

For a GitHub link, APK Catcher fetches the public file itself, ignores GitHub's MIME label, verifies that Android recognizes the result as an installable package, and opens Android's standard installer. Network access is used only for that user-initiated GitHub fetch. Cleartext HTTP and non-GitHub download hosts are rejected.

Private GitHub files are not supported because this helper deliberately has no GitHub login or token storage. Split or multiple-APK bundles are also rejected instead of guessing.
