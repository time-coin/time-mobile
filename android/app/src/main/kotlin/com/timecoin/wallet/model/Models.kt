package com.timecoin.wallet.model

import com.timecoin.wallet.crypto.NetworkType
import kotlinx.serialization.Serializable

/** Unspent Transaction Output */
@Serializable
data class Utxo(
    val txid: String,
    val vout: Int,
    val amount: Long,
    val address: String,
    val confirmations: Int = 0,
    val spendable: Boolean = true,
)

/** Balance summary from masternode */
@Serializable
data class Balance(
    val confirmed: Long = 0,
    val pending: Long = 0,
    val total: Long = 0,
)

/** Transaction record for history display */
@Serializable
data class TransactionRecord(
    val txid: String,
    val vout: Int = 0,
    val isSend: Boolean,
    val address: String,
    val amount: Long,
    val fee: Long = 0,
    val timestamp: Long,
    val status: TransactionStatus = TransactionStatus.Pending,
    val isFee: Boolean = false,
    val isConsolidate: Boolean = false,
    val blockHash: String = "",
    val blockHeight: Long = 0,
    val confirmations: Long = 0,
) {
    /** Unique key for deduplication and LazyColumn. */
    val uniqueKey: String get() = "$txid:$isSend:$isFee:$vout"
}

@Serializable
enum class TransactionStatus {
    Pending,
    Approved,
    Declined,
}

/** Masternode health status */
@Serializable
data class HealthStatus(
    val status: String,
    val version: String,
    val blockHeight: Long,
    val peerCount: Int,
)

/** Instant finality status for a transaction */
@Serializable
data class FinalityStatus(
    val txid: String,
    val finalized: Boolean,
    val confirmations: Int,
)

/** Peer info from masternode — mirrors desktop wallet's PeerInfo struct */
@Serializable
data class PeerInfo(
    val endpoint: String,
    val isActive: Boolean = false,
    val isHealthy: Boolean = false,
    val wsAvailable: Boolean = false,
    val pingMs: Long? = null,
    val blockHeight: Long? = null,
    val version: String? = null,
)

/** Individual transaction output (from gettransaction vout array) */
data class TxOutputInfo(
    val value: Long,
    val index: Int,
    val address: String,
)

/** Contact / address book entry */
@Serializable
data class Contact(
    val address: String,
    val label: String = "",
    val name: String = "",
    val isOwned: Boolean = false,
    val derivationIndex: Int? = null,
    val createdAt: Long = System.currentTimeMillis() / 1000,
)

/**
 * Fee schedule — tiered fee calculation matching the desktop wallet.
 *
 * Default tiers:
 *   < 100 TIME  → 1%
 *   < 1k TIME   → 0.5%
 *   < 10k TIME  → 0.25%
 *   ≥ 10k TIME  → 0.1%
 *
 * Minimum fee: 0.01 TIME (1_000_000 satoshis)
 */
data class FeeSchedule(
    val tiers: List<FeeTier> = DEFAULT_TIERS,
    val minFee: Long = MIN_TX_FEE,
) {
    fun calculateFee(sendAmount: Long): Long {
        val rateBps = tiers.firstOrNull { sendAmount < it.upTo }?.rateBps ?: 10L
        val proportional = sendAmount * rateBps / 10_000
        return maxOf(proportional, minFee)
    }

    companion object {
        const val SATOSHIS_PER_TIME: Long = 100_000_000
        const val MIN_TX_FEE: Long = 1_000_000 // 0.01 TIME

        val DEFAULT_TIERS = listOf(
            FeeTier(upTo = 100 * SATOSHIS_PER_TIME, rateBps = 100),   // < 100 TIME  → 1%
            FeeTier(upTo = 1_000 * SATOSHIS_PER_TIME, rateBps = 50),  // < 1k TIME   → 0.5%
            FeeTier(upTo = 10_000 * SATOSHIS_PER_TIME, rateBps = 25), // < 10k TIME  → 0.25%
            FeeTier(upTo = Long.MAX_VALUE, rateBps = 10),             // ≥ 10k TIME  → 0.1%
        )
    }
}

data class FeeTier(
    val upTo: Long,
    val rateBps: Long,
)
