from pathlib import Path
import subprocess, sys, xml.etree.ElementTree as ET

ROOT = Path('.')
def replace(path, old, new):
    p = ROOT / path
    s = p.read_text()
    assert s.count(old) == 1, f'Unexpected correction target in {path}'
    p.write_text(s.replace(old, new))
    subprocess.run(['git', 'add', str(p)], check=True)

if sys.argv[1] == 'prefs':
    p = 'app/src/main/java/app/jabs/torboxdrop/data/AppPreferences.kt'
    replace(p, 'preferences.getString("google_drive_owner_scope", null)', 'driveSetup.getString("google_drive_owner_scope", null)')
    replace(p, 'check(preferences.edit().putString("google_drive_owner_scope", accountScope)', 'check(driveSetup.edit().putString("google_drive_owner_scope", accountScope)')
    replace(p, 'check(preferences.edit().putString(KEY_GOOGLE_DRIVE_FOLDER_ID, id)', 'check(driveSetup.edit().putString(KEY_GOOGLE_DRIVE_FOLDER_ID, id)')
    replace(p, '''        check(preferences.edit().remove("google_drive_owner_scope")
            .remove(KEY_GOOGLE_DRIVE_FOLDER_ID).remove(KEY_GOOGLE_DRIVE_FOLDER_NAME)
            .putBoolean(KEY_GOOGLE_DRIVE_CONNECTED, false)
            .putBoolean(KEY_GOOGLE_DRIVE_DEFAULT, false).commit()) { "Could not clear Drive connection." }''', '''        check(driveSetup.edit().remove("google_drive_owner_scope")
            .remove(KEY_GOOGLE_DRIVE_FOLDER_ID).remove(KEY_GOOGLE_DRIVE_FOLDER_NAME)
            .putBoolean(KEY_GOOGLE_DRIVE_CONNECTED, false).commit()) { "Could not clear Drive connection." }
        check(preferences.edit().putBoolean(KEY_GOOGLE_DRIVE_DEFAULT, false).commit()) {
            "Could not clear the Drive default."
        }''')
elif sys.argv[1] == 'expect-failures':
    p = Path('app/build/test-results/testDebugUnitTest/TEST-app.jabs.torboxdrop.drive.DriveRecoveryEdgesTest.xml')
    root = ET.parse(p).getroot()
    assert (root.get('tests'), root.get('failures'), root.get('errors'), root.get('skipped')) == ('3','3','0','0'), root.attrib
    expected = {
        'preSubmissionSchedulerFailureLeavesNoRunnableAdmission': 'Pre-POST failure must disarm its admission',
        'missingSourceStopsUnsentFilesButKeepsAcceptedCloudJobMonitorable': 'Accepted cloud job must remain monitorable',
        'manualCheckReconcilesUncertainJobWhileOtherFilesNeedGoogleReconnect': 'Manual check must read late cloud job despite expired Google authorization',
    }
    assert {case.get('name') for case in root.findall('testcase')} == set(expected)
    for case in root.findall('testcase'):
        failure = case.find('failure')
        assert failure is not None and failure.get('type') == 'java.lang.AssertionError', case.attrib
        assert expected[case.get('name')] in failure.get('message', ''), failure.attrib
    print('Three neighboring recovery failures reproduced at the intended assertions.')
elif sys.argv[1] == 'edges':
    p = 'app/src/main/java/app/jabs/torboxdrop/data/TorBoxRepository.kt'
    replace(p, '''        onDriveWatchArmed()
        return admission''', '''        try {
            onDriveWatchArmed()
        } catch (error: Exception) {
            // Creation has not been attempted. This failed preflight must not leave a live intent.
            withContext(NonCancellable) {
                requireNotNull(driveStore).stopUnsubmitted(admission.scope, admission.queueId, queued = true)
            }
            throw error
        }
        return admission''')
    p = 'app/src/main/java/app/jabs/torboxdrop/drive/DriveStore.kt'
    replace(p, 'hasSubmittedFiles(it.watchKey) }', 'hasSubmittedFiles(it.watchKey, includeReview) }')
    replace(p, '''    private fun hasSubmittedFiles(key: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM drive_files WHERE watch_key = ? AND state IN (?, ?) LIMIT 1",
        arrayOf(key, DriveFileState.SUBMITTING.name, DriveFileState.SUBMITTED.name),
    ).use { it.moveToFirst() }''', '''    private fun hasSubmittedFiles(key: String, includeUncertain: Boolean): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM drive_files WHERE watch_key = ? AND state IN (?, ?, ?) LIMIT 1",
        arrayOf(key, DriveFileState.SUBMITTING.name, DriveFileState.SUBMITTED.name,
            if (includeUncertain) DriveFileState.UNCERTAIN.name else DriveFileState.SUBMITTED.name),
    ).use { it.moveToFirst() }''')
    p = 'app/src/main/java/app/jabs/torboxdrop/drive/DriveAutomationRunner.kt'
    replace(p, 'store.markWatchFailed(watch.watchKey, "Drive automation supports torrents only.")', 'stopNewSubmissions(watch, "Drive automation supports torrents only.")')
    replace(p, 'store.markWatchFailed(bound.watchKey, "The torrent identity changed. Nothing was sent to Drive.")', 'stopNewSubmissions(bound, "The torrent identity changed. No new files will be sent to Drive.")')
    replace(p, 'store.markWatchFailed(watch.watchKey, "TorBox no longer lists this torrent. No new files will be sent.")', 'stopNewSubmissions(watch, "TorBox no longer lists this torrent. No new files will be sent.")')
    replace(p, '    private suspend fun recordMissing(watch: DriveWatch) {', '''    private suspend fun stopNewSubmissions(watch: DriveWatch, message: String) {
        store.stopUnsubmitted(watch.accountScope, watch.queueId ?: watch.downloadId, queued = watch.queueId != null)
        store.finishWatchIfTerminal(watch.watchKey)
        // Source removal or an identity mismatch does not cancel a cloud job already accepted.
        store.note(watch.watchKey, message)
    }

    private suspend fun recordMissing(watch: DriveWatch) {''')
    subprocess.run(['git', 'add', 'app/src/test/java/app/jabs/torboxdrop/drive/DriveRecoveryEdgesTest.kt'], check=True)
else:
    raise SystemExit('Unknown correction mode')
subprocess.run(['git', 'diff', '--cached', '--check'], check=True)
