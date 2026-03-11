package com.timecoin.wallet.crypto

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BIP-39 mnemonic generation + SLIP-0010 HD key derivation for Ed25519.
 *
 * Mirrors the desktop wallet's mnemonic.rs exactly:
 *   - SLIP-0010 master key from seed (HMAC-SHA512 with key "ed25519 seed")
 *   - Hardened child derivation at each level
 *   - BIP-44 path: m/44'/0'/account'/change'/index'
 */
object MnemonicHelper {

    /** Generate a new BIP-39 mnemonic phrase. */
    fun generate(wordCount: Int = 12): String {
        val entropySize = when (wordCount) {
            12 -> Mnemonics.WordCount.COUNT_12
            15 -> Mnemonics.WordCount.COUNT_15
            18 -> Mnemonics.WordCount.COUNT_18
            21 -> Mnemonics.WordCount.COUNT_21
            24 -> Mnemonics.WordCount.COUNT_24
            else -> throw IllegalArgumentException("Invalid word count: $wordCount (must be 12, 15, 18, 21, or 24)")
        }
        val mc = Mnemonics.MnemonicCode(entropySize)
        return String(mc.chars)
    }

    /** Validate a mnemonic phrase. Returns true if valid. */
    fun validate(phrase: String): Boolean = try {
        Mnemonics.MnemonicCode(phrase).validate()
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Derive a keypair at the full SLIP-0010 / BIP-44 path.
     * Path: m/44'/0'/account'/change'/index'  (all hardened, Ed25519 requirement)
     */
    fun deriveKeypairBip44(
        phrase: String,
        passphrase: String = "",
        account: Int = 0,
        change: Int = 0,
        index: Int = 0,
    ): Keypair {
        val seed = mnemonicToSeed(phrase, passphrase)
        val path = intArrayOf(44, 0, account, change, index)
        val key = slip10DerivePath(seed, path)
        return Keypair.fromBytes(key)
    }

    /**
     * Derive an address string at the given BIP-44 index.
     */
    fun deriveAddress(
        phrase: String,
        passphrase: String = "",
        account: Int = 0,
        change: Int = 0,
        index: Int = 0,
        network: NetworkType,
    ): String {
        val keypair = deriveKeypairBip44(phrase, passphrase, account, change, index)
        return Address.fromPublicKey(keypair.publicKeyBytes(), network).toString()
    }

    // ── Internal SLIP-0010 implementation ──

    private fun mnemonicToSeed(phrase: String, passphrase: String): ByteArray {
        val mc = Mnemonics.MnemonicCode(phrase)
        return mc.toSeed(passphrase.toCharArray())
    }

    /** Derive SLIP-0010 master key: HMAC-SHA512("ed25519 seed", seed) */
    private fun slip10MasterKey(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec("ed25519 seed".toByteArray(), "HmacSHA512"))
        val result = mac.doFinal(seed)
        return Pair(result.copyOfRange(0, 32), result.copyOfRange(32, 64))
    }

    /** Hardened child derivation (Ed25519 only supports hardened). */
    private fun slip10DeriveChild(
        key: ByteArray,
        chainCode: ByteArray,
        index: Int,
    ): Pair<ByteArray, ByteArray> {
        val hardened = index or 0x80000000.toInt()
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(chainCode, "HmacSHA512"))

        // data = 0x00 || key || hardened_index_be
        val data = ByteArray(1 + 32 + 4)
        data[0] = 0x00
        System.arraycopy(key, 0, data, 1, 32)
        data[33] = (hardened ushr 24).toByte()
        data[34] = (hardened ushr 16).toByte()
        data[35] = (hardened ushr 8).toByte()
        data[36] = hardened.toByte()

        val result = mac.doFinal(data)
        return Pair(result.copyOfRange(0, 32), result.copyOfRange(32, 64))
    }

    /** Derive through a full path. */
    private fun slip10DerivePath(seed: ByteArray, path: IntArray): ByteArray {
        var (key, chainCode) = slip10MasterKey(seed)
        for (index in path) {
            val (k, c) = slip10DeriveChild(key, chainCode, index)
            key = k
            chainCode = c
        }
        return key
    }
}
