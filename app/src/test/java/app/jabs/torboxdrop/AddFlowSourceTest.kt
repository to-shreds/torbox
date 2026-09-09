package app.jabs.torboxdrop

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import org.junit.Test

class AddFlowSourceTest {
    private val projectRoot: Path by lazy(::findProjectRoot)
    private val viewModelSource: String by lazy {
        projectRoot.resolve("app/src/main/java/app/jabs/torboxdrop/MainViewModel.kt").readText()
    }
    private val addScreenSource: String by lazy {
        projectRoot.resolve("app/src/main/java/app/jabs/torboxdrop/ui/screens/AddScreen.kt").readText()
    }
    private val settingsSource: String by lazy {
        projectRoot.resolve("app/src/main/java/app/jabs/torboxdrop/ui/screens/SettingsScreen.kt").readText()
    }

    @Test
    fun externalSharesAndTorrentUrisAlwaysStageForExplicitConfirmation() {
        assertThat(viewModelSource).contains("\"android-share\", \"deep-link\" -> false")
        assertThat(viewModelSource).contains("else -> false")
        assertThat(viewModelSource).contains("URI ingestion is staging only")
        assertThat(viewModelSource).doesNotContain("preferences.confirmBeforeSending")
        assertThat(settingsSource).doesNotContain("Instantly send shared links")
        assertThat(addScreenSource).contains("Text(\"Add to TorBox\")")
    }

    @Test
    fun successfulManualAddClearsFormReturnsToDownloadsAndReconcilesById() {
        assertThat(viewModelSource)
            .contains("destination = if (navigateAfterSuccess) AppDestination.DOWNLOADS")
        assertThat(viewModelSource).contains("addCandidate = \"\"")
        assertThat(viewModelSource).contains("reconcileRecentlyAddedDownloads(rawDownloads)")
        assertThat(addScreenSource).contains("Text(\"Add to TorBox\")")
    }

    @Test
    fun perTorrentDriveOverrideDoesNotRewriteGlobalDefault() {
        assertThat(viewModelSource)
            .contains("fun setAddOptions(options: AddOptions) = _uiState.update { it.copy(addOptions = options) }")
        assertThat(viewModelSource)
            .doesNotContain("options.sendToGoogleDrive?.let { preferences.googleDriveByDefault = it }")
    }

    private fun findProjectRoot(): Path {
        val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("app/src/main/AndroidManifest.xml")) }
            ?: error("Could not locate Android project root from $start")
    }
}
