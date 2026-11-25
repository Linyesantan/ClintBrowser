package com.jhaiian.clint.cloud

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV connection settings shared by cloud backup (and future bookmark sync).
 *
 * Stored in a dedicated preferences file that is NOT part of the in-app backup targets, so
 * restoring a backup never overwrites the connection settings. The password is encrypted with
 * an AES-GCM key that lives in the Android Keystore and never leaves the device.
 */
object WebDavSettings {
    const val PREFS_NAME = "clint_webdav"

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD_ENC = "password_enc"

    const val KEY_AUTO_BACKUP = "cloud_backup_auto_enabled"
    const val KEY_KEEP_COUNT = "cloud_backup_keep_count"
    const val KEY_MAX_SIZE_MB = "cloud_backup_max_size_mb"

    const val DEFAULT_KEEP_COUNT = 10
    const val MIN_KEEP_COUNT = 1
    const val MAX_KEEP_COUNT = 100
    const val DEFAULT_MAX_SIZE_MB = 200
    const val MIN_MAX_SIZE_MB = 1
    const val MAX_MAX_SIZE_MB = 2048

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "clint_webdav_password_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    data class Config(val serverUrl: String, val username: String, val password: String) {
        val isComplete: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank()
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            serverUrl = p.getString(KEY_SERVER_URL, "") ?: "",
            username = p.getString(KEY_USERNAME, "") ?: "",
            password = p.getString(KEY_PASSWORD_ENC, null)?.let { decrypt(it) } ?: ""
        )
    }

    fun saveServerUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, value.trim()).apply()
    }

    fun saveUsername(context: Context, value: String) {
        prefs(context).edit().putString(KEY_USERNAME, value.trim()).apply()
    }

    fun savePassword(context: Context, value: String) {
        val editor = prefs(context).edit()
        if (value.isEmpty()) {
            editor.remove(KEY_PASSWORD_ENC)
        } else {
            val encrypted = encrypt(value) ?: return
            editor.putString(KEY_PASSWORD_ENC, encrypted)
        }
        editor.apply()
    }

    fun isAutoBackupEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_BACKUP, false)

    fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_BACKUP, enabled).apply()
    }

    fun keepCount(context: Context): Int =
        prefs(context).getInt(KEY_KEEP_COUNT, DEFAULT_KEEP_COUNT).coerceIn(MIN_KEEP_COUNT, MAX_KEEP_COUNT)

    fun setKeepCount(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_KEEP_COUNT, value.coerceIn(MIN_KEEP_COUNT, MAX_KEEP_COUNT)).apply()
    }

    fun maxSizeMb(context: Context): Int =
        prefs(context).getInt(KEY_MAX_SIZE_MB, DEFAULT_MAX_SIZE_MB).coerceIn(MIN_MAX_SIZE_MB, MAX_MAX_SIZE_MB)

    fun setMaxSizeMb(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_MAX_SIZE_MB, value.coerceIn(MIN_MAX_SIZE_MB, MAX_MAX_SIZE_MB)).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val parts = stored.split(":")
        if (parts.size != 2) return null
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()
}
