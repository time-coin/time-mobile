package com.timecoin.wallet.model

import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.crypto.Keypair
import com.timecoin.wallet.crypto.UByteArraySerializer
import com.timecoin.wallet.crypto.toHexString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * Transaction types — matches the desktop wallet's transaction.rs.
 *
 * Serialization uses JSON for the txid hash (same as the desktop wallet)
 * and a custom binary format for broadcast (matching bincode layout).
 */

/** Outpoint referencing a previous transaction output */
@Serializable
data class OutPoint(
    @kotlinx.serialization.Serializable(with = UByteArraySerializer::class)
    val txid: ByteArray,
    val vout: Int,
) {
    init { require(txid.size == 32) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutPoint) return false
        return txid.contentEquals(other.txid) && vout == other.vout
    }

    override fun hashCode(): Int = 31 * txid.contentHashCode() + vout
}

/** Transaction input */
@Serializable
data class TxInput(
    @SerialName("previous_output") val previousOutput: OutPoint,
    @SerialName("script_sig")
    @kotlinx.serialization.Serializable(with = UByteArraySerializer::class)
    var scriptSig: ByteArray = ByteArray(0),
    val sequence: Long = 0xFFFFFFFFL,
) {
    companion object {
        fun new(prevTx: ByteArray, prevIndex: Int) = TxInput(
            previousOutput = OutPoint(txid = prevTx, vout = prevIndex),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TxInput) return false
        return previousOutput == other.previousOutput &&
            scriptSig.contentEquals(other.scriptSig) &&
            sequence == other.sequence
    }

    override fun hashCode(): Int {
        var result = previousOutput.hashCode()
        result = 31 * result + scriptSig.contentHashCode()
        result = 31 * result + sequence.hashCode()
        return result
    }
}

/** Transaction output */
@Serializable
data class TxOutput(
    val value: Long,
    @SerialName("script_pubkey")
    @kotlinx.serialization.Serializable(with = UByteArraySerializer::class)
    val scriptPubkey: ByteArray,
) {
    companion object {
        fun new(amount: Long, address: Address) = TxOutput(
            value = amount,
            scriptPubkey = address.toString().toByteArray(),
        )
    }

    fun addressString(): String = String(scriptPubkey)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TxOutput) return false
        return value == other.value && scriptPubkey.contentEquals(other.scriptPubkey)
    }

    override fun hashCode(): Int = 31 * value.hashCode() + scriptPubkey.contentHashCode()
}

/**
 * Transaction — matches the desktop wallet's Transaction struct.
 *
 * The txid is computed as SHA-256 of the JSON representation, matching
 * the masternode's `txid()` method exactly.
 */
@Serializable
data class Transaction(
    val version: Int = 1,
    val inputs: MutableList<TxInput> = mutableListOf(),
    val outputs: MutableList<TxOutput> = mutableListOf(),
    @SerialName("lock_time") val lockTime: Int = 0,
    val timestamp: Long = System.currentTimeMillis() / 1000,
) {
    fun addInput(input: TxInput) { inputs.add(input) }

    fun addOutput(output: TxOutput) {
        require(output.value > 0) { "Output value must be > 0" }
        outputs.add(output)
    }

    /** SHA-256 of JSON serialization (matches masternode txid). */
    fun hash(): ByteArray {
        val json = Json { encodeDefaults = true }.encodeToString(serializer(), this)
        val sha = MessageDigest.getInstance("SHA-256")
        return sha.digest(json.toByteArray())
    }

    /** Transaction ID as hex string. */
    fun txid(): String = hash().toHexString()

    /** Total output value in satoshis. */
    fun totalOutput(): Long = outputs.sumOf { it.value }

    /** Create the signature message for a specific input (matches desktop). */
    private fun createSignatureMessage(inputIdx: Int): ByteArray {
        // Create a copy with all scriptSigs cleared
        val signingTx = this.copy(
            inputs = inputs.map { it.copy(scriptSig = ByteArray(0)) }.toMutableList()
        )
        val txHash = signingTx.hash()

        // message = tx_hash + input_index_le + sha256(outputs)
        val indexBytes = ByteArray(4)
        indexBytes[0] = (inputIdx and 0xFF).toByte()
        indexBytes[1] = ((inputIdx shr 8) and 0xFF).toByte()
        indexBytes[2] = ((inputIdx shr 16) and 0xFF).toByte()
        indexBytes[3] = ((inputIdx shr 24) and 0xFF).toByte()

        val outputsJson = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(TxOutput.serializer()), outputs.toList())
        val sha = MessageDigest.getInstance("SHA-256")
        val outputsHash = sha.digest(outputsJson.toByteArray())

        return txHash + indexBytes + outputsHash
    }

    /** Sign a specific input with the given keypair. */
    fun sign(keypair: Keypair, inputIndex: Int) {
        require(inputIndex in inputs.indices) { "Input index out of range" }
        val message = createSignatureMessage(inputIndex)
        val signature = keypair.sign(message)
        val pubkeyBytes = keypair.publicKeyBytes()

        // script_sig = [32-byte pubkey || 64-byte signature]
        val scriptSig = ByteArray(96)
        System.arraycopy(pubkeyBytes, 0, scriptSig, 0, 32)
        System.arraycopy(signature, 0, scriptSig, 32, 64)
        inputs[inputIndex] = inputs[inputIndex].copy(scriptSig = scriptSig)
    }

    /** Sign all inputs with the same keypair. */
    fun signAll(keypair: Keypair) {
        for (i in inputs.indices) sign(keypair, i)
    }

    /** Verify a single input's signature. */
    fun verifyInput(inputIndex: Int): Boolean {
        require(inputIndex in inputs.indices) { "Input index out of range" }
        val input = inputs[inputIndex]
        if (input.scriptSig.size != 96) return false

        val pubkeyBytes = input.scriptSig.copyOfRange(0, 32)
        val signature = input.scriptSig.copyOfRange(32, 96)
        val message = createSignatureMessage(inputIndex)

        return Keypair.verifyWithPublicKey(pubkeyBytes, message, signature)
    }
}
