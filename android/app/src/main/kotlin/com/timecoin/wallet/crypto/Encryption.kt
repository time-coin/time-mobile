package com.timecoin.wallet.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption with Argon2id KDF — matches the desktop wallet's
 * encryption.rs for wallet file compatibility.
 */
object WalletEncryption {

    private const val AES_KEY_SIZE = 32
    private const val GCM_NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val SALT_SIZE = 16

    // Argon2id parameters (match BouncyCastle defaults / desktop wallet)
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_MEMORY_KB = 65536 // 64 MB
    private const val ARGON2_PARALLELISM = 1

    /** Encrypted wallet data container — serializable for storage. */
    data class EncryptedData(
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val version: Int = 1,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedData) return false
            return salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext) &&
                version == other.version
        }

        override fun hashCode(): Int {
            var result = salt.contentHashCode()
            result = 31 * result + nonce.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            result = 31 * result + version
            return result
        }
    }

    /** Encrypt data with a password. */
    fun encrypt(plaintext: ByteArray, password: String): EncryptedData {
        val random = SecureRandom()

        val salt = ByteArray(SALT_SIZE).also { random.nextBytes(it) }
        val nonce = ByteArray(GCM_NONCE_SIZE).also { random.nextBytes(it) }

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        key.fill(0) // zeroize

        return EncryptedData(salt = salt, nonce = nonce, ciphertext = ciphertext)
    }

    /** Decrypt data with a password. Throws on wrong password. */
    fun decrypt(encrypted: EncryptedData, password: String): ByteArray {
        val key = deriveKey(password, encrypted.salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, encrypted.nonce))

        key.fill(0) // zeroize

        return cipher.doFinal(encrypted.ciphertext)
    }

    /** Derive a 256-bit key from password + salt using Argon2id. */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withIterations(ARGON2_ITERATIONS)
            .withMemoryAsKB(ARGON2_MEMORY_KB)
            .withParallelism(ARGON2_PARALLELISM)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)

        val key = ByteArray(AES_KEY_SIZE)
        generator.generateBytes(password.toByteArray(), key)
        return key
    }
}
