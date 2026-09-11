package app.jabs.torboxdrop.drive

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

class ManualDriveUiSourceTest {
    private val root = generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("app/src/main/AndroidManifest.xml")) }
    private fun source(path: String) = root.resolve("app/src/main/java/app/jabs/torboxdrop/$path").toFile().readText()
    @Test fun everyReadyListLayoutHasDriveActionAndRowClickOnlyOpensPrompt() {
        val screen = source("ui/DownloadsScreen.kt")
        assertEquals(3, Regex("ManualDriveButton\\(item, onDrive\\)").findAll(screen).count())
        assertTrue(screen.contains("SmallAction(Icons.Outlined.AddToDrive, \"Drive\", onDrive)"))
        assertTrue(screen.contains("ManualDriveCoordinator.isEligible(item)"))
        assertTrue(screen.contains("Modifier.size(48.dp)"))
        assertTrue(source("MainActivity.kt").contains("onDrive = viewModel::requestManualDrive"))
        val vm = source("MainViewModel.kt").substringAfter("fun requestManualDrive").substringBefore("fun dismissManualDrive")
        assertTrue(vm.contains("pendingManualDrive = ManualDriveTarget"))
        assertFalse(vm.contains("enqueue(")); assertFalse(vm.contains("queueGoogleDrive")); assertFalse(vm.contains("createTorrent"))
    }
    @Test fun dialogHasExplicitFolderConfirmationAndDoesNotPersistBearerTokens() {
        val dialog = source("ui/screens/ManualDriveDialog.kt")
        assertTrue(dialog.contains("Destination folder name")); assertTrue(dialog.contains("Send to Drive"))
        assertTrue(dialog.contains("Cancel")); assertTrue(dialog.contains("originals stay in TorBox"))
        assertFalse(dialog.contains("mutableStateOf(token")); assertFalse(dialog.contains("mutableStateOf(auth.accessToken"))
        assertFalse(dialog.contains("LaunchedEffect"))
        val coordinator = source("drive/ManualDriveCoordinator.kt")
        assertFalse(coordinator.contains("updateGoogleDriveFolderId")); assertFalse(coordinator.contains(".createTorrent"))
        assertFalse(coordinator.contains(".delete(")); assertFalse(coordinator.contains("googleDriveByDefault ="))
    }
}
