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

    companion object {
        private const val TAG = "WalletService"
    }

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

    private val _peerInfos = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peerInfos: StateFlow<List<PeerInfo>> = _peerInfos

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

    /** Contact info extracted from shared text (SMS, etc.) */
    data class SharedContact(
        val address: String,
        val phone: String = "",
        val rawText: String = "",
    )

    private val _sharedContact = MutableStateFlow<SharedContact?>(null)
    val sharedContact: StateFlow<SharedContact?> = _sharedContact

    fun setSharedContact(contact: SharedContact) {
        _sharedContact.value = contact
    }

    fun clearSharedContact() {
        _sharedContact.value = null
    }

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _decimalPlaces = MutableStateFlow(2)
    val decimalPlaces: StateFlow<Int> = _decimalPlaces

    private val _selectedTransaction = MutableStateFlow<TransactionRecord?>(null)
    val selectedTransaction: StateFlow<TransactionRecord?> = _selectedTransaction

    private val _reindexing = MutableStateFlow(false)
    val reindexing: StateFlow<Boolean> = _reindexing

    private val _backups = MutableStateFlow<List<java.io.File>>(emptyList())
    val backups: StateFlow<List<java.io.File>> = _backups

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
                Log.d(TAG, "loadWallet: loading network=$network")
                wallet = WalletManager.load(walletDir, network, password)
                currentPassword = password
                _isTestnet.value = network == NetworkType.Testnet
                _walletLoaded.value = true
                _addresses.value = wallet!!.getAddresses()
                Log.d(TAG, "loadWallet: ${_addresses.value.size} addresses loaded")
                _contacts.value = contactDao.getAll()
                _screen.value = Screen.Overview
                connectToNetwork()
            } catch (e: Exception) {
                Log.e(TAG, "loadWallet failed", e)
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
                Log.d(TAG, "Connecting to network, isTestnet=$isTestnet")

                // Load manual config
                val config = ConfigManager.load(walletDir)
                val manualEndpoints = ConfigManager.manualEndpoints(
                    config.copy(testnet = isTestnet)
                )
                val rpcCreds = ConfigManager.rpcCredentials(config)

                // Discover and rank all peers (parallel probe + gossip + consensus)
                val rankedPeers = PeerDiscovery.discoverAndRank(
                    isTestnet = isTestnet,
                    manualEndpoints = manualEndpoints,
                    credentials = rpcCreds,
                    cacheDir = walletDir,
                )

                _peerInfos.value = rankedPeers
                _peers.value = rankedPeers.map { it.endpoint }

                if (rankedPeers.isEmpty()) {
                    _error.value = "No masternodes found"
                    return@launch
                }

                Log.d(TAG, "Ranked ${rankedPeers.size} peers, " +
                    "${rankedPeers.count { it.isHealthy }} healthy")

                // Connect to the best healthy peer
                val healthyPeers = rankedPeers.filter { it.isHealthy }
                if (healthyPeers.isEmpty()) {
                    _error.value = "No healthy masternodes found"
                    return@launch
                }

                for (peer in healthyPeers) {
                    if (tryConnect(peer.endpoint, rpcCreds)) {
                        // Mark the active peer
                        _peerInfos.value = rankedPeers.map {
                            it.copy(isActive = it.endpoint == peer.endpoint)
                        }
                        return@launch
                    }
                }

                Log.e(TAG, "Could not connect to any masternode")
                _error.value = "Could not connect to any masternode"
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                _error.value = "Network error: ${e.message}"
            }
        }
    }

    /** Try to connect to a single peer endpoint. Returns true if successful. */
    private suspend fun tryConnect(
        url: String,
        credentials: Pair<String, String>? = null,
    ): Boolean {
        return try {
            Log.d(TAG, "Trying peer: $url")
            val client = MasternodeClient(url, credentials)
            val h = client.healthCheck()
            masternodeClient = client
            _health.value = h
            _connectedPeer.value = url
            Log.d(TAG, "Connected to $url, block=${h.blockHeight}")

            // Initial data fetch
            refreshBalance()
            refreshTransactions()
            refreshUtxos()
            discoverAddresses()
            startWebSocket()
            startPolling()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Peer $url failed: ${e.message}")
            false
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
                delay(30_000)
                try {
                    refreshBalance()
                    refreshTransactions()
                    refreshUtxos()
                    masternodeClient?.let { _health.value = it.healthCheck() }
                } catch (e: Exception) {
                    Log.w(TAG, "Polling cycle failed", e)
                }
            }
        }
    }

    // ── Actions ──

    fun refreshBalance() {
        scope.launch {
            try {
                val w = wallet ?: run { Log.w(TAG, "refreshBalance: wallet is null"); return@launch }
                val client = masternodeClient ?: run { Log.w(TAG, "refreshBalance: client is null"); return@launch }
                val bal = client.getBalances(w.getAddresses())
                Log.d(TAG, "refreshBalance: confirmed=${bal.confirmed} pending=${bal.pending} total=${bal.total}")
                _balance.value = bal
            } catch (e: Exception) {
                Log.e(TAG, "refreshBalance failed", e)
            }
        }
    }

    fun refreshTransactions() {
        scope.launch {
            try {
                val w = wallet ?: run { Log.w(TAG, "refreshTransactions: wallet is null"); return@launch }
                val client = masternodeClient ?: run { Log.w(TAG, "refreshTransactions: client is null"); return@launch }
                val rawTxs = client.getTransactionsMulti(w.getAddresses(), 100)
                Log.d(TAG, "refreshTransactions: ${rawTxs.size} raw entries")

                val ownAddresses = w.getAddresses().toSet()

                // Expand consolidate (self-send) entries by fetching per-output
                // details from gettransaction. This gives processTransactions
                // individual outputs so it can identify the real send amount
                // vs change and create proper send + receive + fee entries.
                val consolidateTxs = rawTxs.filter { it.isConsolidate }
                val normalTxs = rawTxs.filter { !it.isConsolidate }
                val expandedTxs = mutableListOf<TransactionRecord>()

                for (ctx in consolidateTxs) {
                    try {
                        val (fee, outputs) = client.getTransactionDetail(ctx.txid)
                        Log.d(TAG, "Expanding consolidate txid=${ctx.txid.take(12)}.. " +
                            "${outputs.size} outputs, fee=$fee")

                        // Create a send entry for each output (processTransactions
                        // will detect self-send and pick smallest as "real" send)
                        for (out in outputs) {
                            expandedTxs.add(TransactionRecord(
                                txid = ctx.txid,
                                vout = out.index,
                                isSend = true,
                                address = out.address,
                                amount = out.value,
                                fee = fee,
                                timestamp = ctx.timestamp,
                                status = ctx.status,
                                blockHash = ctx.blockHash,
                                blockHeight = ctx.blockHeight,
                                confirmations = ctx.confirmations,
                            ))
                            Log.d(TAG, "  output[${out.index}]: ${out.value} → ${out.address.take(16)}..")
                        }
                    } catch (e: Exception) {
                        // Fallback: show as fee-only if gettransaction fails
                        Log.w(TAG, "Failed to expand consolidate ${ctx.txid.take(12)}..", e)
                        if (ctx.fee > 0) {
                            expandedTxs.add(ctx.copy(
                                isFee = true,
                                isConsolidate = false,
                                address = "Self-send fee",
                                amount = ctx.fee,
                            ))
                        }
                    }
                }

                val allTxs = normalTxs + expandedTxs
                val processed = processTransactions(allTxs, ownAddresses)
                Log.d(TAG, "refreshTransactions: ${processed.size} after change filtering")

                _transactions.value = processed

                // Cache to DB
                transactionDao.upsertAll(processed.map {
                    TransactionEntity(
                        txid = it.txid, vout = it.vout,
                        isSend = it.isSend, isFee = it.isFee,
                        address = it.address,
                        amount = it.amount, fee = it.fee, timestamp = it.timestamp,
                        status = it.status.name.lowercase(), blockHeight = it.blockHeight,
                        confirmations = it.confirmations,
                    )
                })
            } catch (e: Exception) {
                Log.e(TAG, "refreshTransactions failed", e)
            }
        }
    }

    /**
     * Post-process raw RPC transaction entries:
     * 1. Deduplicate by (txid, isSend, vout)
     * 2. Filter out change outputs (receive to own address for a txid we sent)
     * 3. Filter out send entries for change destinations
     * 4. Create separate fee entries for sends with fees
     * 5. Synthesize missing receive entries for send-to-self transactions
     *
     * Mirrors the desktop wallet's state.rs change detection logic.
     */
    private fun processTransactions(
        rawTxs: List<TransactionRecord>,
        ownAddresses: Set<String>,
    ): List<TransactionRecord> {
        Log.d(TAG, "processTransactions: ${rawTxs.size} raw entries, ${ownAddresses.size} own addresses")
        for (tx in rawTxs) {
            Log.d(TAG, "  RAW: txid=${tx.txid.take(12)}.. ${if (tx.isSend) "SEND" else "RECV"} " +
                "amount=${tx.amount} fee=${tx.fee} addr=${tx.address.take(16)}.. vout=${tx.vout}")
        }

        // 1. Deduplicate by (txid, isSend, vout)
        val seen = mutableSetOf<String>()
        val deduped = rawTxs.filter { seen.add(it.uniqueKey) }

        // 2. Identify txids where we were the sender and build destination maps
        val sendsByTxid = deduped
            .filter { it.isSend }
            .groupBy { it.txid }

        val sendTxids = sendsByTxid.keys

        // For each send txid, find the "real" send destination(s):
        // destinations to addresses NOT in our wallet, or if ALL destinations
        // are our own (send-to-self), the one with the smallest amount is the
        // actual send and the rest are change.
        val realSendDests = mutableMapOf<String, MutableSet<String>>() // txid → real dest addresses
        for ((txid, sends) in sendsByTxid) {
            val externalDests = sends.filter { it.address !in ownAddresses }
            if (externalDests.isNotEmpty()) {
                // Normal send: external addresses are the real destinations
                realSendDests[txid] = externalDests.map { it.address }.toMutableSet()
                Log.d(TAG, "  NORMAL SEND txid=${txid.take(12)}.. → ${externalDests.size} external dests")
            } else {
                // Send-to-self: all destinations are ours. Keep the smallest
                // non-zero as the "real" send; the rest are change.
                val sorted = sends.filter { it.amount > 0 }.sortedBy { it.amount }
                if (sorted.isNotEmpty()) {
                    realSendDests[txid] = mutableSetOf(sorted.first().address)
                    Log.d(TAG, "  SELF-SEND txid=${txid.take(12)}.. picked smallest amount=${sorted.first().amount} " +
                        "addr=${sorted.first().address.take(16)}.. (${sends.size} total sends)")
                    for (s in sorted) {
                        Log.d(TAG, "    candidate: amount=${s.amount} addr=${s.address.take(16)}.. vout=${s.vout}")
                    }
                }
            }
        }

        // 3. Filter: remove change send entries and change receive entries
        val keptSelfReceive = mutableSetOf<String>()
        val filtered = deduped.filter { tx ->
            if (tx.isSend) {
                // Filter out send entries for change destinations (own address
                // that is NOT the real send destination for this txid)
                if (tx.txid in sendTxids && tx.address in ownAddresses) {
                    val realDests = realSendDests[tx.txid]
                    if (realDests != null && tx.address !in realDests) {
                        Log.d(TAG, "Filtering change send: txid=${tx.txid.take(12)}.. amount=${tx.amount} addr=${tx.address.take(12)}..")
                        return@filter false
                    }
                }
                return@filter true
            }

            // Keep receives for txids we didn't send
            if (tx.txid !in sendTxids) return@filter true

            // Receive for a txid we sent — check if it's change or a real receive
            if (tx.address !in ownAddresses) return@filter true

            // This is a receive to our own address for a txid we sent.
            val realDests = realSendDests[tx.txid]
            if (realDests != null && tx.address in realDests && tx.txid !in keptSelfReceive) {
                // Send-to-self: keep one receive matching the send destination
                keptSelfReceive.add(tx.txid)
                Log.d(TAG, "Keeping self-receive: txid=${tx.txid.take(12)}.. amount=${tx.amount} addr=${tx.address.take(16)}..")
                return@filter true
            }

            // Change output — filter it out
            Log.d(TAG, "Filtering change output: txid=${tx.txid.take(12)}.. amount=${tx.amount} addr=${tx.address.take(12)}..")
            false
        }

        Log.d(TAG, "After filter: ${filtered.size} entries")
        for (tx in filtered) {
            Log.d(TAG, "  KEPT: txid=${tx.txid.take(12)}.. ${if (tx.isSend) "SEND" else "RECV"} " +
                "amount=${tx.amount} fee=${tx.fee} addr=${tx.address.take(16)}.. vout=${tx.vout}")
        }

        // 4. Create separate fee entries for sends
        val feeEntries = mutableListOf<TransactionRecord>()
        val seenFeeTxids = mutableSetOf<String>()
        for (tx in filtered) {
            if (tx.isSend && tx.fee > 0 && tx.txid !in seenFeeTxids) {
                seenFeeTxids.add(tx.txid)
                feeEntries.add(
                    TransactionRecord(
                        txid = tx.txid,
                        vout = -1,
                        isSend = true,
                        isFee = true,
                        address = "Network Fee",
                        amount = tx.fee,
                        fee = 0,
                        timestamp = tx.timestamp,
                        status = tx.status,
                        blockHash = tx.blockHash,
                        blockHeight = tx.blockHeight,
                        confirmations = tx.confirmations,
                    )
                )
                Log.d(TAG, "  FEE: txid=${tx.txid.take(12)}.. amount=${tx.fee}")
            }
        }

        // 5. Synthesize missing receive entries for send-to-self transactions.
        // If the destination is one of our own addresses and we don't already
        // have a receive entry, add one so the history shows the full picture.
        val synthReceives = mutableListOf<TransactionRecord>()
        for ((txid, sends) in sendsByTxid) {
            for (send in sends) {
                if (send.address !in ownAddresses) continue
                val realDests = realSendDests[txid] ?: continue
                if (send.address !in realDests) continue
                val hasReceive = filtered.any { it.txid == txid && !it.isSend && !it.isFee }
                if (!hasReceive) {
                    Log.d(TAG, "  SYNTH RECV: txid=${txid.take(12)}.. amount=${send.amount} addr=${send.address.take(16)}..")
                    synthReceives.add(
                        TransactionRecord(
                            txid = txid,
                            vout = send.vout,
                            isSend = false,
                            address = send.address,
                            amount = send.amount,
                            fee = 0,
                            timestamp = send.timestamp,
                            status = send.status,
                            blockHash = send.blockHash,
                            blockHeight = send.blockHeight,
                            confirmations = send.confirmations,
                        )
                    )
                } else {
                    Log.d(TAG, "  RECV exists for self-send txid=${txid.take(12)}.. (no synth needed)")
                }
            }
        }

        val result = filtered + feeEntries + synthReceives
        Log.d(TAG, "processTransactions FINAL: ${result.size} entries")
        for (tx in result) {
            Log.d(TAG, "  FINAL: txid=${tx.txid.take(12)}.. " +
                "${if (tx.isFee) "FEE" else if (tx.isSend) "SEND" else "RECV"} " +
                "amount=${tx.amount} addr=${tx.address.take(16)}..")
        }

        // 6. Combine and sort by timestamp descending
        return result.sortedByDescending { it.timestamp }
    }

    fun refreshUtxos() {
        scope.launch {
            try {
                val w = wallet ?: run { Log.w(TAG, "refreshUtxos: wallet is null"); return@launch }
                val client = masternodeClient ?: run { Log.w(TAG, "refreshUtxos: client is null"); return@launch }
                val utxos = client.getUtxos(w.getAddresses())
                Log.d(TAG, "refreshUtxos: ${utxos.size} utxos, spendable=${utxos.count { it.spendable }}")
                w.setUtxos(utxos)
                _utxos.value = utxos
                _balance.value = _balance.value.copy(
                    confirmed = utxos.filter { it.spendable }.sumOf { it.amount }
                )
                _utxoSynced.value = true
            } catch (e: Exception) {
                Log.e(TAG, "refreshUtxos failed", e)
            }
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

    fun saveContact(name: String, address: String, email: String = "", phone: String = "") {
        scope.launch {
            val existing = contactDao.getByAddress(address)
            if (existing != null) {
                contactDao.upsert(existing.copy(
                    name = name,
                    email = email,
                    phone = phone,
                    updatedAt = System.currentTimeMillis() / 1000,
                ))
            } else {
                contactDao.upsert(ContactEntity(
                    address = address,
                    name = name,
                    email = email,
                    phone = phone,
                ))
            }
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
    fun showTransaction(tx: TransactionRecord) {
        _selectedTransaction.value = tx
        _screen.value = Screen.TransactionDetail
    }
    fun clearError() { _error.value = null }
    fun clearSuccess() { _success.value = null }

    /** Lock the wallet: disconnect, clear sensitive state, return to PIN screen. */
    fun lockWallet() {
        Log.d(TAG, "lockWallet: disconnecting and clearing state")
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        pollJob = null

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
        _screen.value = Screen.PinUnlock
    }

    /** Get the wallet file for export/sharing. */
    fun getWalletFile(): java.io.File? {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
        return WalletManager.walletFile(walletDir, network)
    }

    /** Delete current wallet, auto-backup first, reset all state. Returns success. */
    fun deleteWallet(): Boolean {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet

        // Disconnect
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        pollJob = null

        // Delete (with auto-backup)
        val deleted = WalletManager.deleteWallet(walletDir, network)
        Log.d(TAG, "deleteWallet: file deleted=$deleted")

        // Clear database
        scope.launch {
            try {
                transactionDao.deleteAll()
                Log.d(TAG, "deleteWallet: cleared transaction cache")
            } catch (e: Exception) {
                Log.e(TAG, "deleteWallet: failed to clear DB", e)
            }
        }

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

        if (!deleted) {
            _error.value = "Could not delete wallet file. Please try again."
        }
        return deleted
    }

    /** Reindex: erase cached UTXOs and transactions, then resync from the masternode. */
    fun reindexWallet() {
        scope.launch {
            _reindexing.value = true
            Log.d(TAG, "reindexWallet: clearing cached data and resyncing")
            try {
                // Clear DB caches
                transactionDao.deleteAll()

                // Reset in-memory state
                _balance.value = Balance()
                _transactions.value = emptyList()
                _utxos.value = emptyList()
                _utxoSynced.value = false

                wallet?.setUtxos(emptyList())

                // Resync from masternode
                if (masternodeClient != null) {
                    refreshBalance()
                    refreshTransactions()
                    refreshUtxos()
                    discoverAddresses()
                    _success.value = "Wallet reindexed successfully"
                } else {
                    _error.value = "Not connected to masternode — reconnecting"
                    connectToNetwork()
                }
            } catch (e: Exception) {
                Log.e(TAG, "reindexWallet failed", e)
                _error.value = "Reindex failed: ${e.message}"
            } finally {
                _reindexing.value = false
            }
        }
    }

    /** List available wallet backups for the current network. */
    fun refreshBackups() {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
        _backups.value = WalletManager.listBackups(walletDir, network)
    }

    /** Create a manual backup now. */
    fun createBackup(): java.io.File? {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
        val file = WalletManager.backupWallet(walletDir, network)
        if (file != null) {
            _success.value = "Backup created: ${file.name}"
            refreshBackups()
        }
        return file
    }

    /** Restore a wallet from a backup file. */
    @Suppress("UNUSED_PARAMETER")
    fun restoreBackup(backupFile: java.io.File, password: String? = null) {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet

        // Disconnect first
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        pollJob = null

        val restored = WalletManager.restoreBackup(walletDir, network, backupFile)
        if (restored) {
            _success.value = "Backup restored. Reloading wallet..."
            scope.launch {
                transactionDao.deleteAll()
            }
            // Reset state, user will need to unlock
            wallet = null
            currentPassword = null
            _walletLoaded.value = false
            _balance.value = Balance()
            _transactions.value = emptyList()
            _utxos.value = emptyList()
            _addresses.value = emptyList()
            _utxoSynced.value = false
            _screen.value = Screen.PasswordUnlock
        } else {
            _error.value = "Failed to restore backup"
        }
    }

    /** Delete a specific backup file. */
    fun deleteBackup(file: java.io.File): Boolean {
        val deleted = file.delete()
        if (deleted) refreshBackups()
        return deleted
    }

    /** Get the raw time.conf content for editing. */
    fun getConfigText(): String = ConfigManager.readRaw(walletDir)

    /** Save raw time.conf content. */
    fun saveConfigText(content: String) {
        ConfigManager.writeRaw(walletDir, content)
        _success.value = "Configuration saved"
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
        _peerInfos.value = emptyList()
        connectToNetwork()
    }

    /** Switch to a specific peer endpoint (manual peer selection from UI). */
    fun switchPeer(endpoint: String) {
        scope.launch {
            try {
                masternodeClient?.close()
                masternodeClient = null
                wsClient?.stop()
                wsClient = null
                pollJob?.cancel()
                pollJob = null
                _connectedPeer.value = null
                _wsConnected.value = false

                val config = ConfigManager.load(walletDir)
                val rpcCreds = ConfigManager.rpcCredentials(config)

                if (tryConnect(endpoint, rpcCreds)) {
                    _peerInfos.value = _peerInfos.value.map {
                        it.copy(isActive = it.endpoint == endpoint)
                    }
                    _success.value = "Switched to ${endpoint.substringAfter("://")}"
                } else {
                    _error.value = "Could not connect to $endpoint"
                    // Reconnect to any available peer
                    connectToNetwork()
                }
            } catch (e: Exception) {
                _error.value = "Switch failed: ${e.message}"
                connectToNetwork()
            }
        }
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
    TransactionDetail,
    Connections,
    Settings,
}
