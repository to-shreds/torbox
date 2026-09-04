package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import java.util.Locale

enum class FileSort {
    NAME_ASC,
    NAME_DESC,
    SIZE_DESC,
    SIZE_ASC,
    TYPE,
}

data class FileBreadcrumb(
    val label: String,
    val path: String,
)

sealed interface FileBrowserEntry {
    val stableKey: String

    data class Folder(
        val name: String,
        val path: String,
        val fileCount: Int,
        val totalSize: Long?,
    ) : FileBrowserEntry {
        override val stableKey: String = "folder:$path"
    }

    data class File(
        val file: DownloadFile,
        /** Logical path relative to the download root, including the filename. */
        val relativePath: String,
        /** Logical parent path relative to the download root. */
        val parentPath: String,
    ) : FileBrowserEntry {
        override val stableKey: String = buildString {
            append("file:")
            append(file.downloadType.name)
            append(':')
            append(file.downloadId)
            append(':')
            append(file.id)
            append(':')
            append(relativePath)
        }
    }
}

data class FileBrowserResult(
    val currentFolder: String,
    val breadcrumbs: List<FileBreadcrumb>,
    val entries: List<FileBrowserEntry>,
    /** Case-normalized extension counts for the current folder and all of its descendants. */
    val extensionCounts: Map<String, Int>,
    val scopeFileCount: Int,
    val matchingFileCount: Int,
    /** True when entries are recursive file results instead of direct folder contents. */
    val recursiveResults: Boolean,
)

/**
 * Builds an in-memory file-manager view from TorBox's flat file records.
 *
 * With no search or extension filter, [FileBrowserResult.entries] contains only the direct child
 * folders and files of [currentFolder]. When either filter is active, matching files are returned
 * recursively beneath the current folder, with [FileBrowserEntry.File.parentPath] retaining their
 * location. No network access or mutation of the source records occurs.
 *
 * [downloadName] lets the browser hide an absolute TorBox storage root while retaining meaningful
 * child folders. Paths under `/completed/<download name>/...` are therefore presented from the
 * logical download root rather than exposing TorBox's server-side storage path.
 */
fun browseDownloadFiles(
    files: List<DownloadFile>,
    currentFolder: String = "",
    query: String = "",
    extensionFilter: String? = null,
    sort: FileSort = FileSort.NAME_ASC,
    downloadName: String? = null,
    rootLabel: String = "Files",
): FileBrowserResult {
    val locations = logicalLocations(files, downloadName)
    val knownFolders = buildSet {
        add("")
        locations.forEach { location ->
            location.parentSegments.indices.forEach { index ->
                add(location.parentSegments.take(index + 1).toPath())
            }
        }
    }
    val effectiveFolder = clampFolder(currentFolder, knownFolders)
    val folderSegments = splitNormalizedPath(effectiveFolder)
    val scoped = locations.filter { it.parentSegments.startsWith(folderSegments) }
    val normalizedExtension = normalizeExtension(extensionFilter)
    val queryTerms = query.trim()
        .lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
    val recursiveResults = queryTerms.isNotEmpty() || normalizedExtension != null

    val extensionCounts = scoped.asSequence()
        .mapNotNull { it.extension }
        .groupingBy { it }
        .eachCount()
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)

    val matching = scoped.filter { location ->
        (normalizedExtension == null || location.extension == normalizedExtension) &&
            queryTerms.all(location.searchText::contains)
    }

    val entries = if (recursiveResults) {
        matching.sortedWith(fileComparator(sort)).map(LogicalFile::toEntry)
    } else {
        val childFolders = scoped.asSequence()
            .filter { it.parentSegments.size > folderSegments.size }
            .groupBy { it.parentSegments[folderSegments.size] }
            .map { (name, descendants) ->
                val path = (folderSegments + name).toPath()
                FileBrowserEntry.Folder(
                    name = name,
                    path = path,
                    fileCount = descendants.size,
                    totalSize = descendants.knownSizeSum(),
                )
            }
            .sortedWith(folderComparator(sort))
        val directFiles = scoped.asSequence()
            .filter { it.parentSegments.size == folderSegments.size }
            .sortedWith(fileComparator(sort))
            .map(LogicalFile::toEntry)
            .toList()
        childFolders + directFiles
    }

    return FileBrowserResult(
        currentFolder = effectiveFolder,
        breadcrumbs = fileBreadcrumbs(effectiveFolder, rootLabel),
        entries = entries,
        extensionCounts = extensionCounts,
        scopeFileCount = scoped.size,
        matchingFileCount = matching.size,
        recursiveResults = recursiveResults,
    )
}

