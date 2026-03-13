package com.timecoin.wallet.db

import androidx.room.*

/** Contact / address book entry stored in Room. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val address: String,
    val label: String = "",
    val name: String = "",
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
)

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
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE address LIKE '%' || :query || '%' OR txid LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun search(query: String): List<TransactionEntity>

    @Upsert
    suspend fun upsert(tx: TransactionEntity)

    @Upsert
    suspend fun upsertAll(txs: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
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
    entities = [ContactEntity::class, TransactionEntity::class, SettingEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters()
abstract class WalletDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun transactionDao(): TransactionDao
    abstract fun settingDao(): SettingDao
}
