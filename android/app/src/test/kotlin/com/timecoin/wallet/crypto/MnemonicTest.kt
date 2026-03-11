package com.timecoin.wallet.crypto

import org.junit.Assert.*
import org.junit.Test

class MnemonicTest {

    @Test
    fun `generate 12-word mnemonic`() {
        val mnemonic = MnemonicHelper.generate(12)
        val words = mnemonic.split(" ")
        assertEquals(12, words.size)
        assertTrue(words.all { it.isNotBlank() })
    }

    @Test
    fun `generate 24-word mnemonic`() {
        val mnemonic = MnemonicHelper.generate(24)
        val words = mnemonic.split(" ")
        assertEquals(24, words.size)
    }

    @Test
    fun `generated mnemonic passes validation`() {
        val mnemonic = MnemonicHelper.generate(12)
        assertTrue(MnemonicHelper.validate(mnemonic))
    }

    @Test
    fun `invalid mnemonic fails validation`() {
        assertFalse(MnemonicHelper.validate("not a valid mnemonic phrase at all nope"))
        assertFalse(MnemonicHelper.validate(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generate rejects invalid word count`() {
        MnemonicHelper.generate(13)
    }

    @Test
    fun `deriveKeypairBip44 is deterministic`() {
        val mnemonic = MnemonicHelper.generate(12)
        val kp1 = MnemonicHelper.deriveKeypairBip44(mnemonic)
        val kp2 = MnemonicHelper.deriveKeypairBip44(mnemonic)
        assertArrayEquals(kp1.publicKeyBytes(), kp2.publicKeyBytes())
        assertArrayEquals(kp1.secretKeyBytes(), kp2.secretKeyBytes())
    }

    @Test
    fun `different indices produce different keys`() {
        val mnemonic = MnemonicHelper.generate(12)
        val kp0 = MnemonicHelper.deriveKeypairBip44(mnemonic, index = 0)
        val kp1 = MnemonicHelper.deriveKeypairBip44(mnemonic, index = 1)
        assertFalse(kp0.publicKeyBytes().contentEquals(kp1.publicKeyBytes()))
    }

    @Test
    fun `different accounts produce different keys`() {
        val mnemonic = MnemonicHelper.generate(12)
        val kp0 = MnemonicHelper.deriveKeypairBip44(mnemonic, account = 0)
        val kp1 = MnemonicHelper.deriveKeypairBip44(mnemonic, account = 1)
        assertFalse(kp0.publicKeyBytes().contentEquals(kp1.publicKeyBytes()))
    }

    @Test
    fun `deriveAddress produces valid TIME address`() {
        val mnemonic = MnemonicHelper.generate(12)
        val addr = MnemonicHelper.deriveAddress(mnemonic, network = NetworkType.Mainnet)
        assertTrue(addr.startsWith("TIME1"))
        assertTrue(Address.isValid(addr))
    }

    @Test
    fun `deriveAddress testnet produces TIME0 prefix`() {
        val mnemonic = MnemonicHelper.generate(12)
        val addr = MnemonicHelper.deriveAddress(mnemonic, network = NetworkType.Testnet)
        assertTrue(addr.startsWith("TIME0"))
        assertTrue(Address.isValid(addr))
    }

    @Test
    fun `derived keypair can sign and verify`() {
        val mnemonic = MnemonicHelper.generate(12)
        val kp = MnemonicHelper.deriveKeypairBip44(mnemonic)
        val message = "test signing with derived key".toByteArray()
        val sig = kp.sign(message)
        assertTrue(kp.verify(message, sig))
    }

    @Test
    fun `passphrase changes derived key`() {
        val mnemonic = MnemonicHelper.generate(12)
        val kpNoPass = MnemonicHelper.deriveKeypairBip44(mnemonic, passphrase = "")
        val kpWithPass = MnemonicHelper.deriveKeypairBip44(mnemonic, passphrase = "secret")
        assertFalse(kpNoPass.publicKeyBytes().contentEquals(kpWithPass.publicKeyBytes()))
    }

    @Test
    fun `different mnemonics produce different keys`() {
        val m1 = MnemonicHelper.generate(12)
        val m2 = MnemonicHelper.generate(12)
        val kp1 = MnemonicHelper.deriveKeypairBip44(m1)
        val kp2 = MnemonicHelper.deriveKeypairBip44(m2)
        assertFalse(kp1.publicKeyBytes().contentEquals(kp2.publicKeyBytes()))
    }
}
