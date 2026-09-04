package app.jabs.torboxdrop.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only AES-GCM ciphertext outside backup scope; the non-exportable key lives in AndroidKeyStore. */
class SecureTokenStore(context: Context) {
    private val tokenFile = File(context.noBackupFilesDir, "torbox_api_token.v1")
    private val lock = Any()

    fun hasToken(): Boolean = synchronized(lock) { tokenFile.isFile && tokenFile.length() > 0L }

    fun save(token: String) = synchronized(lock) {
        val normalized = token.trim()
        require(normalized.isNotEmpty()) { "Token cannot be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(normalized.toByteArray(StandardCharsets.UTF_8))
        val payload = listOf(
            FORMAT_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP),
        ).joinToString("\n")
        val temporary = File(tokenFile.parentFile, "${tokenFile.name}.tmp")
        temporary.writeText(payload, StandardCharsets.UTF_8)
        if (!temporary.renameTo(tokenFile)) {
            temporary.copyTo(tokenFile, overwrite = true)
            temporary.delete()
        }
    }

    fun read(): String? = synchronized(lock) {
        if (!tokenFile.isFile) return@synchronized null
        try {
            val parts = tokenFile.readLines(StandardCharsets.UTF_8)
            if (parts.size != 3 || parts[0] != FORMAT_VERSION) {
                clearLocked()
                return@synchronized null
            }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: AEADBadTagException) {
            clearLocked()
            null
        } catch (_: Exception) {
            clearLocked()
            null
        }
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        if (tokenFile.exists()) tokenFile.delete()
        runCatching {
            keyStore().apply {
                if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getExistingKey(): SecretKey {
        return keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("Secure token key is unavailable")
    }

    private fun getOrCreateKey(): SecretKey {
        runCatching { getExistingKey() }.getOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "torbox_drop_api_token_v1"
        const val FORMAT_VERSION = "TBD1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
