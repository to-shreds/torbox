package app.jabs.torboxdrop.data

import android.content.Context
import app.jabs.torboxdrop.model.DownloadDensity
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadTab

class AppPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("torbox_drop_preferences_v2", Context.MODE_PRIVATE)
    private val driveSetup = context.getSharedPreferences("torbox_drop_drive_setup_v1", Context.MODE_PRIVATE)

    var confirmBeforeSending: Boolean
        get() = preferences.getBoolean(KEY_CONFIRM, false)
        set(value) = edit(KEY_CONFIRM, value)
    var autoSendClipboardMagnets: Boolean
        get() = preferences.getBoolean(KEY_AUTO_CLIPBOARD, true)
        set(value) = edit(KEY_AUTO_CLIPBOARD, value)
    var autoSendBrowserMagnets: Boolean
        get() = preferences.getBoolean(KEY_AUTO_BROWSER, true)
        set(value) = edit(KEY_AUTO_BROWSER, value)
    var queueByDefault: Boolean
        get() = preferences.getBoolean(KEY_QUEUE, false)
        set(value) = edit(KEY_QUEUE, value)
    var cachedOnlyByDefault: Boolean
        get() = preferences.getBoolean(KEY_CACHED_ONLY, false)
        set(value) = edit(KEY_CACHED_ONLY, value)
    var notifyNewByDefault: Boolean
        get() = preferences.getBoolean(KEY_NOTIFY_NEW, false)
        set(value) = edit(KEY_NOTIFY_NEW, value)

    /** Resettable behavioral default. Drive connection/folder setup itself is kept separately. */
    var googleDriveByDefault: Boolean
        get() = preferences.getBoolean(KEY_GOOGLE_DRIVE_DEFAULT, false)
        set(value) = edit(KEY_GOOGLE_DRIVE_DEFAULT, value)

    /** Non-secret hint only. OAuth bearer tokens are never stored here. */
    var googleDriveConnected: Boolean
        get() = driveSetup.getBoolean(KEY_GOOGLE_DRIVE_CONNECTED, false)
        set(value) = driveSetup.edit().putBoolean(KEY_GOOGLE_DRIVE_CONNECTED, value).apply()

    var googleDriveFolderId: String?
        get() = driveSetup.getString(KEY_GOOGLE_DRIVE_FOLDER_ID, null)
        set(value) {
            val normalized = value?.trim()?.takeIf(String::isNotEmpty)
            if (normalized == null) driveSetup.edit().remove(KEY_GOOGLE_DRIVE_FOLDER_ID).apply()
            else driveSetup.edit().putString(KEY_GOOGLE_DRIVE_FOLDER_ID, normalized).apply()
        }

    var googleDriveFolderName: String
        get() = driveSetup.getString(KEY_GOOGLE_DRIVE_FOLDER_NAME, DEFAULT_DRIVE_FOLDER_NAME)
            ?: DEFAULT_DRIVE_FOLDER_NAME
        set(value) = driveSetup.edit()
            .putString(KEY_GOOGLE_DRIVE_FOLDER_NAME, value.trim().ifBlank { DEFAULT_DRIVE_FOLDER_NAME })
            .apply()

    var seedPreference: Int
        get() = preferences.getInt(KEY_SEED, 1).coerceIn(1, 3)
        set(value) = edit(KEY_SEED, value.coerceIn(1, 3))
    var allowZipByDefault: Boolean
        get() = preferences.getBoolean(KEY_ALLOW_ZIP, true)
        set(value) = edit(KEY_ALLOW_ZIP, value)
    var density: DownloadDensity
        get() = enumValue(KEY_DENSITY, DownloadDensity.COMPACT)
        set(value) = edit(KEY_DENSITY, value.name)
    var downloadsTab: DownloadTab
        get() = enumValue(KEY_TAB, DownloadTab.ACTIVE)
        set(value) = edit(KEY_TAB, value.name)
    var sort: DownloadSort
        get() = enumValue(KEY_SORT, DownloadSort.NEWEST)
        set(value) = edit(KEY_SORT, value.name)
    var browserHomePage: String
        get() = preferences.getString(KEY_BROWSER_HOME, DEFAULT_HOME) ?: DEFAULT_HOME
        set(value) = edit(KEY_BROWSER_HOME, value)
    var adBlockingEnabled: Boolean
        get() = preferences.getBoolean(KEY_AD_BLOCK, true)
        set(value) = edit(KEY_AD_BLOCK, value)
    var thirdPartyCookiesEnabled: Boolean
        get() = preferences.getBoolean(KEY_THIRD_PARTY_COOKIES, false)
        set(value) = edit(KEY_THIRD_PARTY_COOKIES, value)
    var lastDownloadsRefreshEpochMillis: Long
        get() = preferences.getLong(KEY_LAST_REFRESH, 0L)
        set(value) = edit(KEY_LAST_REFRESH, value)
    var relayUserId: String?
        get() = preferences.getString(KEY_RELAY_USER_ID, null)
        set(value) {
            if (value.isNullOrBlank()) preferences.edit().remove(KEY_RELAY_USER_ID).apply()
            else edit(KEY_RELAY_USER_ID, value.trim())
        }

    fun siteExceptions(): Set<String> = preferences.getStringSet(KEY_SITE_EXCEPTIONS, emptySet())?.toSet().orEmpty()
    fun setSiteException(host: String, disabled: Boolean) {
        val normalized = host.trim().lowercase()
        if (normalized.isBlank()) return
        val next = siteExceptions().toMutableSet()
        if (disabled) next += normalized else next -= normalized
        preferences.edit().putStringSet(KEY_SITE_EXCEPTIONS, next).apply()
    }
    fun resetDownloadViewPreferences() {
        preferences.edit().remove(KEY_DENSITY).remove(KEY_TAB).remove(KEY_SORT).apply()
    }
    fun resetAll() { preferences.edit().clear().apply() }

    private fun edit(key: String, value: Boolean) = preferences.edit().putBoolean(key, value).apply()
    private fun edit(key: String, value: Int) = preferences.edit().putInt(key, value).apply()
    private fun edit(key: String, value: Long) = preferences.edit().putLong(key, value).apply()
    private fun edit(key: String, value: String) = preferences.edit().putString(key, value).apply()
    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    companion object {
        const val DEFAULT_HOME = "https://www.google.com"
        const val DEFAULT_DRIVE_FOLDER_NAME = "TorBox Drop"
        private const val KEY_CONFIRM = "confirm_before_sending"
        private const val KEY_AUTO_CLIPBOARD = "auto_send_clipboard_magnets"
        private const val KEY_AUTO_BROWSER = "auto_send_browser_magnets"
        private const val KEY_QUEUE = "queue_by_default"
        private const val KEY_CACHED_ONLY = "cached_only_by_default"
        private const val KEY_NOTIFY_NEW = "notify_new_by_default"
        private const val KEY_GOOGLE_DRIVE_DEFAULT = "google_drive_by_default"
        private const val KEY_GOOGLE_DRIVE_CONNECTED = "google_drive_connected"
        private const val KEY_GOOGLE_DRIVE_FOLDER_ID = "google_drive_folder_id"
        private const val KEY_GOOGLE_DRIVE_FOLDER_NAME = "google_drive_folder_name"
        private const val KEY_SEED = "seed_preference"
        private const val KEY_ALLOW_ZIP = "allow_zip_by_default"
        private const val KEY_DENSITY = "download_density"
        private const val KEY_TAB = "downloads_tab"
        private const val KEY_SORT = "downloads_sort"
        private const val KEY_BROWSER_HOME = "browser_home"
        private const val KEY_AD_BLOCK = "browser_ad_block"
        private const val KEY_THIRD_PARTY_COOKIES = "browser_third_party_cookies"
        private const val KEY_SITE_EXCEPTIONS = "browser_site_exceptions"
        private const val KEY_LAST_REFRESH = "last_downloads_refresh"
        private const val KEY_RELAY_USER_ID = "relay_user_id"
    }
}
