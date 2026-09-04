package app.jabs.torboxdrop.ui

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import org.junit.Test

class DownloadsScreenSourceTest {
    private val source: String by lazy {
        findProjectRoot()
            .resolve("app/src/main/java/app/jabs/torboxdrop/ui/DownloadsScreen.kt")
            .readText()
    }

    @Test
    fun eachDownloadTabOwnsASaveableLazyListState() {
        assertThat(Regex("rememberLazyListState\\(\\)").findAll(source).count()).isAtLeast(4)
        assertThat(source).contains("DownloadTab.ACTIVE -> activeListState")
        assertThat(source).contains("DownloadTab.FINISHED -> finishedListState")
        assertThat(source).contains("DownloadTab.QUEUE -> queueListState")
        assertThat(source).contains("DownloadTab.AIRLOCK -> airLockListState")
    }

    @Test
    fun hidingSearchClearsItsQueryAndQueueRowsExposeFullDetails() {
        assertThat(source).contains("if (searchVisible) closeSearch()")
        assertThat(source).contains("onSearchChanged(\"\")")
        assertThat(source).contains("Queued download details and actions")
        assertThat(source).contains("QueueDetail(\"Source\"")
        assertThat(source).doesNotContain("item.seeds ?: 0")
        assertThat(source).doesNotContain("item.peers ?: 0")
    }

    private fun findProjectRoot(): Path {
        val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("app/src/main/AndroidManifest.xml")) }
            ?: error("Could not locate Android project root from $start")
    }
}
