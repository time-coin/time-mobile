package com.timecoin.wallet.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 keypair — wraps BouncyCastle to match the desktop wallet's
 * ed25519-dalek Keypair (generate, sign, verify).
 */
class Keypair private constructor(
    private val privateKey: Ed25519PrivateKeyParameters,
    val publicKey: Ed25519PublicKeyParameters,
) {
    /** 32-byte public key */
    fun publicKeyBytes(): ByteArray = publicKey.encoded

    /** 32-byte secret key */
    fun secretKeyBytes(): ByteArray = privateKey.encoded

    /** Hex-encoded secret key */
    fun secretKeyHex(): String = secretKeyBytes().toHexString()

    /** Sign a message, returning a 64-byte Ed25519 signature. */
    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Verify a signature against this keypair's public key. */
    fun verify(message: ByteArray, signature: ByteArray): Boolean {
        return verifyWithPublicKey(publicKeyBytes(), message, signature)
    }

    companion object {
        /** Generate a new random keypair. */
        fun generate(): Keypair {
            val random = SecureRandom()
            val privateKey = Ed25519PrivateKeyParameters(random)
            val publicKey = privateKey.generatePublicKey()
            return Keypair(privateKey, publicKey)
        }

        /** Create a keypair from a 32-byte secret key. */
        fun fromBytes(secretBytes: ByteArray): Keypair {
            require(secretBytes.size == 32) { "Secret key must be 32 bytes" }
            val privateKey = Ed25519PrivateKeyParameters(secretBytes, 0)
            val publicKey = privateKey.generatePublicKey()
            return Keypair(privateKey, publicKey)
        }

        /** Create a keypair from a hex-encoded secret key. */
        fun fromHex(hex: String): Keypair {
            val bytes = hex.hexToByteArray()
            return fromBytes(bytes)
        }

        /** Verify a signature using a raw 32-byte public key. */
        fun verifyWithPublicKey(
            publicKey: ByteArray,
            message: ByteArray,
            signature: ByteArray,
        ): Boolean {
            require(publicKey.size == 32) { "Public key must be 32 bytes" }
            require(signature.size == 64) { "Signature must be 64 bytes" }
            val pubKeyParams = Ed25519PublicKeyParameters(publicKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubKeyParams)
            verifier.update(message, 0, message.size)
            return verifier.verifySignature(signature)
        }
    }
}

// Hex utilities
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
