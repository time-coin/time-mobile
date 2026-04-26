package com.timecoin.wallet.db

import androidx.room.*
import com.timecoin.wallet.model.PaymentRequest
import com.timecoin.wallet.model.PaymentRequestStatus

/** Contact / address book entry stored in Room. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val address: String,
    val label: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val isOwned: Boolean = false,
    val derivationIndex: Int? = null,
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val updatedAt: Long = System.currentTimeMillis() / 1000,
)

/** Cached transaction record. */
@Entity(
    tableName = "transactions",
    primaryKeys = ["txid", "isSend", "isFee", "vout"],
)
data class TransactionEntity(
    val txid: String,
    val vout: Int = 0,
    val isSend: Boolean,
    val isFee: Boolean = false,
    val address: String,
    val amount: Long,
    val fee: Long = 0,
    val timestamp: Long,
    val status: String = "pending",
    val blockHeight: Long = 0,
    val confirmations: Long = 0,
    val memo: String = "",
) {
    val uniqueKey: String get() = "$txid:$isSend:$isFee:$vout"

    fun toTransactionRecord() = com.timecoin.wallet.model.TransactionRecord(
        txid = txid, vout = vout, isSend = isSend, isFee = isFee,
        address = address, amount = amount, fee = fee, timestamp = timestamp,
        status = if (status == "approved") com.timecoin.wallet.model.TransactionStatus.Approved
                 else com.timecoin.wallet.model.TransactionStatus.Pending,
        blockHeight = blockHeight, confirmations = confirmations, memo = memo,
    )
}

/** Key-value settings store. */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE isOwned = 1 ORDER BY derivationIndex ASC")
    suspend fun getOwnedAddresses(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE isOwned = 0 ORDER BY name ASC")
    suspend fun getExternalContacts(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE address = :address")
    suspend fun getByAddress(address: String): ContactEntity?

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Delete
    suspend fun delete(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE address = :address")
    suspend fun deleteByAddress(address: String)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, CASE WHEN isFee = 1 THEN 1 WHEN isSend = 1 THEN 2 ELSE 0 END ASC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, CASE WHEN isFee = 1 THEN 1 WHEN isSend = 1 THEN 2 ELSE 0 END ASC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT * FROM transactions WHERE address LIKE '%' || :query || '%' OR txid LIKE '%' || :query || '%' ORDER BY timestamp DESC, CASE WHEN isFee = 1 THEN 1 WHEN isSend = 1 THEN 2 ELSE 0 END ASC")
    suspend fun search(query: String): List<TransactionEntity>

    @Upsert
    suspend fun upsert(tx: TransactionEntity)

    @Upsert
    suspend fun upsertAll(txs: List<TransactionEntity>)

    @Query("UPDATE transactions SET memo = :memo WHERE txid = :txid AND vout = :vout AND isSend = :isSend AND isFee = :isFee")
    suspend fun updateMemo(txid: String, vout: Int, isSend: Boolean, isFee: Boolean, memo: String)

    @Query("SELECT DISTINCT txid FROM transactions WHERE status = 'pending'")
    suspend fun getPendingTxids(): List<String>

    @Query("UPDATE transactions SET status = :status WHERE txid = :txid")
    suspend fun updateStatusByTxid(txid: String, status: String)

    @Query("DELETE FROM transactions WHERE txid = :txid")
    suspend fun deleteByTxid(txid: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

/** Payment request stored locally (both outgoing and incoming). */
@Entity(tableName = "payment_requests")
data class PaymentRequestEntity(
    @PrimaryKey val id: String,
    val requesterAddress: String,
    val payerAddress: String,
    val amountSats: Long,
    val memo: String = "",
    val requesterName: String = "",
    val status: String = "pending",
    val isOutgoing: Boolean = true,
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val updatedAt: Long = System.currentTimeMillis() / 1000,
    val paidTxid: String = "",
    /** True once the masternode has confirmed receipt of this outgoing request. */
    val delivered: Boolean = false,
    /** True once the payer has opened and viewed this request. */
    val viewed: Boolean = false,
    /** Unix timestamp when this request expires (default: 7 days after creation). */
    val expiresAt: Long = 0L,
) {
    fun toPaymentRequest() = PaymentRequest(
        id = id,
        requesterAddress = requesterAddress,
        payerAddress = payerAddress,
        amountSats = amountSats,
        memo = memo,
        requesterName = requesterName,
        status = when (status) {
            "accepted" -> PaymentRequestStatus.Accepted
            "declined" -> PaymentRequestStatus.Declined
            "cancelled" -> PaymentRequestStatus.Cancelled
            "paid" -> PaymentRequestStatus.Paid
            else -> PaymentRequestStatus.Pending
        },
        isOutgoing = isOutgoing,
        createdAt = createdAt,
        updatedAt = updatedAt,
        paidTxid = paidTxid,
        delivered = delivered,
        viewed = viewed,
        expiresAt = expiresAt,
    )
}

@Dao
interface PaymentRequestDao {
    @Query("SELECT * FROM payment_requests ORDER BY createdAt DESC")
    suspend fun getAll(): List<PaymentRequestEntity>

    @Query("SELECT * FROM payment_requests WHERE id = :id")
    suspend fun getById(id: String): PaymentRequestEntity?

    @Upsert
    suspend fun upsert(req: PaymentRequestEntity)

    @Query("UPDATE payment_requests SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("UPDATE payment_requests SET status = 'paid', paidTxid = :txid, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markPaid(id: String, txid: String, updatedAt: Long)

    @Query("DELETE FROM payment_requests WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE payment_requests SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: String)

    @Query("UPDATE payment_requests SET viewed = 1 WHERE id = :id")
    suspend fun markViewed(id: String)

    @Query("SELECT * FROM payment_requests WHERE status = 'pending' AND expiresAt > 0 AND expiresAt <= :now")
    suspend fun getExpired(now: Long): List<PaymentRequestEntity>

    @Query("SELECT * FROM payment_requests WHERE isOutgoing = 1 AND delivered = 0 AND status = 'pending'")
    suspend fun getUndeliveredOutgoing(): List<PaymentRequestEntity>

    /**
     * Count active outgoing requests to a given payer in the last [windowSecs] seconds.
     * Cancelled requests are excluded so corrections are always allowed.
     */
    @Query("""
        SELECT COUNT(*) FROM payment_requests
        WHERE isOutgoing = 1
          AND payerAddress = :address
          AND status NOT IN ('cancelled')
          AND createdAt > :since
    """)
    suspend fun countActiveOutgoingTo(address: String, since: Long): Int
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun set(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [ContactEntity::class, TransactionEntity::class, SettingEntity::class, PaymentRequestEntity::class],
    version = 8,
    exportSchema = false,
)
@TypeConverters()
abstract class WalletDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun transactionDao(): TransactionDao
    abstract fun settingDao(): SettingDao
    abstract fun paymentRequestDao(): PaymentRequestDao
}