fun parentFolder(path: String): String = splitNormalizedPath(path).dropLast(1).toPath()

fun fileBreadcrumbs(path: String, rootLabel: String = "Files"): List<FileBreadcrumb> {
    val parts = splitNormalizedPath(path)
    return buildList {
        add(FileBreadcrumb(label = rootLabel, path = ""))
        parts.indices.forEach { index ->
            add(
                FileBreadcrumb(
                    label = parts[index],
                    path = parts.take(index + 1).toPath(),
                ),
            )
        }
    }
}

fun normalizeFolderPath(path: String): String = splitNormalizedPath(path).toPath()

fun DownloadFile.fileExtension(): String? {
    val leaf = name.ifBlank { path.orEmpty().substringAfterLast('/').substringAfterLast('\\') }
        .substringBefore('?')
        .substringBefore('#')
    val dot = leaf.lastIndexOf('.')
    if (dot <= 0 || dot == leaf.lastIndex) return null
    return leaf.substring(dot + 1).lowercase(Locale.ROOT)
}

private data class ParsedFilePath(
    val file: DownloadFile,
    val directories: List<String>,
)

private data class LogicalFile(
    val file: DownloadFile,
    val parentSegments: List<String>,
) {
    val relativePath: String = (parentSegments + file.name).toPath()
    val parentPath: String = parentSegments.toPath()
    val extension: String? = file.fileExtension()
    val searchText: String = buildString {
        append(file.name)
        append(' ')
        append(relativePath)
        append(' ')
        append(file.path.orEmpty())
        append(' ')
        append(file.mimeType.orEmpty())
    }.lowercase(Locale.ROOT)

    fun toEntry(): FileBrowserEntry.File = FileBrowserEntry.File(
        file = file,
        relativePath = relativePath,
        parentPath = parentPath,
    )
}

private fun logicalLocations(files: List<DownloadFile>, downloadName: String?): List<LogicalFile> {
    if (files.isEmpty()) return emptyList()
    val normalizedDownloadName = normalizedComparableName(downloadName)
    val parsed = files.map { file ->
        val rawPath = file.path?.trim().orEmpty()
        val absolute = rawPath.isAbsoluteVirtualPath()
        val pathSegments = splitNormalizedPath(rawPath.ifBlank { file.name })
        val pathIncludesFilename = pathSegments.lastOrNull()
            ?.equals(file.name.trim(), ignoreCase = true) == true
        var directories = when {
            file.path.isNullOrBlank() -> emptyList()
            pathIncludesFilename -> pathSegments.dropLast(1)
            else -> pathSegments
        }

        // TorBox absolute paths commonly expose /completed/<bundle> before meaningful folders.
        // Strip that recognized server prefix, but never infer an arbitrary absolute common path
        // as disposable: it may itself be a meaningful folder when only one branch is loaded.
        val completedIndex = directories.indexOfFirst { it.equals("completed", ignoreCase = true) }
        val hasTorBoxCompletedRoot = absolute && completedIndex >= 0
        if (hasTorBoxCompletedRoot) directories = directories.drop(completedIndex + 1)

        val downloadRootIndex = if (normalizedDownloadName.isEmpty()) {
            -1
        } else {
            directories.indexOfFirst { normalizedComparableName(it) == normalizedDownloadName }
        }
        val rootedByName = downloadRootIndex >= 0
        directories = when {
            rootedByName -> directories.drop(downloadRootIndex + 1)
            hasTorBoxCompletedRoot -> directories.drop(1)
            else -> directories
        }

        ParsedFilePath(
            file = file,
            directories = directories,
        )
    }

    return parsed.map { parsedFile ->
        LogicalFile(
            file = parsedFile.file,
            parentSegments = parsedFile.directories,
        )
    }
}

