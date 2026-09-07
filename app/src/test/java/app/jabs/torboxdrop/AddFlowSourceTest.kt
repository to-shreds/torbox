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

    @Test
    fun addDestinationNeverAutoSubmitsClipboardOrChosenTorrent() {
        assertThat(viewModelSource).contains("_uiState.value.destination == AppDestination.ADD")
        assertThat(viewModelSource).contains("!explicitlyChosenHere &&")
        assertThat(viewModelSource).doesNotContain("explicitlyChosenHere || !preferences.confirmBeforeSending")
    }

    @Test
    fun successfulManualAddClearsFormReturnsToDownloadsAndReconcilesById() {
        assertThat(viewModelSource)
            .contains("destination = if (navigateAfterSuccess) AppDestination.DOWNLOADS")
        assertThat(viewModelSource).contains("addCandidate = \"\"")
        assertThat(viewModelSource).contains("reconcileRecentlyAddedDownloads(rawDownloads)")
        assertThat(addScreenSource).contains("Text(\"Add to TorBox\")")
    }

    private fun findProjectRoot(): Path {
        val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("app/src/main/AndroidManifest.xml")) }
            ?: error("Could not locate Android project root from $start")
    }
}
