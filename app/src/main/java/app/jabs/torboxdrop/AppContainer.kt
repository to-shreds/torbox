package app.jabs.torboxdrop

import android.content.Context
import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.data.LocalStore
import app.jabs.torboxdrop.data.SecureTokenStore
import app.jabs.torboxdrop.data.TorBoxApiClient
import app.jabs.torboxdrop.data.TorBoxDriveIntegrationClient
import app.jabs.torboxdrop.data.TorBoxRepository
import app.jabs.torboxdrop.drive.DriveStore
import app.jabs.torboxdrop.drive.GoogleDriveApiClient
import app.jabs.torboxdrop.drive.GoogleDriveAuthorizationManager
import app.jabs.torboxdrop.drive.driveAccountScope
import app.jabs.torboxdrop.model.AccountInfo
import app.jabs.torboxdrop.notifications.AdditionalMonitoredWork
import app.jabs.torboxdrop.notifications.CompletionMonitorScheduler
import app.jabs.torboxdrop.notifications.CompletionMonitorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val preferences = AppPreferences(appContext)
    val tokenStore = SecureTokenStore(appContext)
    val localStore = LocalStore(appContext)
    val driveStore = DriveStore(appContext)
    val googleDriveAuthorization = GoogleDriveAuthorizationManager(appContext)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val api = TorBoxApiClient(httpClient = httpClient, tokenProvider = tokenStore::read)
    val driveIntegration = TorBoxDriveIntegrationClient(httpClient = httpClient, tokenProvider = tokenStore::read)
    val googleDriveApi = GoogleDriveApiClient(httpClient)
    val repository = TorBoxRepository(
        api = api,
        localStore = localStore,
        preferences = preferences,
        driveStore = driveStore,
        tokenProvider = tokenStore::read,
        onDriveWatchArmed = {
            CompletionMonitorScheduler.scheduleFallback(appContext)
            CompletionMonitorScheduler.runSoon(appContext)
            CompletionMonitorService.startFromUserAction(appContext)
        },
    )

    init {
        AdditionalMonitoredWork.install {
            val accountScope = driveAccountScope(tokenStore.read()) ?: return@install false
            runBlocking { driveStore.hasRunnableWork(accountScope) }
        }
    }

    suspend fun validateToken(candidate: String): AccountInfo = TorBoxApiClient(
        httpClient = httpClient,
        tokenProvider = { candidate.trim() },
    ).getCurrentUser()
}
