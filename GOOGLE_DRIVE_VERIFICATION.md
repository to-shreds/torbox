# Google Drive verification report

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
