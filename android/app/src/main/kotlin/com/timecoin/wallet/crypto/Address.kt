package com.timecoin.wallet.crypto

import java.security.MessageDigest
import java.math.BigInteger

/**
 * TIME Coin address format — matches the desktop wallet (address.rs).
 *
 * Format: TIME{network_digit}{base58(payload[20] + checksum[4])}
 *   - TIME1... = mainnet
 *   - TIME0... = testnet
 *   - payload  = first 20 bytes of SHA-256(public_key)
 *   - checksum = first 4 bytes of SHA-256(SHA-256(payload))
 */
data class Address(
    val network: NetworkType,
    val payload: ByteArray,
) {
    init {
        require(payload.size == 20) { "Payload must be 20 bytes" }
    }

    fun isTestnet(): Boolean = network == NetworkType.Testnet

    override fun toString(): String = formatAddress()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Address) return false
        return network == other.network && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * network.hashCode() + payload.contentHashCode()

    private fun formatAddress(): String {
        val networkDigit = when (network) {
            NetworkType.Testnet -> '0'
            NetworkType.Mainnet -> '1'
        }
        val checksum = computeChecksum(payload)
        val data = ByteArray(24)
        System.arraycopy(payload, 0, data, 0, 20)
        System.arraycopy(checksum, 0, data, 20, 4)
        return "TIME$networkDigit${Base58.encode(data)}"
    }

    companion object {
        /** Create an address from a 32-byte Ed25519 public key. */
        fun fromPublicKey(publicKey: ByteArray, network: NetworkType): Address {
            require(publicKey.size == 32) { "Public key must be 32 bytes" }
            val payload = hashPublicKey(publicKey)
            return Address(network, payload)
        }

        /** Parse a TIME address string (TIME0... or TIME1...). */
        fun fromString(s: String): Address {
            require(s.length in 35..45) { "Invalid address length: ${s.length}" }
            require(s.startsWith("TIME")) { "Address must start with TIME" }

            val network = when (s[4]) {
                '0' -> NetworkType.Testnet
                '1' -> NetworkType.Mainnet
                else -> throw IllegalArgumentException("Invalid network digit: ${s[4]}")
            }

            val encoded = s.substring(5)
            val decoded = Base58.decode(encoded)
            require(decoded.size == 24) { "Invalid decoded payload length: ${decoded.size}" }

            val payloadBytes = decoded.copyOfRange(0, 20)
            val checksum = decoded.copyOfRange(20, 24)
            val computedChecksum = computeChecksum(payloadBytes)

            require(checksum.contentEquals(computedChecksum)) { "Invalid address checksum" }

            return Address(network, payloadBytes)
        }

        /** Validate an address string without throwing. */
        fun isValid(s: String): Boolean = try {
            fromString(s); true
        } catch (_: Exception) {
            false
        }

        /** SHA-256 → first 20 bytes (matches desktop wallet). */
        private fun hashPublicKey(publicKey: ByteArray): ByteArray {
            val sha = MessageDigest.getInstance("SHA-256")
            val hash = sha.digest(publicKey)
            return hash.copyOfRange(0, 20)
        }

        /** Double SHA-256 → first 4 bytes. */
        private fun computeChecksum(data: ByteArray): ByteArray {
            val sha = MessageDigest.getInstance("SHA-256")
            val hash1 = sha.digest(data)
            sha.reset()
            val hash2 = sha.digest(hash1)
            return hash2.copyOfRange(0, 4)
        }
    }
}

enum class NetworkType {
    Mainnet,
    Testnet;

    /** RPC port for this network. */
    val rpcPort: Int get() = when (this) {
        Mainnet -> 24001
        Testnet -> 24101
    }

    /** Canonical genesis block hash — matches constants.rs in time-masternode. */
    val genesisHash: String get() = when (this) {
        Mainnet -> "45181d4c65a3a2bcc2215d037267bee4cc2248f21764466846d2b7218b601ce5"
        Testnet -> "b9523431d4e59a1b41d757a8c0f01ed023c11123761b1455e4644ef9d5599ff6"
    }
}

/**
 * Base58 encoding/decoding (same algorithm as the Rust wallet's
 * num-bigint based implementation).
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(data: ByteArray): String {
        var num = BigInteger(1, data)
        val sb = StringBuilder()

        while (num > BigInteger.ZERO) {
            val (quotient, remainder) = num.divideAndRemainder(BASE)
            sb.insert(0, ALPHABET[remainder.toInt()])
            num = quotient
        }

        // Leading zeros → leading '1's
        for (b in data) {
            if (b == 0.toByte()) sb.insert(0, '1') else break
        }

        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        var num = BigInteger.ZERO

        for (c in s) {
            val idx = ALPHABET.indexOf(c)
            require(idx >= 0) { "Invalid Base58 character: $c" }
            num = num.multiply(BASE).add(BigInteger.valueOf(idx.toLong()))
        }

        val stripped = if (num == BigInteger.ZERO) {
            ByteArray(0)
        } else {
            val bytes = num.toByteArray()
            // BigInteger may add a leading 0x00 for sign — strip it
            if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }

        val leadingOnes = s.takeWhile { it == '1' }.length
        val result = ByteArray(leadingOnes + stripped.size)
        System.arraycopy(stripped, 0, result, leadingOnes, stripped.size)

        return result
    }
}
