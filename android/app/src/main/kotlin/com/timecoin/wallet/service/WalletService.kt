package com.timecoin.wallet.service

import android.content.Context
import com.timecoin.wallet.crypto.*
import com.timecoin.wallet.db.*
import com.timecoin.wallet.model.*
import com.timecoin.wallet.network.*
import com.timecoin.wallet.wallet.WalletManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central wallet service — connects the wallet, masternode client, WebSocket,
 * peer discovery, and database. The UI observes state flows.
 */
@Singleton
class WalletService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val transactionDao: TransactionDao,
    private val settingDao: SettingDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Observable state ──
    private val _screen = MutableStateFlow(Screen.Welcome)
    val screen: StateFlow<Screen> = _screen

    private val _walletLoaded = MutableStateFlow(false)
    val walletLoaded: StateFlow<Boolean> = _walletLoaded

    private val _isTestnet = MutableStateFlow(false)
    val isTestnet: StateFlow<Boolean> = _isTestnet

    private val _balance = MutableStateFlow(Balance())
    val balance: StateFlow<Balance> = _balance

    private val _transactions = MutableStateFlow<List<TransactionRecord>>(emptyList())
    val transactions: StateFlow<List<TransactionRecord>> = _transactions

    private val _utxos = MutableStateFlow<List<Utxo>>(emptyList())
    val utxos: StateFlow<List<Utxo>> = _utxos

    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    val addresses: StateFlow<List<String>> = _addresses

    private val _contacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val contacts: StateFlow<List<ContactEntity>> = _contacts

    private val _health = MutableStateFlow<HealthStatus?>(null)
    val health: StateFlow<HealthStatus?> = _health

    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // ── Internal state ──
    private var wallet: WalletManager? = null
    private var masternodeClient: MasternodeClient? = null
    private var wsClient: WsNotificationClient? = null
    private var pollJob: Job? = null

    private val walletDir get() = context.filesDir

    // ── Wallet lifecycle ──

    fun checkExistingWallet() {
        val mainExists = WalletManager.exists(walletDir, NetworkType.Mainnet)
        val testExists = WalletManager.exists(walletDir, NetworkType.Testnet)
        if (mainExists || testExists) {
            val network = if (mainExists) NetworkType.Mainnet else NetworkType.Testnet
            val encrypted = WalletManager.isEncrypted(walletDir, network)
            if (encrypted) {
                _screen.value = Screen.PasswordUnlock
            } else {
                loadWallet(network, null)
            }
        }
    }

    fun selectNetwork(isTestnet: Boolean) {
        _isTestnet.value = isTestnet
        _screen.value = Screen.MnemonicSetup
    }

    fun createWallet(mnemonic: String, password: String?) {
        scope.launch {
            _loading.value = true
            try {
                val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
                wallet = WalletManager.create(mnemonic, network)
                wallet!!.save(walletDir, password)

                // Save primary address as owned contact
                val addr = wallet!!.primaryAddress
                contactDao.upsert(ContactEntity(
                    address = addr, label = "Primary", isOwned = true, derivationIndex = 0,
                ))

                _walletLoaded.value = true
                _addresses.value = wallet!!.getAddresses()
                _screen.value = Screen.Overview
                connectToNetwork()
            } catch (e: Exception) {
                _error.value = "Failed to create wallet: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadWallet(network: NetworkType, password: String?) {
        scope.launch {
            _loading.value = true
            try {
                wallet = WalletManager.load(walletDir, network, password)
                _isTestnet.value = network == NetworkType.Testnet
                _walletLoaded.value = true
                _addresses.value = wallet!!.getAddresses()
                _contacts.value = contactDao.getAll()
                _screen.value = Screen.Overview
                connectToNetwork()
            } catch (e: Exception) {
                _error.value = "Failed to load wallet: ${e.message}"
                if (password != null) {
                    _screen.value = Screen.PasswordUnlock
                }
            } finally {
                _loading.value = false
            }
        }
    }

    // ── Network ──

    private fun connectToNetwork() {
        scope.launch {
            try {
                val isTestnet = _isTestnet.value
                val peers = PeerDiscovery.fetchPeers(isTestnet, walletDir)
                if (peers.isEmpty()) {
                    _error.value = "No masternodes found"
                    return@launch
                }

                // Try peers until one responds
                for (peer in peers) {
                    try {
                        val client = MasternodeClient(peer)
                        client.healthCheck() // test connectivity
                        masternodeClient = client
                        _health.value = client.healthCheck()
                        break
                    } catch (_: Exception) { continue }
                }

                if (masternodeClient == null) {
                    _error.value = "Could not connect to any masternode"
                    return@launch
                }

                // Initial data fetch
                refreshBalance()
                refreshTransactions()
                refreshUtxos()

                // Start WebSocket
                startWebSocket()

                // Start polling
                startPolling()
            } catch (e: Exception) {
                _error.value = "Network error: ${e.message}"
            }
        }
    }

    private fun startWebSocket() {
        val w = wallet ?: return
        val client = masternodeClient ?: return
        val wsUrl = client.rpcEndpoint
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/ws"

        wsClient?.stop()
        wsClient = WsNotificationClient(wsUrl, w.getAddresses(), scope)
        wsClient!!.start()

        scope.launch {
            wsClient!!.events.collect { event ->
                when (event) {
                    is WsEvent.Connected -> _wsConnected.value = true
                    is WsEvent.Disconnected -> _wsConnected.value = false
                    is WsEvent.TransactionReceived -> {
                        refreshBalance()
                        refreshTransactions()
                        refreshUtxos()
                    }
                    is WsEvent.UtxoFinalized -> refreshUtxos()
                    is WsEvent.TransactionRejected -> {
                        _error.value = "Transaction rejected: ${event.reason}"
                    }
                }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(30_000) // poll every 30s
                try {
                    refreshBalance()
                    masternodeClient?.let { _health.value = it.healthCheck() }
                } catch (_: Exception) { }
            }
        }
    }

    // ── Actions ──

    fun refreshBalance() {
        scope.launch {
            try {
                val w = wallet ?: return@launch
                val client = masternodeClient ?: return@launch
                val bal = client.getBalances(w.getAddresses())
                _balance.value = bal
            } catch (_: Exception) { }
        }
    }

    fun refreshTransactions() {
        scope.launch {
            try {
                val w = wallet ?: return@launch
                val client = masternodeClient ?: return@launch
                val txs = client.getTransactionsMulti(w.getAddresses(), 100)
                _transactions.value = txs

                // Cache to DB
                transactionDao.upsertAll(txs.map {
                    TransactionEntity(
                        txid = it.txid, isSend = it.isSend, address = it.address,
                        amount = it.amount, fee = it.fee, timestamp = it.timestamp,
                        status = it.status.name.lowercase(), blockHeight = it.blockHeight,
                        confirmations = it.confirmations,
                    )
                })
            } catch (_: Exception) { }
        }
    }

    fun refreshUtxos() {
        scope.launch {
            try {
                val w = wallet ?: return@launch
                val client = masternodeClient ?: return@launch
                val utxos = client.getUtxos(w.getAddresses())
                w.setUtxos(utxos)
                _utxos.value = utxos
                _balance.value = _balance.value.copy(
                    confirmed = utxos.filter { it.spendable }.sumOf { it.amount }
                )
            } catch (_: Exception) { }
        }
    }

    fun sendTransaction(toAddress: String, amount: Long) {
        scope.launch {
            _loading.value = true
            try {
                val w = wallet ?: throw Exception("Wallet not loaded")
                val client = masternodeClient ?: throw Exception("Not connected")

                val tx = w.createTransaction(toAddress, amount)
                val txHex = tx.txid() // For now, send txid; real impl sends serialized bytes
                // TODO: Implement proper bincode serialization for broadcast
                val txid = client.broadcastTransaction(txHex)

                _success.value = "Transaction sent! TxID: ${txid.take(16)}..."
                w.save(walletDir, null) // persist UTXO changes

                refreshBalance()
                refreshTransactions()
                refreshUtxos()
            } catch (e: Exception) {
                _error.value = "Send failed: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun generateAddress(): String? {
        val w = wallet ?: return null
        val addr = w.generateAddress()
        _addresses.value = w.getAddresses()

        scope.launch {
            contactDao.upsert(ContactEntity(
                address = addr,
                label = "Address ${w.getAddresses().size}",
                isOwned = true,
                derivationIndex = w.getAddresses().size - 1,
            ))
        }
        return addr
    }

    fun saveContact(name: String, address: String) {
        scope.launch {
            contactDao.upsert(ContactEntity(address = address, name = name))
            _contacts.value = contactDao.getAll()
        }
    }

    fun deleteContact(address: String) {
        scope.launch {
            contactDao.deleteByAddress(address)
            _contacts.value = contactDao.getAll()
        }
    }

    fun navigateTo(screen: Screen) { _screen.value = screen }
    fun clearError() { _error.value = null }
    fun clearSuccess() { _success.value = null }

    fun shutdown() {
        wsClient?.stop()
        masternodeClient?.close()
        pollJob?.cancel()
        scope.cancel()
    }
}

enum class Screen {
    Welcome,
    NetworkSelect,
    MnemonicSetup,
    MnemonicConfirm,
    PasswordUnlock,
    Overview,
    Send,
    Receive,
    Transactions,
    Settings,
}
