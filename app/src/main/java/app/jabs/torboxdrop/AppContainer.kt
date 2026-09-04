package app.jabs.torboxdrop

import android.content.Context
import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.data.LocalStore
import app.jabs.torboxdrop.data.SecureTokenStore
import app.jabs.torboxdrop.data.TorBoxApiClient
import app.jabs.torboxdrop.data.TorBoxRepository
import app.jabs.torboxdrop.model.AccountInfo
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    val preferences = AppPreferences(context)
    val tokenStore = SecureTokenStore(context)
    val localStore = LocalStore(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val api = TorBoxApiClient(
        httpClient = httpClient,
        tokenProvider = tokenStore::read,
    )
    val repository = TorBoxRepository(api, localStore, preferences)

    suspend fun validateToken(candidate: String): AccountInfo = TorBoxApiClient(
        httpClient = httpClient,
        tokenProvider = { candidate.trim() },
    ).getCurrentUser()
}
