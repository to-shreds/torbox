package app.jabs.torboxdrop.security

import app.jabs.torboxdrop.data.QueueControlOperation
import app.jabs.torboxdrop.data.TorrentControlOperation
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadDensity
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.DownloadsUiState
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class ProjectSecurityInvariantsTest {
    private val projectRoot: Path by lazy(::findProjectRoot)
    private val mainRoot: Path get() = projectRoot.resolve("app/src/main")

    @Test
    fun productionHasNoUsenetUiApiOrEndpoint() {
        val productionText = textFilesUnder(mainRoot).joinToString("\n") { it.readText() }.lowercase()
        assertThat(productionText).doesNotContain("usenet")
        assertThat(productionText).doesNotContain("nzb")
        assertThat(productionText).doesNotContain("/usenet/")
    }

    @Test
    fun browserDoesNotInstallJavascriptBridge() {
        val productionText = productionKotlinFiles().joinToString("\n") { it.readText() }

        assertThat(productionText).doesNotContain("addJavascriptInterface")
        assertThat(productionText).doesNotContain("@JavascriptInterface")
    }

    @Test
    fun browserFailsClosedAndCannotReadLocalAppResources() {
        val browserSource = mainRoot.resolve(
            "java/app/jabs/torboxdrop/browser/SecureBrowserController.kt",
        ).readText()

        assertThat(browserSource).contains("allowFileAccess = false")
        assertThat(browserSource).contains("allowContentAccess = false")
        assertThat(browserSource).contains("allowFileAccessFromFileURLs = false")
        assertThat(browserSource).contains("allowUniversalAccessFromFileURLs = false")
        assertThat(browserSource).contains("WebSettings.MIXED_CONTENT_NEVER_ALLOW")
        assertThat(browserSource).contains("safeBrowsingEnabled = true")
        assertThat(browserSource).contains("handler.cancel()")
        assertThat(browserSource).doesNotContain("handler.proceed()")
        assertThat(browserSource).doesNotContain("allowFileAccess = true")
        assertThat(browserSource).doesNotContain("allowContentAccess = true")
        assertThat(browserSource).doesNotContain("allowUniversalAccessFromFileURLs = true")
        assertThat(browserSource).contains("request.hasGesture()")
        assertThat(browserSource).contains("handleNavigation(url, hasTrustedGesture = false)")
    }

    @Test
    fun apiTokenUsesKeystoreEncryptionAndProductionHasNoDirectConsoleLogging() {
        val tokenSource = mainRoot.resolve("java/app/jabs/torboxdrop/data/SecureTokenStore.kt").readText()
        val productionText = productionKotlinFiles().joinToString("\n") { it.readText() }

        assertThat(tokenSource).contains("AndroidKeyStore")
        assertThat(tokenSource).contains("AES/GCM/NoPadding")
        assertThat(tokenSource).contains("noBackupFilesDir")
        assertThat(productionText).doesNotContain("android.util.Log")
        assertThat(productionText).doesNotContain("Log.")
        assertThat(productionText).doesNotContain("println(")
        assertThat(productionText).doesNotContain("System.out")
        assertThat(productionText).doesNotContain("System.err")
        assertThat(productionText).doesNotContain("printStackTrace(")
    }

    @Test
    fun readinessRequiresBothAuthoritativeFields() {
        fun item(finished: Boolean, present: Boolean) = DownloadItem(
            id = "1",
            type = DownloadType.TORRENT,
            name = "test",
            downloadFinished = finished,
            downloadPresent = present,
        )

        assertThat(item(finished = false, present = false).isReady).isFalse()
        assertThat(item(finished = true, present = false).isReady).isFalse()
        assertThat(item(finished = false, present = true).isReady).isFalse()
        assertThat(item(finished = true, present = true).isReady).isTrue()

        val modelSource = mainRoot.resolve("java/app/jabs/torboxdrop/model/Models.kt").readText()
        assertThat(modelSource).contains("downloadFinished && downloadPresent")
    }

    @Test
    fun downloadProgressRemainsServerDerivedWithoutInterpolation() {
        val apiSource = mainRoot.resolve("java/app/jabs/torboxdrop/data/TorBoxApiClient.kt").readText()
        val repositorySource = mainRoot.resolve("java/app/jabs/torboxdrop/data/TorBoxRepository.kt").readText()
        val viewModelSource = mainRoot.resolve("java/app/jabs/torboxdrop/MainViewModel.kt").readText()
        val formatterSource = mainRoot.resolve("java/app/jabs/torboxdrop/ui/UiFormatters.kt").readText()

        assertThat(apiSource)
            .contains("progress = source.double(\"progress\")?.takeIf(Double::isFinite)")
        assertThat(repositorySource).doesNotContain("copy(progress =")
        assertThat(viewModelSource).doesNotContain("copy(progress =")
        assertThat(viewModelSource).doesNotContain("progress = item.progress +")
        assertThat(formatterSource).contains("progress / 100.0")
        assertThat(formatterSource).doesNotContain("System.currentTimeMillis")
        assertThat(formatterSource).doesNotContain("elapsedRealtime")
    }

    @Test
    fun requestDownloadLinkUsesNonRedirectingJsonFlow() {
        val apiSource = mainRoot.resolve("java/app/jabs/torboxdrop/data/TorBoxApiClient.kt").readText()

        assertThat(apiSource).contains("\"torrents/requestdl\"")
        assertThat(apiSource).contains("\"webdl/requestdl\"")
        assertThat(apiSource).contains(".addQueryParameter(\"redirect\", \"false\")")
        assertThat(apiSource).contains("validateTemporaryUrl(temporaryUrl, apiToken)")
    }

    @Test
    fun onlyDocumentedManagementOperationsAreRepresentable() {
        assertThat(TorrentControlOperation.entries.map { it.wireValue })
            .containsExactly("reannounce", "pause", "resume", "delete")
        assertThat(QueueControlOperation.entries.map { it.wireValue })
            .containsExactly("start", "delete")

        val repositorySource = mainRoot.resolve("java/app/jabs/torboxdrop/data/TorBoxRepository.kt").readText()
        assertThat(repositorySource).doesNotContain("pauseWeb")
    }

    @Test
    fun manifestDeclaresShareTorrentMagnetAndModernMonitoringBoundaries() {
        val manifest = parseManifest()
        val root = manifest.documentElement
        val permissions = manifest.elements("uses-permission")
            .map { it.androidAttribute("name") }
            .toSet()
        assertThat(permissions).containsAtLeast(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        )

        val application = manifest.elements("application").single()
        assertThat(application.androidAttribute("usesCleartextTraffic")).isEqualTo("false")
        assertThat(application.androidAttribute("allowBackup")).isEqualTo("false")

        val mainActivity = application.childElements("activity")
            .single { it.androidAttribute("name") == ".MainActivity" }
        assertThat(mainActivity.androidAttribute("exported")).isEqualTo("true")
        assertThat(mainActivity.hasIntent("android.intent.action.SEND", mimeType = "text/*")).isTrue()
        assertThat(
            mainActivity.hasIntent(
                "android.intent.action.SEND",
                mimeType = "application/x-bittorrent",
            ),
        ).isTrue()
        assertThat(mainActivity.hasIntent("android.intent.action.VIEW", scheme = "magnet")).isTrue()
        assertThat(
            mainActivity.hasIntent(
                "android.intent.action.VIEW",
                mimeType = "application/x-bittorrent",
                scheme = "content",
            ),
        ).isTrue()

        val service = application.childElements("service")
            .single { it.androidAttribute("name") == ".notifications.CompletionMonitorService" }
        assertThat(service.androidAttribute("exported")).isEqualTo("false")
        assertThat(service.androidAttribute("foregroundServiceType")).isEqualTo("dataSync")

        val receiver = application.childElements("receiver")
            .single { it.androidAttribute("name") == ".notifications.RestoreMonitoringReceiver" }
        assertThat(receiver.androidAttribute("exported")).isEqualTo("false")
        assertThat(receiver.hasIntent("android.intent.action.BOOT_COMPLETED")).isTrue()
    }

    @Test
    fun compactDensityIsTheNativeDefault() {
        assertThat(DownloadsUiState().density).isEqualTo(DownloadDensity.COMPACT)

        val preferencesSource = mainRoot.resolve("java/app/jabs/torboxdrop/data/AppPreferences.kt").readText()
        assertThat(preferencesSource)
            .contains("enumValue(KEY_DENSITY, DownloadDensity.COMPACT)")
    }

    private fun productionKotlinFiles(): List<Path> = Files.walk(mainRoot.resolve("java")).use { paths ->
        paths.filter { !it.isDirectory() && it.extension == "kt" }.toList()
    }

    private fun textFilesUnder(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter {
            !it.isDirectory() && it.extension.lowercase() in TEXT_PRODUCTION_EXTENSIONS
        }.toList()
    }

    private fun parseManifest(): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        return factory.newDocumentBuilder().parse(mainRoot.resolve("AndroidManifest.xml").toFile())
    }

    private fun Document.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.childElements(tagName: String): List<Element> = buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.tagName == tagName) add(element)
        }
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun Element.hasIntent(
        action: String,
        mimeType: String? = null,
        scheme: String? = null,
    ): Boolean = childElements("intent-filter").any { filter ->
        val hasAction = filter.childElements("action")
            .any { it.androidAttribute("name") == action }
        val data = filter.childElements("data")
        hasAction &&
            (mimeType == null || data.any { it.androidAttribute("mimeType") == mimeType }) &&
            (scheme == null || data.any { it.androidAttribute("scheme") == scheme })
    }

    private fun findProjectRoot(): Path {
        val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("app/src/main/AndroidManifest.xml")) }
            ?: error("Could not locate Android project root from $start")
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        val TEXT_PRODUCTION_EXTENSIONS = setOf("kt", "java", "xml", "json", "txt", "html", "js")
    }
}
