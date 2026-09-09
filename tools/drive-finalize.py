#!/usr/bin/env python3
from pathlib import Path
import os
import subprocess

SOURCE_BASE = os.environ.get("SOURCE_BASE", "bb3680e2c205b5beb10cb63dd55f746db33167c8")
expected = {
    ".github/workflows/drive-adversarial-security.yml",
    ".github/workflows/drive-adversarial-state.yml",
    ".github/workflows/drive-finalize.yml",
    "tools/drive-finalize.py",
}
changed = set(subprocess.check_output(["git", "diff", "--name-only", SOURCE_BASE, "HEAD"]).decode().splitlines())
assert changed == expected, f"Implementation changed after adversarial source freeze: {sorted(changed)}"

subprocess.run([
    "git", "rm", "-r", "--ignore-unmatch",
    ".github/workflows/drive-recovery-verify.yml",
    ".github/workflows/drive-pre-adversarial-repair.yml",
    ".github/workflows/drive-adversarial-security.yml",
    ".github/workflows/drive-adversarial-state.yml",
    ".github/workflows/drive-finalize.yml",
    "tools/drive-recovery",
    "tools/drive-reproduction",
    "tools/drive-finalize.py",
], check=True)

gradle = Path("app/build.gradle.kts")
text = gradle.read_text()
assert text.count("versionCode = 20003") == 1
assert text.count('versionName = "2.0.3"') == 1
text = text.replace("versionCode = 20003", "versionCode = 20004")
text = text.replace('versionName = "2.0.3"', 'versionName = "2.0.4"')
gradle.write_text(text)

Path("GOOGLE_DRIVE_SETUP.md").write_text("""# Google Drive setup for TorBox Drop 2.0.4

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
""")

Path("GOOGLE_DRIVE_VERIFICATION.md").write_text("""# Google Drive verification report

Date: 2026-09-08

## Scope

This report covers the Google Drive automation source used for the TorBox Drop 2.0.4 release candidate. The implementation adds a single global Drive destination, per-torrent opt-in or opt-out, cached-torrent handling, completion-triggered upload, queue-to-active recovery, individual-file TorBox cloud transfers and durable duplicate prevention.

## Primary verification

The recovered implementation passed the complete JVM regression suite, Android lint, debug assembly and minified release assembly. A subsequent pre-adversarial review found and repaired three additional issues: a per-torrent Drive choice could overwrite the remembered global default, raw magnet text could be retained in the Drive journal, and a local scheduler failure could interrupt an Add after the Drive intent had already been durably saved. The full suite, lint and both APK builds passed again after those repairs.

The frozen implementation reviewed by both adversarial gates is commit `bb3680e2c205b5beb10cb63dd55f746db33167c8`.

## Gate 7: security, OAuth and credential attack

GitHub Actions run `34304470086` completed successfully. The review attacked OAuth scope breadth, bearer-token persistence and logging, redirect-based credential leakage, accidental request replay, server-controlled error text, account replacement, raw magnet retention and Android backup exposure.

Static checks and executable boundary tests passed. Google access is limited to `drive.file`; credential-bearing clients do not follow redirects or automatically retry; Google access tokens are not written to ordinary preferences or SQLite; and the TorBox API token remains in AndroidKeyStore-backed no-backup storage.

## Gate 8: state machine and recovery attack

GitHub Actions run `34304701984` completed successfully. The review attacked duplicate uploads, ambiguous POST responses, late TorBox job appearance, process restart, incorrect queue-to-active matching, reused torrent IDs, deletion during submitted work, account replacement, Drive-only monitoring, boot restoration and non-authoritative completion labels.

The targeted attack suite passed and the entire JVM suite was replayed successfully afterward. The durable journal does not automatically turn an uncertain remote POST back into a new submission, and completion still requires TorBox's authoritative `download_finished && download_present` state.

## Not represented as verified

CI does not have the production Google OAuth registration, a live TorBox account, a physical Android device or the private TorBox Drop v2 signing key. This report therefore does not claim that the production consent screen, a live TorBox-to-Google transfer, vendor-specific background scheduling or an update-compatible signed 2.0.4 APK has been exercised. Those are deployment checks, not hidden passing tests.
""")

