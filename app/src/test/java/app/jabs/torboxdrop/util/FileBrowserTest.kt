package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FileBrowserTest {
    @Test
    fun rootView_hidesTorBoxAbsoluteRootAndKeepsRealFolders() {
        val files = listOf(
            file(1, "Episode 1.mkv", "/completed/Bundle/Season 1/Episode 1.mkv", 100),
            file(2, "Episode 2.mkv", "/completed/Bundle/Season 2/Episode 2.mkv", 200),
            file(3, "readme.txt", "/completed/Bundle/readme.txt", 10),
        )

        val result = browseDownloadFiles(files, downloadName = "Bundle")

        assertThat(result.currentFolder).isEmpty()
        assertThat(result.entries.map { it.stableKey }).containsExactly(
            "folder:Season 1",
            "folder:Season 2",
            "file:TORRENT:download:3:readme.txt",
        ).inOrder()
        val firstFolder = result.entries.first() as FileBrowserEntry.Folder
        assertThat(firstFolder.fileCount).isEqualTo(1)
        assertThat(firstFolder.totalSize).isEqualTo(100L)
    }

    @Test
    fun absolutePaths_stripOnlyRecognizedTorBoxServerAndBundleRoots() {
        val files = listOf(
            file(1, "Episode 1.mkv", "/srv/completed/Bundle/Season 1/Episode 1.mkv"),
            file(2, "Episode 2.mkv", "/srv/completed/Bundle/Season 2/Episode 2.mkv"),
        )

        val result = browseDownloadFiles(files)

        assertThat(result.entries.filterIsInstance<FileBrowserEntry.Folder>().map { it.name })
            .containsExactly("Season 1", "Season 2")
    }

    @Test
    fun customDownloadTitle_preservesSharedMeaningfulSeasonFolder() {
        val files = listOf(
            file(1, "Episode 1.mkv", "/completed/Bundle/Season 1/Episode 1.mkv"),
            file(2, "Episode 2.mkv", "/completed/Bundle/Season 1/Episode 2.mkv"),
        )

        val result = browseDownloadFiles(files, downloadName = "My custom title")

        val folder = result.entries.single() as FileBrowserEntry.Folder
        assertThat(folder.path).isEqualTo("Season 1")
        assertThat(folder.fileCount).isEqualTo(2)
    }

    @Test
    fun singleAbsoluteTorBoxFile_preservesItsMeaningfulFolder() {
        val result = browseDownloadFiles(
            files = listOf(
                file(1, "Episode.mkv", "/completed/Bundle/Season 1/Episode.mkv"),
            ),
            downloadName = "A title that does not match Bundle",
        )

        val folder = result.entries.single() as FileBrowserEntry.Folder
        assertThat(folder.path).isEqualTo("Season 1")
        val nested = browseDownloadFiles(
            files = listOf(
                file(1, "Episode.mkv", "/completed/Bundle/Season 1/Episode.mkv"),
            ),
            currentFolder = folder.path,
            downloadName = "A title that does not match Bundle",
        )
        assertThat((nested.entries.single() as FileBrowserEntry.File).relativePath)
            .isEqualTo("Season 1/Episode.mkv")
    }

    @Test
    fun arbitraryAbsoluteCommonPath_isNotDiscarded() {
        val files = listOf(
            file(1, "Episode 1.mkv", "/media/Library/Season 1/Episode 1.mkv"),
            file(2, "Episode 2.mkv", "/media/Library/Season 1/Episode 2.mkv"),
        )

        val result = browseDownloadFiles(files, downloadName = "Unrelated title")

        assertThat((result.entries.single() as FileBrowserEntry.Folder).path).isEqualTo("media")
    }

    @Test
    fun pathsMayBeFullFilePathsOrDirectoryOnlyPaths() {
        val files = listOf(
            file(
                id = 1,
                name = "Full path.mkv",
                path = "/completed/Bundle/Season 1/Full path.mkv",
            ),
            file(
                id = 2,
                name = "Directory path.mkv",
                path = "/completed/Bundle/Season 1",
            ),
        )

        val result = browseDownloadFiles(
            files = files,
            currentFolder = "Season 1",
            downloadName = "Bundle",
        )

        assertThat(result.entries.filterIsInstance<FileBrowserEntry.File>().map { it.relativePath })
            .containsExactly(
                "Season 1/Directory path.mkv",
                "Season 1/Full path.mkv",
            ).inOrder()
    }

    @Test
    fun directoryOnlyRelativePath_remainsAVisibleFolder() {
        val result = browseDownloadFiles(
            files = listOf(file(1, "Episode.mkv", "Season 1")),
        )

        val folder = result.entries.single() as FileBrowserEntry.Folder
        assertThat(folder.path).isEqualTo("Season 1")
        val nested = browseDownloadFiles(
            files = listOf(file(1, "Episode.mkv", "Season 1")),
            currentFolder = folder.path,
        )
        assertThat((nested.entries.single() as FileBrowserEntry.File).relativePath)
            .isEqualTo("Season 1/Episode.mkv")
    }

    @Test
    fun relativePaths_preserveACommonMeaningfulFolder() {
        val files = listOf(
            file(1, "Episode 1.mkv", "Season 1/Episode 1.mkv"),
            file(2, "Episode 2.mkv", "Season 1/Episode 2.mkv"),
        )

        val result = browseDownloadFiles(files, downloadName = "Different bundle title")

        assertThat(result.entries.filterIsInstance<FileBrowserEntry.Folder>().single().name)
            .isEqualTo("Season 1")
    }

    @Test
    fun folderNavigation_returnsDirectChildrenAndBreadcrumbs() {
        val files = listOf(
            file(1, "Episode.mkv", "/completed/Bundle/Season 1/Episode.mkv"),
            file(2, "Trailer.mkv", "/completed/Bundle/Season 1/Extras/Trailer.mkv"),
            file(3, "Poster.jpg", "/completed/Bundle/Season 2/Poster.jpg"),
        )

        val result = browseDownloadFiles(
            files = files,
            currentFolder = "Season 1",
            downloadName = "Bundle",
        )

        assertThat(result.entries.map { it.stableKey }).containsExactly(
            "folder:Season 1/Extras",
            "file:TORRENT:download:1:Season 1/Episode.mkv",
        ).inOrder()
        assertThat(result.breadcrumbs).containsExactly(
            FileBreadcrumb("Files", ""),
            FileBreadcrumb("Season 1", "Season 1"),
        ).inOrder()
        assertThat(parentFolder("Season 1/Extras")).isEqualTo("Season 1")
    }

    @Test
    fun missingFolder_clampsToNearestExistingAncestor() {
        val result = browseDownloadFiles(
            files = listOf(file(1, "Episode.mkv", "Season 1/Episode.mkv")),
            currentFolder = "Season 1/Removed/Subfolder",
        )

        assertThat(result.currentFolder).isEqualTo("Season 1")
    }

    @Test
    fun extensionFilter_isCaseInsensitiveAndRecursesThroughFolders() {
        val files = listOf(
            file(1, "Episode.MKV", "Season 1/Episode.MKV"),
            file(2, "Movie.mkv", "Season 2/Movie.mkv"),
            file(3, "Captions.srt", "Season 1/Captions.srt"),
        )

        val result = browseDownloadFiles(files, extensionFilter = ".MkV")

        assertThat(result.recursiveResults).isTrue()
        assertThat(result.entries.filterIsInstance<FileBrowserEntry.File>().map { it.file.id })
            .containsExactly(1L, 2L)
        assertThat(result.entries.filterIsInstance<FileBrowserEntry.File>().map { it.parentPath })
            .containsExactly("Season 1", "Season 2")
        assertThat(result.extensionCounts).containsExactly("mkv", 2, "srt", 1)
        assertThat(result.matchingFileCount).isEqualTo(2)
    }

    @Test
    fun search_matchesAllTermsAcrossNamePathAndMimeRecursively() {
        val files = listOf(
            file(1, "Episode One.bin", "Season 1/Episode One.bin", mime = "video/x-matroska"),
            file(2, "Episode Two.mkv", "Specials/Episode Two.mkv", mime = "video/x-matroska"),
            file(3, "Notes.txt", "Season 1/Notes.txt", mime = "text/plain"),
        )

        val result = browseDownloadFiles(files, query = "season video")

        assertThat(result.entries.filterIsInstance<FileBrowserEntry.File>().map { it.file.id })
            .containsExactly(1L)
        assertThat(result.recursiveResults).isTrue()
    }

    @Test
    fun sortsFilesByNameTypeAndSizeWithUnknownSizesLast() {
        val files = listOf(
            file(1, "zeta.txt", "zeta.txt", 50),
            file(2, "Alpha.mkv", "Alpha.mkv", 200),
            file(3, "beta.mkv", "beta.mkv", null),
        )

        fun ids(sort: FileSort) = browseDownloadFiles(files, sort = sort).entries
            .filterIsInstance<FileBrowserEntry.File>()
            .map { it.file.id }

        assertThat(ids(FileSort.NAME_ASC)).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(ids(FileSort.NAME_DESC)).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(ids(FileSort.SIZE_DESC)).containsExactly(2L, 1L, 3L).inOrder()
        assertThat(ids(FileSort.SIZE_ASC)).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(ids(FileSort.TYPE)).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun folderAggregates_includeAllDescendantsAndStayUnknownWhenAnySizeIsUnknown() {
        val files = listOf(
            file(1, "one.mkv", "Season/one.mkv", Long.MAX_VALUE),
            file(2, "two.mkv", "Season/Extras/two.mkv", 10),
            file(3, "unknown.mkv", "Season/unknown.mkv", null),
        )

        val folder = browseDownloadFiles(files).entries.single() as FileBrowserEntry.Folder

        assertThat(folder.fileCount).isEqualTo(3)
        assertThat(folder.totalSize).isNull()
    }

    @Test
    fun folderAggregates_saturateKnownSizeOverflow() {
        val folder = browseDownloadFiles(
            listOf(
                file(1, "one.mkv", "Season/one.mkv", Long.MAX_VALUE),
                file(2, "two.mkv", "Season/two.mkv", 10),
            ),
        ).entries.single() as FileBrowserEntry.Folder

        assertThat(folder.totalSize).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun pathNormalization_handlesBackslashesDotSegmentsAndNoPath() {
        assertThat(normalizeFolderPath("/Season\\Extras/./Temp/../Clips/"))
            .isEqualTo("Season/Extras/Clips")
        assertThat(file(1, "MOVIE.MKV", null).fileExtension()).isEqualTo("mkv")
        assertThat(file(2, ".nomedia", null).fileExtension()).isNull()

        val result = browseDownloadFiles(listOf(file(1, "loose.mkv", null)))
        val entry = result.entries.single() as FileBrowserEntry.File
        assertThat(entry.relativePath).isEqualTo("loose.mkv")
        assertThat(entry.parentPath).isEmpty()
    }

    @Test
    fun duplicateNamesInDifferentFolders_keepDistinctStableKeys() {
        val files = listOf(
            file(1, "Episode.mkv", "Season 1/Episode.mkv"),
            file(2, "Episode.mkv", "Season 2/Episode.mkv"),
        )

        val entries = browseDownloadFiles(files, extensionFilter = "mkv").entries
            .filterIsInstance<FileBrowserEntry.File>()

        assertThat(entries.map { it.stableKey }).containsExactly(
            "file:TORRENT:download:1:Season 1/Episode.mkv",
            "file:TORRENT:download:2:Season 2/Episode.mkv",
        )
    }

    private fun file(
        id: Long,
        name: String,
        path: String?,
        size: Long? = null,
        mime: String? = null,
    ) = DownloadFile(
        id = id,
        downloadId = "download",
        downloadType = DownloadType.TORRENT,
        name = name,
        path = path,
        size = size,
        mimeType = mime,
    )
}
