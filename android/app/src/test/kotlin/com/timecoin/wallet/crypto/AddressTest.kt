package com.timecoin.wallet.crypto

import org.junit.Assert.*
import org.junit.Test

class AddressTest {

    @Test
    fun `fromPublicKey produces valid mainnet address`() {
        val kp = Keypair.generate()
        val addr = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val str = addr.toString()
        assertTrue("Address should start with TIME1", str.startsWith("TIME1"))
        assertTrue("Address length should be 35-45", str.length in 35..45)
    }

    @Test
    fun `fromPublicKey produces valid testnet address`() {
        val kp = Keypair.generate()
        val addr = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Testnet)
        val str = addr.toString()
        assertTrue("Address should start with TIME0", str.startsWith("TIME0"))
    }

    @Test
    fun `fromString round-trips mainnet address`() {
        val kp = Keypair.generate()
        val original = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val parsed = Address.fromString(original.toString())
        assertEquals(original, parsed)
        assertEquals(NetworkType.Mainnet, parsed.network)
    }

    @Test
    fun `fromString round-trips testnet address`() {
        val kp = Keypair.generate()
        val original = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Testnet)
        val parsed = Address.fromString(original.toString())
        assertEquals(original, parsed)
        assertEquals(NetworkType.Testnet, parsed.network)
    }

    @Test
    fun `same public key gives same address`() {
        val secret = ByteArray(32) { it.toByte() }
        val kp = Keypair.fromBytes(secret)
        val addr1 = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val addr2 = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        assertEquals(addr1.toString(), addr2.toString())
    }

    @Test
    fun `different networks give different addresses`() {
        val kp = Keypair.generate()
        val mainnet = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet).toString()
        val testnet = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Testnet).toString()
        assertNotEquals(mainnet, testnet)
        assertEquals("1", mainnet.substring(4, 5))
        assertEquals("0", testnet.substring(4, 5))
    }

    @Test
    fun `isValid returns true for valid address`() {
        val kp = Keypair.generate()
        val addr = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet).toString()
        assertTrue(Address.isValid(addr))
    }

    @Test
    fun `isValid returns false for invalid addresses`() {
        assertFalse(Address.isValid(""))
        assertFalse(Address.isValid("not an address"))
        assertFalse(Address.isValid("TIME2abc"))
        assertFalse(Address.isValid("BTC1abcdef"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromString rejects bad prefix`() {
        Address.fromString("BTC1" + "A".repeat(35))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromString rejects invalid network digit`() {
        Address.fromString("TIME2" + "A".repeat(35))
    }

    @Test
    fun `isTestnet works correctly`() {
        val kp = Keypair.generate()
        val mainnet = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Mainnet)
        val testnet = Address.fromPublicKey(kp.publicKeyBytes(), NetworkType.Testnet)
        assertFalse(mainnet.isTestnet())
        assertTrue(testnet.isTestnet())
    }
}

class Base58Test {

    @Test
    fun `encode and decode round-trip`() {
        val data = byteArrayOf(1, 2, 3, 4, 5, 10, 20, 30)
        val encoded = Base58.encode(data)
        val decoded = Base58.decode(encoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `leading zeros preserved`() {
        val data = byteArrayOf(0, 0, 0, 1, 2, 3)
        val encoded = Base58.encode(data)
        assertTrue("Should start with leading 1s", encoded.startsWith("111"))
        val decoded = Base58.decode(encoded)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `empty input`() {
        val encoded = Base58.encode(ByteArray(0))
        assertEquals("", encoded)
    }

    @Test
    fun `single byte round-trip`() {
        for (b in 0..255) {
            val data = byteArrayOf(b.toByte())
            val decoded = Base58.decode(Base58.encode(data))
            assertArrayEquals("Failed for byte $b", data, decoded)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects invalid characters`() {
        Base58.decode("0OIl") // 0, O, I, l are excluded from Base58
    }
}

class NetworkTypeTest {

    @Test
    fun `mainnet rpc port is 24001`() {
        assertEquals(24001, NetworkType.Mainnet.rpcPort)
    }

    @Test
    fun `testnet rpc port is 24101`() {
        assertEquals(24101, NetworkType.Testnet.rpcPort)
    }
}