readme = Path("README.md")
text = readme.read_text()
assert "## Google Drive automation" not in text
needle = "- Completion watches are durable and use a user-started visible foreground service with a slower WorkManager fallback.\n"
assert text.count(needle) == 1
text = text.replace(needle, needle + "- Google Drive automation can send a torrent's individual files through TorBox directly to one configured Drive folder as soon as TorBox says the torrent is truly ready. The Drive action is durable and independent of the completion-notification toggle.\n")
marker = "## Architecture\n"
assert text.count(marker) == 1
text = text.replace(marker, """## Google Drive automation

Settings can connect one Google Drive account and one global destination folder. Add then exposes **Send to Google Drive when ready** for each torrent, with a separate remembered default. Cached torrents can proceed promptly; ordinary and queued torrents wait for the same authoritative readiness rule used elsewhere in the app. TorBox performs the file transfer server-side.

Production Google OAuth configuration is required before the signed app can authorize Drive. See [GOOGLE_DRIVE_SETUP.md](GOOGLE_DRIVE_SETUP.md) for the exact package and certificate values, and [GOOGLE_DRIVE_VERIFICATION.md](GOOGLE_DRIVE_VERIFICATION.md) for the completed automated and adversarial checks.

## Architecture
""")
text = text.replace("| Local data | SQLite for last-known records, files, bookmarks, recent sends, and notification subscriptions |", "| Local data | SQLite for last-known records, files, bookmarks, recent sends, notification subscriptions, and durable Drive automation state |")
text = text.replace("| Background work | User-started `dataSync` foreground service plus constrained periodic WorkManager fallback |", "| Background work | Shared user-started `dataSync` live monitor plus constrained WorkManager fallback for completion and Drive automation |")
text = text.replace("release/TorBox-Drop-v2.0.2.apk", "release/TorBox-Drop-v2.0.3.apk")
text = text.replace("v2.0.2 uses the same package and signing certificate", "v2.0.3 uses the same package and signing certificate")
text = text.replace("If TorBox Drop v2.0.0 or v2.0.1 is already installed, v2.0.3", "If TorBox Drop v2.0.0, v2.0.1, or v2.0.2 is already installed, v2.0.3")
build_sentence = "The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The verified distribution artifact for this release is `release/TorBox-Drop-v2.0.3.apk`; it is minified, resource-shrunk, zip-aligned, and signed with the TorBox Drop v2 release key. The private key is deliberately not committed."
assert text.count(build_sentence) == 1
text = text.replace(build_sentence, build_sentence + "\n\nThe Google Drive source is versioned as 2.0.4. CI produces a debug APK and an unsigned minified 2.0.4 release candidate, but neither is represented as the production update. The final distribution APK must be signed with the existing TorBox Drop v2 private key and exercised with the production Google OAuth client before publication.")
readme.write_text(text)

notes = Path("BUILD_NOTES.md")
text = notes.read_text()
assert text.startswith("# TorBox Drop 2.0.3 build notes")
text = text.replace("# TorBox Drop 2.0.3 build notes", "# TorBox Drop 2.0.4 release-candidate build notes", 1)
text = text.replace("| Version | `2.0.3` (`versionCode 20003`) |", "| Version | `2.0.4` (`versionCode 20004`) |", 1)
marker = "## v2.0.3 targeted corrective release\n"
assert text.count(marker) == 1
section = """## v2.0.4 Google Drive automation release candidate

- Settings connects Google Drive using Google Play services authorization with only the `drive.file` scope and configures one global app-managed destination folder.
- Add exposes a per-torrent **Send to Google Drive when ready** choice without rewriting the remembered default.
- Cached torrents can be acted on promptly; unfinished and queued torrents remain durable until TorBox reports both `download_finished` and `download_present`.
- Multi-file torrents are submitted as individual files to TorBox's Google Drive integration. Media bytes do not pass through Android for this automation.
- Drive state is account-scoped and durable across process restart. Ambiguous remote POST results are quarantined and reconciled instead of blindly replayed.
- Raw magnet URLs are not retained in the Drive automation journal. Credential-bearing Google and TorBox Drive clients reject redirects and automatic request replay.
- Drive-only monitoring works without arming a completion notification. WorkManager remains the durable fallback; the faster foreground loop is available only when Android permits its visible monitoring notification.

Automated verification passed the complete JVM suite, Android lint, debug assembly and minified release assembly after the final pre-adversarial repairs. Separate security/OAuth and state-machine/recovery adversarial gates then passed. See [GOOGLE_DRIVE_VERIFICATION.md](GOOGLE_DRIVE_VERIFICATION.md).

The automated source is ready for deployment validation, but a production distribution is not claimed yet. The Google Cloud Android OAuth client must be registered for `app.jabs.torboxdrop` and the existing v2 release certificate SHA-1, and the APK must be signed with the existing v2 private key. CI does not possess that private key. A live consent flow, live TorBox-to-Drive transfer and physical-device background check therefore remain deployment tests.

"""
text = text.replace(marker, section + marker)
text = text.replace("## Verification scope\n", "## Baseline v2 verification scope\n", 1)
notes.write_text(text)

