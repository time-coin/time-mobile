package com.timecoin.wallet.crypto

import org.junit.Assert.*
import org.junit.Test

class KeypairTest {

    @Test
    fun `generate creates valid 32-byte keys`() {
        val kp = Keypair.generate()
        assertEquals(32, kp.publicKeyBytes().size)
        assertEquals(32, kp.secretKeyBytes().size)
    }

    @Test
    fun `fromBytes round-trips correctly`() {
        val kp = Keypair.generate()
        val restored = Keypair.fromBytes(kp.secretKeyBytes())
        assertArrayEquals(kp.publicKeyBytes(), restored.publicKeyBytes())
        assertArrayEquals(kp.secretKeyBytes(), restored.secretKeyBytes())
    }

    @Test
    fun `fromHex round-trips correctly`() {
        val kp = Keypair.generate()
        val hex = kp.secretKeyHex()
        val restored = Keypair.fromHex(hex)
        assertArrayEquals(kp.publicKeyBytes(), restored.publicKeyBytes())
    }

    @Test
    fun `sign produces 64-byte signature`() {
        val kp = Keypair.generate()
        val message = "Hello TIME Coin".toByteArray()
        val sig = kp.sign(message)
        assertEquals(64, sig.size)
    }

    @Test
    fun `sign and verify round-trip`() {
        val kp = Keypair.generate()
        val message = "test message".toByteArray()
        val sig = kp.sign(message)
        assertTrue(kp.verify(message, sig))
    }

    @Test
    fun `verify rejects tampered message`() {
        val kp = Keypair.generate()
        val message = "original".toByteArray()
        val sig = kp.sign(message)
        assertFalse(kp.verify("tampered".toByteArray(), sig))
    }

    @Test
    fun `verify rejects wrong key`() {
        val kp1 = Keypair.generate()
        val kp2 = Keypair.generate()
        val message = "test".toByteArray()
        val sig = kp1.sign(message)
        assertFalse(kp2.verify(message, sig))
    }

    @Test
    fun `verifyWithPublicKey works standalone`() {
        val kp = Keypair.generate()
        val message = "standalone verify".toByteArray()
        val sig = kp.sign(message)
        assertTrue(Keypair.verifyWithPublicKey(kp.publicKeyBytes(), message, sig))
    }

    @Test
    fun `deterministic - same secret key gives same public key`() {
        val secret = ByteArray(32) { it.toByte() }
        val kp1 = Keypair.fromBytes(secret)
        val kp2 = Keypair.fromBytes(secret)
        assertArrayEquals(kp1.publicKeyBytes(), kp2.publicKeyBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromBytes rejects wrong size`() {
        Keypair.fromBytes(ByteArray(16))
    }

    @Test
    fun `empty message can be signed and verified`() {
        val kp = Keypair.generate()
        val sig = kp.sign(ByteArray(0))
        assertTrue(kp.verify(ByteArray(0), sig))
    }
}

class HexUtilTest {

    @Test
    fun `toHexString encodes correctly`() {
        assertEquals("00ff10", byteArrayOf(0, -1, 16).toHexString())
        assertEquals("", ByteArray(0).toHexString())
    }

    @Test
    fun `hexToByteArray decodes correctly`() {
        assertArrayEquals(byteArrayOf(0, -1, 16), "00ff10".hexToByteArray())
        assertArrayEquals(ByteArray(0), "".hexToByteArray())
    }

    @Test
    fun `hex round-trip`() {
        val original = byteArrayOf(1, 2, 127, -128, -1, 0)
        assertArrayEquals(original, original.toHexString().hexToByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hexToByteArray rejects odd-length strings`() {
        "abc".hexToByteArray()
    }
}
