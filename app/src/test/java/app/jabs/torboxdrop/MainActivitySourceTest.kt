package app.jabs.torboxdrop

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import org.junit.Test

class MainActivitySourceTest {
    private val source: String by lazy {
        findProjectRoot()
            .resolve("app/src/main/java/app/jabs/torboxdrop/MainActivity.kt")
            .readText()
    }

    @Test
    fun bottomNavigationRetainsItsContentHeightOutsideSystemInsets() {
        val functionStart = source.indexOf("private fun CompactBottomNavigation")
        val functionEnd = source.indexOf("\n@Composable", startIndex = functionStart + 1)

        assertThat(functionStart).isAtLeast(0)
        assertThat(functionEnd).isGreaterThan(functionStart)

        val bottomNavigationSource = source.substring(functionStart, functionEnd)
        assertThat(bottomNavigationSource).contains("NavigationBar {")
        assertThat(bottomNavigationSource).doesNotContain(".height(")
    }

    @Test
    fun edgeToEdgeUsesDarkSystemBarStylesForTheDarkAppTheme() {
        assertThat(source).contains("statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)")
        assertThat(source).contains("navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)")
    }

    private fun findProjectRoot(): Path {
        val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("app/src/main/AndroidManifest.xml")) }
            ?: error("Could not locate Android project root from $start")
    }
}
