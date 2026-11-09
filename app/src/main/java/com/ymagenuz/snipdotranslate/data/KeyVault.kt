package com.ymagenuz.snipdotranslate.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ymagenuz.snipdotranslate.core.API_PROVIDERS
import com.ymagenuz.snipdotranslate.core.isPlaceholderApiKey
import com.ymagenuz.snipdotranslate.core.prepareSecretForWrite
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A failure at the key store; its message never contains key material. */
class KeyVaultException(message: String) : Exception(message)

interface KeyCipher {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/** Stores one encrypted API key per provider in app-private storage that is never backed up. */
class KeyVault(private val directory: File, private val cipher: KeyCipher) {
    private val lock = Mutex()

    private fun fileFor(providerId: String): File {
        require(API_PROVIDERS.any { it.providerId == providerId }) { "unsupported API provider" }
        return File(directory, providerId)
    }

    /** Returns the saved key, or "" if there is none or it can no longer be decrypted. */
    suspend fun read(providerId: String): String = withContext(Dispatchers.IO) {
        lock.withLock { readUnlocked(providerId) }
    }

    private fun readUnlocked(providerId: String): String {
        val file = fileFor(providerId)
        if (!file.exists()) return ""
        return try {
            cipher.open(file.readBytes()).decodeToString()
        } catch (_: Exception) {
            // The Keystore key can be lost, for example after clearing the device's credentials.
            ""
        }
    }

    /** Saves a key and reads it back. Returns false if it can't be a usable key. */
    suspend fun write(providerId: String, secret: String): Boolean = withContext(Dispatchers.IO) {
        val prepared = prepareSecretForWrite(secret)
        if (prepared == null || isPlaceholderApiKey(prepared)) return@withContext false
        lock.withLock {
            try {
                writeAtomically(fileFor(providerId), cipher.seal(prepared.encodeToByteArray()))
            } catch (_: Exception) {
                throw KeyVaultException("credential write failed")
            }
            if (readUnlocked(providerId) != prepared) {
                throw KeyVaultException("credential write verification failed")
            }
        }
        true
    }

    suspend fun delete(providerId: String) = withContext(Dispatchers.IO) {
        lock.withLock { fileFor(providerId).delete() }
        Unit
    }
}

/** AES-GCM with a key that stays inside the Android Keystore. */
class AndroidKeystoreCipher(private val alias: String = "snipdo_api_keys") : KeyCipher {
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return sealWith(cipher, plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val (iv, body) = splitSealed(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(body)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal const val GCM_TAG_BITS = 128

/** Sealed layout: one byte of IV length, the IV, then the ciphertext. */
internal fun sealWith(cipher: Cipher, plain: ByteArray): ByteArray {
    val body = cipher.doFinal(plain)
    val iv = cipher.iv
    return byteArrayOf(iv.size.toByte()) + iv + body
}

internal fun splitSealed(sealed: ByteArray): Pair<ByteArray, ByteArray> {
    require(sealed.isNotEmpty()) { "empty credential" }
    val ivLength = sealed[0].toInt() and 0xFF
    require(ivLength > 0 && sealed.size > 1 + ivLength) { "damaged credential" }
    return sealed.copyOfRange(1, 1 + ivLength) to sealed.copyOfRange(1 + ivLength, sealed.size)
}
