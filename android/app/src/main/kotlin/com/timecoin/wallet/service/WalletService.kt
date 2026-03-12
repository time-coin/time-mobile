package com.timecoin.wallet.service

import android.content.Context
import android.util.Log
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

    private val _utxoSynced = MutableStateFlow(false)
    val utxoSynced: StateFlow<Boolean> = _utxoSynced

    private val _health = MutableStateFlow<HealthStatus?>(null)
    val health: StateFlow<HealthStatus?> = _health

    private val _wsConnected = MutableStateFlow(false)
    val wsConnected: StateFlow<Boolean> = _wsConnected

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers

    private val _connectedPeer = MutableStateFlow<String?>(null)
    val connectedPeer: StateFlow<String?> = _connectedPeer

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _scannedAddress = MutableStateFlow<String?>(null)
    val scannedAddress: StateFlow<String?> = _scannedAddress

    fun setScannedAddress(address: String) {
        _scannedAddress.value = address
    }

    fun clearScannedAddress() {
        _scannedAddress.value = null
    }

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _decimalPlaces = MutableStateFlow(2)
    val decimalPlaces: StateFlow<Int> = _decimalPlaces

    // ── Internal state ──
    private var wallet: WalletManager? = null
    private var masternodeClient: MasternodeClient? = null
    private var wsClient: WsNotificationClient? = null
    private var currentPassword: String? = null
    private var pollJob: Job? = null
    private var pendingMnemonic: String? = null

    private val walletDir get() = context.filesDir

    // ── Wallet lifecycle ──

    init {
        scope.launch {
            val saved = settingDao.get("decimal_places")
            if (saved != null) _decimalPlaces.value = saved.toIntOrNull() ?: 2
        }
    }

    fun setDecimalPlaces(places: Int) {
        _decimalPlaces.value = places
        scope.launch {
            settingDao.set(com.timecoin.wallet.db.SettingEntity("decimal_places", places.toString()))
        }
    }

    fun checkExistingWallet() {
        // Migrate legacy flat testnet file to subdirectory
        WalletManager.migrateIfNeeded(walletDir)

        val mainExists = WalletManager.exists(walletDir, NetworkType.Mainnet)
        val testExists = WalletManager.exists(walletDir, NetworkType.Testnet)
        if (mainExists || testExists) {
            val network = if (mainExists) NetworkType.Mainnet else NetworkType.Testnet
            val encrypted = WalletManager.isEncrypted(walletDir, network)
            if (encrypted) {
                _screen.value = Screen.PinUnlock
            } else {
                loadWallet(network, null)
            }
        }
    }

    fun selectNetwork(isTestnet: Boolean) {
        _isTestnet.value = isTestnet
        _screen.value = Screen.MnemonicSetup
    }

    /** Store mnemonic temporarily and navigate to PIN setup. */
    fun setPendingMnemonic(mnemonic: String) {
        pendingMnemonic = mnemonic
        _screen.value = Screen.PinSetup
    }

    /** Create wallet using the pending mnemonic and a 4-digit PIN. */
    fun createWalletWithPin(pin: String) {
        val mnemonic = pendingMnemonic ?: return
        createWallet(mnemonic, pin)
        pendingMnemonic = null
    }

    fun createWallet(mnemonic: String, password: String?) {
        scope.launch {
            _loading.value = true
            try {
                val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
                wallet = WalletManager.create(mnemonic, network)
                currentPassword = password
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
                currentPassword = password
                _isTestnet.value = network == NetworkType.Testnet
                _walletLoaded.value = true
                _addresses.value = wallet!!.getAddresses()
                _contacts.value = contactDao.getAll()
                _screen.value = Screen.Overview
                connectToNetwork()
            } catch (e: Exception) {
                _error.value = "Failed to load wallet: ${e.message}"
                if (password != null) {
                    _screen.value = Screen.PinUnlock
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
                Log.d("WalletService", "Connecting to network, isTestnet=$isTestnet")
                val peers = PeerDiscovery.fetchPeers(isTestnet, walletDir)
                Log.d("WalletService", "Discovered ${peers.size} peers: $peers")
                _peers.value = peers
                if (peers.isEmpty()) {
                    _error.value = "No masternodes found"
                    return@launch
                }

                // Try peers until one responds (try HTTPS first, then HTTP fallback)
                for (peer in peers) {
                    // Derive both HTTPS and HTTP URLs for the peer
                    val peerUrls = if (peer.startsWith("https://")) {
                        listOf(peer, peer.replaceFirst("https://", "http://"))
                    } else if (peer.startsWith("http://")) {
                        listOf(peer.replaceFirst("http://", "https://"), peer)
                    } else {
                        listOf("https://$peer", "http://$peer")
                    }

                    for (url in peerUrls) {
                        try {
                            Log.d("WalletService", "Trying peer: $url")
                            val client = MasternodeClient(url)
                            val h = client.healthCheck()
                            masternodeClient = client
                            _health.value = h
                            _connectedPeer.value = url
                            Log.d("WalletService", "Connected to $url, block=${h.blockHeight}")
                            break
                        } catch (e: Exception) {
                            Log.w("WalletService", "Peer $url failed: ${e.message}")
                            continue
                        }
                    }
                    if (masternodeClient != null) break
                }

                if (masternodeClient == null) {
                    Log.e("WalletService", "Could not connect to any masternode")
                    _error.value = "Could not connect to any masternode"
                    return@launch
                }

                // Initial data fetch
                refreshBalance()
                refreshTransactions()
                refreshUtxos()

                // Address discovery: scan forward for addresses with balances
                discoverAddresses()

                // Start WebSocket
                startWebSocket()

                // Start polling
                startPolling()
            } catch (e: Exception) {
                Log.e("WalletService", "Network error", e)
                _error.value = "Network error: ${e.message}"
            }
        }
    }

    private fun startWebSocket() {
        val w = wallet ?: return
        val client = masternodeClient ?: return

        // WS port = RPC port + 1 (e.g. 24101 → 24102), use wss:// (TLS enabled by default)
        val wsUrl = deriveWsUrl(client.rpcEndpoint)
        Log.d("WalletService", "Starting WebSocket at $wsUrl")

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
                _utxoSynced.value = true
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
            _contacts.value = contactDao.getAll()
            // Persist updated nextAddressIndex
            w.save(walletDir, currentPassword)
        }
        return addr
    }

    /**
     * Address discovery: starting from the current address count, generate new
     * addresses and check each for balance. Keep going until we find a gap of
     * [GAP_LIMIT] consecutive addresses with no history. This ensures we pick
     * up all addresses that received funds (e.g. from the desktop wallet).
     */
    private suspend fun discoverAddresses() {
        val w = wallet ?: return
        val client = masternodeClient ?: return
        val gapLimit = 5
        var consecutiveEmpty = 0

        Log.d("WalletService", "Starting address discovery from index ${w.getAddresses().size}")

        while (consecutiveEmpty < gapLimit) {
            val addr = w.generateAddress()
            try {
                val bal = client.getBalance(addr)
                val txs = client.getTransactions(addr, 1)
                if (bal.total > 0 || txs.isNotEmpty()) {
                    Log.d("WalletService", "Discovered active address: $addr")
                    consecutiveEmpty = 0

                    // Save as owned contact
                    val existing = contactDao.getByAddress(addr)
                    if (existing == null) {
                        contactDao.upsert(ContactEntity(
                            address = addr,
                            label = "Address ${w.getAddresses().size}",
                            isOwned = true,
                            derivationIndex = w.getAddresses().size - 1,
                        ))
                    }
                } else {
                    consecutiveEmpty++
                }
            } catch (e: Exception) {
                Log.w("WalletService", "Discovery check failed for $addr: ${e.message}")
                consecutiveEmpty++
            }
        }

        // Remove the empty gap addresses we just discovered
        // by reloading from the saved state before the gap
        val activeCount = w.getAddresses().size - gapLimit
        if (activeCount > 0) {
            Log.d("WalletService", "Discovery complete. Found addresses up to index ${activeCount - 1}")
        }

        // Update UI and save
        _addresses.value = w.getAddresses()
        w.save(walletDir, currentPassword)
        _contacts.value = contactDao.getAll()

        // Refresh balances with all discovered addresses
        refreshBalance()
        refreshTransactions()
        refreshUtxos()
    }

    fun saveContact(name: String, address: String) {
        scope.launch {
            contactDao.upsert(ContactEntity(address = address, name = name))
            _contacts.value = contactDao.getAll()
        }
    }

    fun updateAddressLabel(address: String, label: String) {
        scope.launch {
            val existing = contactDao.getByAddress(address)
            if (existing != null) {
                contactDao.upsert(existing.copy(label = label, updatedAt = System.currentTimeMillis() / 1000))
            } else {
                contactDao.upsert(ContactEntity(address = address, label = label, isOwned = true))
            }
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

    /** Return the mnemonic recovery phrase. Wallet must be loaded. */
    fun getMnemonic(): String? = wallet?.getMnemonic()

    /** Get the wallet file for export/sharing. */
    fun getWalletFile(): java.io.File? {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
        return WalletManager.walletFile(walletDir, network)
    }

    /** Delete current wallet, auto-backup first, reset all state. */
    fun deleteWallet() {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet

        // Disconnect
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        pollJob = null

        // Delete (with auto-backup)
        WalletManager.deleteWallet(walletDir, network)

        // Reset state
        wallet = null
        currentPassword = null
        _walletLoaded.value = false
        _connectedPeer.value = null
        _wsConnected.value = false
        _health.value = null
        _balance.value = Balance()
        _transactions.value = emptyList()
        _utxos.value = emptyList()
        _addresses.value = emptyList()
        _utxoSynced.value = false
        _screen.value = Screen.Welcome
    }

    fun reconnect() {
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        pollJob = null
        _connectedPeer.value = null
        _wsConnected.value = false
        _health.value = null
        connectToNetwork()
    }

    /**
     * Switch between testnet and mainnet.
     * Disconnects, resets wallet state, and loads the other network's wallet.
     */
    fun switchNetwork(toTestnet: Boolean) {
        if (toTestnet == _isTestnet.value) return
        val targetNetwork = if (toTestnet) NetworkType.Testnet else NetworkType.Mainnet

        // Check if wallet exists for target network
        if (!WalletManager.exists(walletDir, targetNetwork)) {
            _error.value = "No wallet found for ${if (toTestnet) "testnet" else "mainnet"}. " +
                "Create one from the welcome screen."
            return
        }

        // Disconnect current network
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        pollJob = null

        // Reset state
        _connectedPeer.value = null
        _wsConnected.value = false
        _health.value = null
        _balance.value = Balance()
        _transactions.value = emptyList()
        _utxos.value = emptyList()
        _addresses.value = emptyList()
        _utxoSynced.value = false
        wallet = null

        // Load wallet for target network (encrypted → need PIN)
        val encrypted = WalletManager.isEncrypted(walletDir, targetNetwork)
        if (encrypted) {
            _isTestnet.value = toTestnet
            _screen.value = Screen.PinUnlock
        } else {
            loadWallet(targetNetwork, null)
        }
    }

    /**
     * Derive WebSocket URL from RPC endpoint.
     * WS port = RPC port + 1: https://host:24101 → wss://host:24102
     */
    private fun deriveWsUrl(endpoint: String): String {
        val base = endpoint
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val lastColon = base.lastIndexOf(':')
        if (lastColon > 0) {
            val port = base.substring(lastColon + 1).toIntOrNull()
            if (port != null) {
                return "${base.substring(0, lastColon + 1)}${port + 1}"
            }
        }
        return base
    }

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
    PinSetup,
    PinUnlock,
    PasswordUnlock,
    Overview,
    Send,
    QrScanner,
    Receive,
    Transactions,
    Connections,
    Settings,
}
