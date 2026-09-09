package app.jabs.torboxdrop.notifications

/** Non-secret synchronous probe used only to avoid stopping monitoring while Drive work remains. */
internal object AdditionalMonitoredWork {
    @Volatile
    private var probe: (() -> Boolean)? = null

    fun install(probe: () -> Boolean) {
        this.probe = probe
    }

    fun hasWork(): Boolean = runCatching { probe?.invoke() == true }.getOrDefault(false)
}
