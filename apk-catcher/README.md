# APK Catcher

Standalone, share-sheet-only Android helper. This project is isolated on the `build/apk-catcher-20260907` branch and does not change the TorBox app or its main branch.

Share one actual file to APK Catcher. A mislabeled APK or a ZIP containing exactly one APK is copied into private temporary storage, checked as an Android package, then handed to Android's standard installer. Android retains control of installation and the unknown-app-source permission. No launcher screen, background service, network access, broad storage permission, analytics, or account.

URL-only shares and split/multiple-APK bundles are not installed. A short message explains the problem. Temporary files are removed on a subsequent use after 24 hours; files already handed to Android are not removed prematurely.

Build source and tests are in this directory. The isolated GitHub workflow compiles the app with Android SDK tools, tests archive handling, and returns an aligned unsigned APK. The final APK is signed separately; no signing key is stored in this repository.