private fun clampFolder(requested: String, knownFolders: Set<String>): String {
    var candidate = normalizeFolderPath(requested)
    while (candidate.isNotEmpty() && candidate !in knownFolders) {
        candidate = parentFolder(candidate)
    }
    return candidate
}

private fun fileComparator(sort: FileSort): Comparator<LogicalFile> {
    val byName = compareBy<LogicalFile>(
        { it.file.name.lowercase(Locale.ROOT) },
        { it.relativePath.lowercase(Locale.ROOT) },
        { it.file.id },
    )
    return when (sort) {
        FileSort.NAME_ASC -> byName
        FileSort.NAME_DESC -> byName.reversed()
        FileSort.SIZE_DESC -> compareByDescending<LogicalFile> { it.file.size ?: Long.MIN_VALUE }.then(byName)
        FileSort.SIZE_ASC -> compareBy<LogicalFile> { it.file.size ?: Long.MAX_VALUE }.then(byName)
        FileSort.TYPE -> compareBy<LogicalFile>(
            { it.extension ?: "\uFFFF" },
            { it.file.name.lowercase(Locale.ROOT) },
            { it.relativePath.lowercase(Locale.ROOT) },
        )
    }
}

private fun folderComparator(sort: FileSort): Comparator<FileBrowserEntry.Folder> {
    val byName = compareBy<FileBrowserEntry.Folder>({ it.name.lowercase(Locale.ROOT) }, { it.path })
    return when (sort) {
        FileSort.NAME_DESC -> byName.reversed()
        FileSort.SIZE_DESC -> compareByDescending<FileBrowserEntry.Folder> { it.totalSize ?: Long.MIN_VALUE }.then(byName)
        FileSort.SIZE_ASC -> compareBy<FileBrowserEntry.Folder> { it.totalSize ?: Long.MAX_VALUE }.then(byName)
        FileSort.NAME_ASC, FileSort.TYPE -> byName
    }
}

private fun normalizeExtension(value: String?): String? = value
    ?.trim()
    ?.removePrefix(".")
    ?.lowercase(Locale.ROOT)
    ?.takeIf(String::isNotBlank)

private fun splitNormalizedPath(path: String): List<String> {
    if (path.isBlank()) return emptyList()
    return buildList {
        path.replace('\\', '/').split('/').forEach { rawSegment ->
            val segment = rawSegment.trim()
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> if (isNotEmpty()) removeAt(lastIndex)
                segment.matches(Regex("^[A-Za-z]:$")) && isEmpty() -> Unit
                else -> add(segment)
            }
        }
    }
}

private fun String.isAbsoluteVirtualPath(): Boolean {
    val value = trim()
    return value.startsWith('/') || value.startsWith('\\') || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)
}

private fun normalizedComparableName(value: String?): String = value.orEmpty()
    .lowercase(Locale.ROOT)
    .filter(Char::isLetterOrDigit)

private fun List<String>.startsWith(prefix: List<String>): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index].equals(prefix[index], ignoreCase = true) }

private fun List<String>.toPath(): String = joinToString("/")

private fun Collection<LogicalFile>.knownSizeSum(): Long? {
    if (isEmpty() || any { it.file.size == null }) return null
    return fold(0L) { total, location ->
        val value = requireNotNull(location.file.size)
        if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }
}