acceptance = Path("ACCEPTANCE_TESTS.md")
text = acceptance.read_text()
text = text.replace("Date: 2026-09-04", "Date: 2026-09-08", 1)
old = "The final JVM suite contains 142 tests with 0 failures, 0 errors, and 0 skipped tests. A clean GitHub Actions build produced both debug and minified release APKs."
new = "The v2.0.4 Google Drive release-candidate source passes the complete JVM regression suite, and a clean GitHub Actions build produces both debug and minified unsigned release APKs. The earlier baseline suite counts below remain historical inventory rather than a current total."
assert text.count(old) == 1
text = text.replace(old, new)
marker = "## Criteria 1 through 50\n"
assert text.count(marker) == 1
section = """## v2.0.4 Google Drive automation

| Acceptance criterion | Status | Evidence or remaining check |
| --- | --- | --- |
| Connect one Google Drive account with `drive.file` scope | Automated boundary pass; live setup required | Authorization code requests and verifies only `drive.file`; production Google OAuth registration and consent remain external. |
| Use one global Drive destination folder | Automated / pass | Folder reservation, creation, conflict recovery, writable-folder validation, persistence and reconnect tests pass. |
| Per-torrent Send to Google Drive choice | Automated / pass | Add-flow source guard confirms the per-torrent override does not rewrite the remembered global default. |
| Cached/ready torrent can trigger promptly | Automated / pass | New Drive work schedules an immediate constrained worker pass and ready-state tests submit without waiting for a download transition. |
| Ordinary torrent waits for true readiness | Automated / pass | Tests require both `download_finished` and `download_present`; progress 100% or a `completed` label is insufficient. |
| Queued torrent keeps Drive intent until activation | Automated / pass | Exact torrent hash correlation is required; filename-only and ambiguous matches are rejected. |
| Multi-file torrent submits individual files | Automated / pass | Runner tests submit one TorBox Google Drive job per eligible file and block incomplete file lists. |
| No Android media re-download for automation | Automated source/boundary pass | Drive runner calls TorBox integration jobs directly; no temporary download URL is used by the Drive path. |
| Duplicate prevention and crash recovery | Adversarial / pass | Gate 8 run `34304701984` passed targeted duplicate, ambiguity, restart, account and recovery attacks plus full-suite replay. |
| Google/TorBox credential boundary | Adversarial / pass | Gate 7 run `34304470086` passed redirect, replay, token sanitization, persistence, OAuth-scope and account-isolation attacks. |
| Drive automation works without Notify when complete | Automated / pass with Android timing limit | Worker and service run Drive work independently of notification subscriptions. If Android notification capability prevents the live foreground service, WorkManager still owns durable delivery but may be slower. |
| Live Google consent and TorBox-to-Drive transfer | Requires configured production OAuth client, TorBox account and device | Not claimed by CI. |
| Update-compatible signed 2.0.4 APK | Requires existing v2 private signing key | Public certificate is known; the private key is deliberately absent from the repository and CI workspace. |

"""
text = text.replace(marker, section + marker)
acceptance.write_text(text)

subprocess.run([
    "git", "add", "app/build.gradle.kts", "GOOGLE_DRIVE_SETUP.md",
    "GOOGLE_DRIVE_VERIFICATION.md", "README.md", "BUILD_NOTES.md",
    "ACCEPTANCE_TESTS.md"
], check=True)
subprocess.run(["git", "diff", "--cached", "--check"], check=True)
