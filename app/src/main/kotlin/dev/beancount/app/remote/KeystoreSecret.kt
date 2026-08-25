package dev.beancount.app.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small secret — a remote password — kept where the app cannot leak it by accident.
 *
 * The key lives in the **Android Keystore**, which means this process can ask for
 * encryption and decryption but can never read the key itself: it is not in the app's
 * memory, not in its files, and not in a backup. What is on disk is ciphertext and a nonce,
 * in app-private storage, with `allowBackup=false` above it
 * (docs/threat-model.md §3.5).
 *
 * This is deliberately not `EncryptedSharedPreferences`: that would add a dependency whose
 * own future is uncertain, for something that is forty lines of platform API. The tradeoff
 * is that it must be tested on a device, which it is.
 */
class KeystoreSecret(private val file: File, private val alias: String = DEFAULT_ALIAS) {

    fun store(value: CharArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keyFor(alias))
        }
        val plaintext = String(value).toByteArray()
        val ciphertext = cipher.doFinal(plaintext)
        plaintext.fill(0)

        file.parentFile?.mkdirs()
        // Nonce first, then ciphertext: one file, nothing to fall out of step.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * @return the stored value, or null when there is none — or when it can no longer be
     *   decrypted, which happens for real: the Keystore key is dropped when the device's
     *   screen lock is removed or the app's data is cleared. That is a "sign in again",
     *   not a crash.
     */
    fun read(): CharArray? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            val nonceSize = bytes[0].toInt()
            val nonce = bytes.copyOfRange(1, 1 + nonceSize)
            val ciphertext = bytes.copyOfRange(1 + nonceSize, bytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, nonce))
            }
            String(cipher.doFinal(ciphertext)).toCharArray()
        }.getOrNull()
    }

    fun clear() {
        file.delete()
    }

    /** Forgets the key itself, so anything already written becomes unreadable for good. */
    fun destroyKey() {
        runCatching { keystore().deleteEntry(alias) }
        clear()
    }

    private fun keystore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? =
        (keystore().getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun keyFor(alias: String): SecretKey = existingKey() ?: generate(alias)

    private fun generate(alias: String): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    // Deliberately *not* setUserAuthenticationRequired: a scheduled backup
                    // or a widget refresh happens with the screen off, and a credential that
                    // needs a fingerprint at 3am is a credential that silently fails.
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val DEFAULT_ALIAS = "dev.beancount.remote.credentials"
    }
}
