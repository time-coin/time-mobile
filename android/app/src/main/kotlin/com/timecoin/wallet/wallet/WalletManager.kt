package com.timecoin.wallet.wallet

import com.timecoin.wallet.crypto.*
import com.timecoin.wallet.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Wallet manager — holds keys in memory, derives HD addresses, creates
 * and signs transactions. Wallet file I/O uses encrypted JSON.
 *
 * Mirrors the desktop wallet's WalletManager + WalletDat.
 */
class WalletManager private constructor(
    private val mnemonic: String,
    val network: NetworkType,
    private var nextAddressIndex: Int = 1,
) {
    private val keypair: Keypair = MnemonicHelper.deriveKeypairBip44(mnemonic, "", 0, 0, 0)
    val primaryAddress: String = Address.fromPublicKey(keypair.publicKeyBytes(), network).toString()

    private val utxos = mutableListOf<Utxo>()
    var balance: Long = 0L
        private set

    /** All derived addresses (index 0 = primary). */
    private val addresses = mutableListOf(primaryAddress)

    fun getAddresses(): List<String> = addresses.toList()

    /** Generate a new HD address at the next index. */
    fun generateAddress(): String {
        val addr = MnemonicHelper.deriveAddress(mnemonic, "", 0, 0, nextAddressIndex, network)
        addresses.add(addr)
        nextAddressIndex++
        return addr
    }

    /** Derive the keypair for a specific address index. */
    fun deriveKeypair(index: Int): Keypair =
        MnemonicHelper.deriveKeypairBip44(mnemonic, "", 0, 0, index)

    // ── UTXO management ──

    fun setUtxos(newUtxos: List<Utxo>) {
        utxos.clear()
        utxos.addAll(newUtxos)
        balance = utxos.filter { it.spendable }.sumOf { it.amount }
    }

    fun getUtxos(): List<Utxo> = utxos.toList()

    // ── Transaction creation ──

    /**
     * Create and sign a transaction sending [amount] satoshis to [toAddress].
     * Uses the default fee schedule. Returns the signed transaction.
     */
    fun createTransaction(
        toAddress: String,
        amount: Long,
        feeSchedule: FeeSchedule = FeeSchedule(),
    ): Transaction {
        val fee = feeSchedule.calculateFee(amount)
        val totalNeeded = amount + fee

        // Select UTXOs (simple greedy: use all spendable until we have enough)
        val spendable = utxos.filter { it.spendable }.sortedByDescending { it.amount }
        var accumulated = 0L
        val selected = mutableListOf<Utxo>()
        for (utxo in spendable) {
            selected.add(utxo)
            accumulated += utxo.amount
            if (accumulated >= totalNeeded) break
        }

        require(accumulated >= totalNeeded) {
            "Insufficient funds: have ${accumulated.toTimeString()}, need ${totalNeeded.toTimeString()}"
        }

        val tx = Transaction()

        // Add inputs
        for (utxo in selected) {
            val txidBytes = utxo.txid.hexToByteArray()
            tx.addInput(TxInput.new(txidBytes, utxo.vout))
        }

        // Recipient output
        val recipientAddr = Address.fromString(toAddress)
        tx.addOutput(TxOutput.new(amount, recipientAddr))

        // Change output (back to ourselves)
        val change = accumulated - totalNeeded
        if (change > 0) {
            val changeAddr = Address.fromString(primaryAddress)
            tx.addOutput(TxOutput.new(change, changeAddr))
        }

        // Sign all inputs with the primary keypair
        tx.signAll(keypair)

        // Remove spent UTXOs
        for (utxo in selected) {
            utxos.removeAll { it.txid == utxo.txid && it.vout == utxo.vout }
        }
        balance = utxos.filter { it.spendable }.sumOf { it.amount }

        return tx
    }

    // ── Persistence ──

    /** Save encrypted wallet to the given directory. */
    fun save(dir: File, password: String?) {
        dir.mkdirs()
        val walletFile = File(dir, walletFilename(network))

        val data = WalletFileData(
            version = WALLET_VERSION,
            network = network.name,
            mnemonic = mnemonic,
            nextAddressIndex = nextAddressIndex,
        )
        val json = Json.encodeToString(data)

        if (password != null) {
            val encrypted = WalletEncryption.encrypt(json.toByteArray(), password)
            val envelope = EncryptedEnvelope(
                salt = encrypted.salt.toHexString(),
                nonce = encrypted.nonce.toHexString(),
                ciphertext = encrypted.ciphertext.toHexString(),
                version = encrypted.version,
            )
            walletFile.writeText(Json.encodeToString(envelope))
        } else {
            walletFile.writeText(json)
        }
    }

    companion object {
        private const val WALLET_VERSION = 3

        fun walletFilename(network: NetworkType): String =
            if (network == NetworkType.Testnet) "time-wallet-testnet.dat" else "time-wallet.dat"

        /** Create a new wallet from a mnemonic phrase. */
        fun create(mnemonic: String, network: NetworkType): WalletManager {
            require(MnemonicHelper.validate(mnemonic)) { "Invalid mnemonic phrase" }
            return WalletManager(mnemonic, network)
        }

        /** Load wallet from an encrypted file. */
        fun load(dir: File, network: NetworkType, password: String?): WalletManager {
            val walletFile = File(dir, walletFilename(network))
            require(walletFile.exists()) { "Wallet file not found: ${walletFile.path}" }

            val content = walletFile.readText()
            val json = if (password != null) {
                val envelope = Json.decodeFromString<EncryptedEnvelope>(content)
                val encrypted = WalletEncryption.EncryptedData(
                    salt = envelope.salt.hexToByteArray(),
                    nonce = envelope.nonce.hexToByteArray(),
                    ciphertext = envelope.ciphertext.hexToByteArray(),
                    version = envelope.version,
                )
                String(WalletEncryption.decrypt(encrypted, password))
            } else {
                content
            }

            val data = Json.decodeFromString<WalletFileData>(json)
            val net = NetworkType.valueOf(data.network)
            return WalletManager(data.mnemonic, net, data.nextAddressIndex)
        }

        /** Check if a wallet file exists. */
        fun exists(dir: File, network: NetworkType): Boolean =
            File(dir, walletFilename(network)).exists()

        /** Check if the wallet file is encrypted (contains JSON with "salt" field). */
        fun isEncrypted(dir: File, network: NetworkType): Boolean {
            val file = File(dir, walletFilename(network))
            if (!file.exists()) return false
            val content = file.readText()
            return content.contains("\"salt\"")
        }
    }
}

@Serializable
private data class WalletFileData(
    val version: Int,
    val network: String,
    val mnemonic: String,
    val nextAddressIndex: Int,
)

@Serializable
private data class EncryptedEnvelope(
    val salt: String,
    val nonce: String,
    val ciphertext: String,
    val version: Int,
)

/** Format satoshis as "X.XXXXXXXX TIME" for error messages. */
fun Long.toTimeString(): String {
    val whole = this / 100_000_000
    val frac = this % 100_000_000
    return "$whole.${"%08d".format(frac)} TIME"
}
