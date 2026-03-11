package com.timecoin.wallet.crypto

import org.junit.Assert.*
import org.junit.Test

class EncryptionTest {

    @Test
    fun `encrypt and decrypt round-trip`() {
        val plaintext = "Hello TIME Coin wallet!".toByteArray()
        val password = "testpassword123"
        val encrypted = WalletEncryption.encrypt(plaintext, password)
        val decrypted = WalletEncryption.decrypt(encrypted, password)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypted data has correct component sizes`() {
        val encrypted = WalletEncryption.encrypt("test".toByteArray(), "pass")
        assertEquals(16, encrypted.salt.size)    // SALT_SIZE
        assertEquals(12, encrypted.nonce.size)    // GCM_NONCE_SIZE
        assertTrue(encrypted.ciphertext.isNotEmpty())
    }

    @Test
    fun `different encryptions produce different ciphertext`() {
        val plaintext = "same data".toByteArray()
        val password = "samepass"
        val e1 = WalletEncryption.encrypt(plaintext, password)
        val e2 = WalletEncryption.encrypt(plaintext, password)
        // Salt and nonce are random, so ciphertext should differ
        assertFalse(e1.salt.contentEquals(e2.salt))
        assertFalse(e1.ciphertext.contentEquals(e2.ciphertext))
    }

    @Test(expected = Exception::class)
    fun `wrong password throws on decrypt`() {
        val encrypted = WalletEncryption.encrypt("secret".toByteArray(), "correctpass")
        WalletEncryption.decrypt(encrypted, "wrongpass")
    }

    @Test
    fun `empty plaintext round-trips`() {
        val encrypted = WalletEncryption.encrypt(ByteArray(0), "pass")
        val decrypted = WalletEncryption.decrypt(encrypted, "pass")
        assertArrayEquals(ByteArray(0), decrypted)
    }

    @Test
    fun `large data round-trips`() {
        val plaintext = ByteArray(10_000) { (it % 256).toByte() }
        val password = "strongpassword"
        val encrypted = WalletEncryption.encrypt(plaintext, password)
        val decrypted = WalletEncryption.decrypt(encrypted, password)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `unicode password works`() {
        val plaintext = "data".toByteArray()
        val password = "p@\$\$w0rd!key"
        val encrypted = WalletEncryption.encrypt(plaintext, password)
        val decrypted = WalletEncryption.decrypt(encrypted, password)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = Exception::class)
    fun `tampered ciphertext fails`() {
        val encrypted = WalletEncryption.encrypt("data".toByteArray(), "pass")
        val tampered = encrypted.ciphertext.clone()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
        WalletEncryption.decrypt(
            WalletEncryption.EncryptedData(encrypted.salt, encrypted.nonce, tampered),
            "pass"
        )
    }
}
