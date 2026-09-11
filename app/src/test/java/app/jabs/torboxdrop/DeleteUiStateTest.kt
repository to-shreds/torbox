package app.jabs.torboxdrop

import app.jabs.torboxdrop.model.*
import org.junit.Assert.*
import org.junit.Test

class DeleteUiStateTest {
    private val item = DownloadItem("17", DownloadType.TORRENT, "Example")
    @Test fun confirmedRemovalIsImmediateWithoutWaitingForRefresh() {
        val other = item.copy(id = "18")
        val state = MainUiState(selectedDownload = item, fileSheet = FileSheetState(item),
            downloads = DownloadsUiState(downloads = listOf(item, other)),
            watchedKeys = setOf("TORRENT:17", "TORRENT:18"),
            loadedFiles = mapOf("TORRENT:17" to emptyList(), "TORRENT:18" to emptyList()))
        val next = state.withDeletedDownload(item)
        assertEquals(listOf(other), next.downloads.downloads); assertNull(next.selectedDownload)
        assertNull(next.fileSheet); assertEquals(setOf("TORRENT:18"), next.watchedKeys)
        assertFalse(next.loadedFiles.containsKey("TORRENT:17"))
    }
    @Test fun removingTorrentDoesNotRemoveWebWithSameIdOrCloseAnotherDetail() {
        val other = item.copy(type = DownloadType.WEB)
        val state = MainUiState(selectedDownload = other, fileSheet = FileSheetState(other),
            downloads = DownloadsUiState(downloads = listOf(item, other)), watchedKeys = setOf("WEB:17"))
        val next = state.withDeletedDownload(item)
        assertEquals(other, next.selectedDownload); assertEquals(other, next.fileSheet!!.download)
        assertEquals(listOf(other), next.downloads.downloads); assertEquals(setOf("WEB:17"), next.watchedKeys)
    }
    @Test fun deleteDialogKeepsVisibleProgressAndSanitizedErrorsInsteadOfClosingOnConfirm() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "app/src/main").exists() }
        val ui = java.io.File(root, "app/src/main/java/app/jabs/torboxdrop/ui/screens/DownloadDetailScreen.kt").readText()
        val branch = ui.substringAfter("DetailDialog.Delete -> ConfirmDeleteDialog(").substringBefore("null -> Unit")
        assertTrue(branch.contains("busy = actionInProgress")); assertTrue(branch.contains("error = deleteError"))
        assertFalse(branch.substringAfter("onConfirm =").contains("dialog = null"))
        val activity = java.io.File(root, "app/src/main/java/app/jabs/torboxdrop/MainActivity.kt").readText()
        assertTrue(activity.contains("deleteError = state.deleteError"))
    }
}
