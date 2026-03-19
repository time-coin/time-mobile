package com.timecoin.wallet.service

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.timecoin.wallet.crypto.*
import com.timecoin.wallet.crypto.BiometricHelper
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Separate databases for mainnet and testnet so data never mixes.
    private val mainnetDb by lazy {
        Room.databaseBuilder(context, WalletDatabase::class.java, "wallet.db")
            .fallbackToDestructiveMigration().build()
    }
    private val testnetDb by lazy {
        Room.databaseBuilder(context, WalletDatabase::class.java, "wallet-testnet.db")
            .fallbackToDestructiveMigration().build()
    }
    private val currentDb get() = if (_isTestnet.value) testnetDb else mainnetDb
    private val contactDao get() = currentDb.contactDao()
    private val transactionDao get() = currentDb.transactionDao()
    private val settingDao get() = currentDb.settingDao()
    private val paymentRequestDao get() = currentDb.paymentRequestDao()

    companion object {
        private const val TAG = "WalletService"
        private const val INITIAL_TX_LIMIT = 100
        private const val TX_PAGE_SIZE = 100
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

    // Cache for successfully expanded consolidate transactions
    private val consolidateCache = mutableMapOf<String, List<TransactionRecord>>()

    // Cache for real recipient addresses: the masternode reports the change
    // address in send entries, so we resolve the true recipient via
    // getTransactionDetail and cache the result to avoid repeat RPC calls.
    private val sendRecipientCache = mutableMapOf<String, String>()

    // Progressive transaction loading state
    private var fetchedTxLimit = INITIAL_TX_LIMIT
    private val _hasMoreTransactions = MutableStateFlow(true)
    val hasMoreTransactions: StateFlow<Boolean> = _hasMoreTransactions
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    private val _addresses = MutableStateFlow<List<String>>(emptyList())
    val addresses: StateFlow<List<String>> = _addresses

    private val _contacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val contacts: StateFlow<List<ContactEntity>> = _contacts

    private val _utxoSynced = MutableStateFlow(false)
    val utxoSynced: StateFlow<Boolean> = _utxoSynced

    private val _transactionsSynced = MutableStateFlow(false)
    val transactionsSynced: StateFlow<Boolean> = _transactionsSynced

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

    // ── Payment requests ──
    private val _paymentRequests = MutableStateFlow<List<PaymentRequest>>(emptyList())
    val paymentRequests: StateFlow<List<PaymentRequest>> = _paymentRequests

    /** Most recent unreviewed incoming request — drives the notification dialog in WalletApp. */
    private val _incomingPaymentRequest = MutableStateFlow<PaymentRequest?>(null)
    val incomingPaymentRequest: StateFlow<PaymentRequest?> = _incomingPaymentRequest

    /** Request currently being reviewed on PaymentRequestReviewScreen. */
    private val _selectedPaymentRequest = MutableStateFlow<PaymentRequest?>(null)
    val selectedPaymentRequest: StateFlow<PaymentRequest?> = _selectedPaymentRequest

    /** Max non-cancelled outgoing requests to the same address per hour. */
    private val RATE_LIMIT_MAX = 3
    private val RATE_LIMIT_WINDOW_SECS = 3600L
    private val PAYMENT_REQUEST_EXPIRY_SECS = 86400L // 24 hours — matches masternode TTL

    private val _backups = MutableStateFlow<List<java.io.File>>(emptyList())
    val backups: StateFlow<List<java.io.File>> = _backups

    // ── Internal state ──
    private var wallet: WalletManager? = null
    private var masternodeClient: MasternodeClient? = null
    private var wsClient: WsNotificationClient? = null
    private var currentPassword: String? = null
    private var pollJob: Job? = null
    private var backgroundSyncJob: Job? = null
    private var pendingMnemonic: String? = null

    private val walletDir get() = context.filesDir

    // ── Wallet lifecycle ──

    init {
        scope.launch {
            val saved = settingDao.get("decimal_places")
            if (saved != null) _decimalPlaces.value = saved.toIntOrNull() ?: 2
            expirePaymentRequests()
            _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
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

                // Load cached data from Room for instant display
                val cachedTxs = transactionDao.getAll()
                if (cachedTxs.isNotEmpty()) {
                    _transactions.value = cachedTxs.map { it.toTransactionRecord() }
                    Log.d(TAG, "loadWallet: ${cachedTxs.size} cached transactions loaded")
                }
                val cachedBal = settingDao.get("cached_balance")
                if (cachedBal != null) {
                    try {
                        val parts = cachedBal.split(",")
                        val confirmed = parts[0].toLong()
                        val pending = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        _balance.value = Balance(
                            confirmed = confirmed,
                            pending = pending,
                            total = confirmed + pending,
                        )
                        Log.d(TAG, "loadWallet: cached balance confirmed=$confirmed pending=$pending")
                    } catch (_: Exception) {}
                }

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

                // Load manual config (testnet config lives in testnet/ subdirectory)
                val config = ConfigManager.load(walletDir, isTestnet)
                val manualEndpoints = ConfigManager.manualEndpoints(config)
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

            // Kick off a quick balance fetch for immediate display while
            // discovery runs. Tx/UTXO sync is intentionally deferred until
            // after discovery so the "Verified" badge only appears once we
            // know the full address set and have complete data.
            refreshBalance()

            // Start WebSocket right away so we don't miss real-time events
            // during the address trim / discovery phase.
            startWebSocket()
            startPolling()

            // Trim and discover run sequentially, then do the full sync that
            // flips the status to "Verified" once everything is confirmed.
            scope.launch {
                trimEmptyAddresses(client)
                discoverAddresses() // calls refreshTransactionsSync/refreshUtxosSync with markSynced=true at end
            }

            startBackgroundSync()
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
                    is WsEvent.Connected -> {
                        _wsConnected.value = true
                        retryUndeliveredPaymentRequests()
                    }
                    is WsEvent.Disconnected -> _wsConnected.value = false
                    is WsEvent.TransactionReceived -> {
                        // Immediately add transaction to UI for instant feedback
                        val notif = event.notification
                        val w = wallet
                        val isSend = w != null && !w.getAddresses().contains(notif.address)
                        val instant = TransactionRecord(
                            txid = notif.txid,
                            vout = notif.outputIndex,
                            isSend = isSend,
                            address = notif.address,
                            amount = notif.amount,
                            timestamp = if (notif.timestamp > 0) notif.timestamp
                                        else System.currentTimeMillis() / 1000,
                            status = TransactionStatus.Pending,
                        )
                        val current = _transactions.value
                        if (current.none { it.uniqueKey == instant.uniqueKey }) {
                            _transactions.value = listOf(instant) + current
                        }
                        // Mark balance as unverified until UTXO refresh completes
                        _utxoSynced.value = false
                        _transactionsSynced.value = false
                        // Then do a full refresh in the background for complete data
                        refreshBalance()
                        refreshTransactions()
                        refreshUtxos()
                    }
                    is WsEvent.UtxoFinalized -> refreshUtxos()
                    is WsEvent.TransactionRejected -> {
                        _error.value = "Transaction rejected: ${event.reason}"
                    }
                    is WsEvent.PaymentRequestReceived -> {
                        val r = event.request
                        val entity = PaymentRequestEntity(
                            id = r.id,
                            requesterAddress = r.requesterAddress,
                            payerAddress = r.payerAddress,
                            amountSats = r.amountSats,
                            memo = r.memo,
                            requesterName = r.requesterName,
                            status = "pending",
                            isOutgoing = false,
                            createdAt = r.timestamp,
                            updatedAt = r.timestamp,
                        )
                        paymentRequestDao.upsert(entity)
                        val req = entity.toPaymentRequest()
                        _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
                        _incomingPaymentRequest.value = req
                    }
                    is WsEvent.PaymentRequestCancelled -> {
                        paymentRequestDao.updateStatus(
                            id = event.requestId,
                            status = "cancelled",
                            updatedAt = System.currentTimeMillis() / 1000,
                        )
                        _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
                        if (_incomingPaymentRequest.value?.id == event.requestId) {
                            _incomingPaymentRequest.value = null
                        }
                        if (_selectedPaymentRequest.value?.id == event.requestId) {
                            _selectedPaymentRequest.value = _selectedPaymentRequest.value?.copy(
                                status = PaymentRequestStatus.Cancelled
                            )
                        }
                    }
                    is WsEvent.PaymentRequestResponse -> {
                        val status = if (event.accepted) "accepted" else "declined"
                        paymentRequestDao.updateStatus(
                            id = event.requestId,
                            status = status,
                            updatedAt = System.currentTimeMillis() / 1000,
                        )
                        _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
                        val msg = if (event.accepted) "Payment request accepted!" else "Payment request declined."
                        _success.value = msg
                    }
                    is WsEvent.PaymentRequestViewed -> {
                        paymentRequestDao.markViewed(event.requestId)
                        _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
                    }
                }
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        backgroundSyncJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(30_000)
                try {
                    refreshBalance()
                    refreshTransactions()
                    refreshUtxos()
                    masternodeClient?.let { _health.value = it.healthCheck() }
                    expirePaymentRequests()
                } catch (e: Exception) {
                    Log.w(TAG, "Polling cycle failed", e)
                }
            }
        }
    }

    /**
     * After the initial sync shows "Verified", progressively download
     * the full transaction history in the background. Each page reuses
     * consolidateCache so only new self-send txids trigger RPC calls.
     * Results are saved to Room for offline/search access.
     */
    private fun startBackgroundSync() {
        backgroundSyncJob?.cancel()
        backgroundSyncJob = scope.launch {
            // Wait until the initial sync is verified (timeout after 2 min to avoid hanging forever)
            withTimeoutOrNull(120_000) { transactionsSynced.first { it } }
                ?: run { Log.w(TAG, "backgroundSync: timed out waiting for initial sync"); return@launch }
            Log.d(TAG, "backgroundSync: initial sync verified, starting full history download")

            while (isActive && _hasMoreTransactions.value) {
                delay(3_000) // pace requests to avoid overloading masternode
                try {
                    val w = wallet ?: break
                    val client = masternodeClient ?: break
                    fetchedTxLimit += TX_PAGE_SIZE
                    Log.d(TAG, "backgroundSync: fetching with limit=$fetchedTxLimit")

                    val rawTxs = client.getTransactionsMulti(w.getAddresses(), fetchedTxLimit)
                    _hasMoreTransactions.value = rawTxs.size >= fetchedTxLimit

                    val processed = expandAndProcess(rawTxs, w, client)
                    _transactions.value = processed
                    cacheTransactionsToDb(processed)
                    Log.d(TAG, "backgroundSync: cached ${processed.size} transactions (limit=$fetchedTxLimit, more=${_hasMoreTransactions.value})")
                } catch (e: Exception) {
                    Log.w(TAG, "backgroundSync: batch failed, retrying", e)
                    fetchedTxLimit -= TX_PAGE_SIZE
                    delay(10_000)
                }
            }
            Log.d(TAG, "backgroundSync: complete, all transactions cached (limit=$fetchedTxLimit)")
        }
    }

    // ── Actions ──

    fun refreshBalance() {
        scope.launch {
            try { refreshBalanceSync() }
            catch (e: Exception) { Log.e(TAG, "refreshBalance failed", e) }
        }
    }

    private suspend fun refreshBalanceSync() {
        val w = wallet ?: run { Log.w(TAG, "refreshBalance: wallet is null"); return }
        val client = masternodeClient ?: run { Log.w(TAG, "refreshBalance: client is null"); return }
        val bal = client.getBalances(w.getAddresses())
        Log.d(TAG, "refreshBalance: confirmed=${bal.confirmed} pending=${bal.pending} total=${bal.total}")
        _balance.value = bal
        // Cache for instant display on next startup
        settingDao.set(SettingEntity("cached_balance", "${bal.confirmed},${bal.pending}"))
    }

    fun refreshTransactions() {
        scope.launch {
            try { refreshTransactionsSync() }
            catch (e: Exception) { Log.e(TAG, "refreshTransactions failed", e) }
        }
    }

    private suspend fun refreshTransactionsSync(markSynced: Boolean = true) {
        val w = wallet ?: run { Log.w(TAG, "refreshTransactions: wallet is null"); return }
        val client = masternodeClient ?: run { Log.w(TAG, "refreshTransactions: client is null"); return }
        val rawTxs = client.getTransactionsMulti(w.getAddresses(), fetchedTxLimit)
        Log.d(TAG, "refreshTransactions: ${rawTxs.size} raw entries (limit=$fetchedTxLimit)")

        _hasMoreTransactions.value = rawTxs.size >= fetchedTxLimit

        val processed = expandAndProcess(rawTxs, w, client)
        _transactions.value = processed
        if (markSynced) _transactionsSynced.value = true
        cacheTransactionsToDb(processed)
    }

    /**
     * Load older transactions by increasing the fetch limit.
     * Reuses consolidateCache so already-expanded self-sends skip RPC calls.
     */
    fun loadMoreTransactions() {
        scope.launch {
            if (_loadingMore.value) return@launch
            if (!_hasMoreTransactions.value) return@launch
            _loadingMore.value = true
            try {
                val w = wallet ?: return@launch
                val client = masternodeClient ?: return@launch
                fetchedTxLimit += TX_PAGE_SIZE
                Log.d(TAG, "loadMoreTransactions: fetching with limit=$fetchedTxLimit")

                val rawTxs = client.getTransactionsMulti(w.getAddresses(), fetchedTxLimit)
                _hasMoreTransactions.value = rawTxs.size >= fetchedTxLimit

                val processed = expandAndProcess(rawTxs, w, client)
                _transactions.value = processed
                cacheTransactionsToDb(processed)
            } catch (e: Exception) {
                Log.e(TAG, "loadMoreTransactions failed", e)
                fetchedTxLimit -= TX_PAGE_SIZE
            } finally {
                _loadingMore.value = false
            }
        }
    }

    /**
     * Search for a specific transaction by txid. Checks Room cache first,
     * then falls back to direct gettransaction RPC call.
     */
    fun searchTransaction(txid: String) {
        scope.launch {
            try {
                // Check if already in the displayed list
                if (_transactions.value.any { it.txid == txid }) {
                    Log.d(TAG, "searchTransaction: $txid already in list")
                    return@launch
                }

                // Check Room cache
                val cached = transactionDao.search(txid)
                if (cached.isNotEmpty()) {
                    Log.d(TAG, "searchTransaction: found ${cached.size} entries in Room")
                    val existing = _transactions.value
                    val cachedRecords = cached.map { it.toTransactionRecord() }
                    _transactions.value = (existing + cachedRecords)
                        .distinctBy { it.uniqueKey }
                        .sortedByDescending { it.timestamp }
                    return@launch
                }

                // Direct RPC lookup — fetch full transaction details
                val client = masternodeClient ?: return@launch
                val w = wallet ?: return@launch
                val ownAddresses = w.getAddresses().toSet()

                val (fee, outputs) = client.getTransactionDetail(txid)
                Log.d(TAG, "searchTransaction: found txid=$txid, ${outputs.size} outputs, fee=$fee")

                val entries = outputs.map { out ->
                    val toOwn = out.address in ownAddresses
                    TransactionRecord(
                        txid = txid, vout = out.index,
                        isSend = toOwn, address = out.address,
                        amount = out.value, fee = fee, timestamp = 0,
                        status = TransactionStatus.Approved,
                    )
                }

                val processed = processTransactions(entries, ownAddresses)
                val existing = _transactions.value
                _transactions.value = (existing + processed)
                    .distinctBy { it.uniqueKey }
                    .sortedByDescending { it.timestamp }
                cacheTransactionsToDb(processed)
            } catch (e: Exception) {
                Log.e(TAG, "searchTransaction failed for $txid", e)
                _error.value = "Transaction not found: ${txid.take(16)}…"
            }
        }
    }

    /**
     * Expand consolidate entries and run processTransactions.
     * Uses consolidateCache to skip getTransactionDetail RPC for
     * already-expanded txids (the "don't repeat batches" optimization).
     */
    private suspend fun expandAndProcess(
        rawTxs: List<TransactionRecord>,
        w: com.timecoin.wallet.wallet.WalletManager,
        client: com.timecoin.wallet.network.MasternodeClient,
    ): List<TransactionRecord> {
        val ownAddresses = w.getAddresses().toSet()
        val consolidateTxs = rawTxs.filter { it.isConsolidate }
        val normalTxs = rawTxs.filter { !it.isConsolidate }
        val expandedTxs = mutableListOf<TransactionRecord>()

        // Expand all consolidate txs in parallel — each getTransactionDetail
        // is an independent RPC call so there is no ordering dependency.
        val expandedEntries: List<TransactionRecord> = coroutineScope {
            consolidateTxs.map { ctx ->
                async {
                    val cached = consolidateCache[ctx.txid]
                    if (cached != null) {
                        Log.d(TAG, "Reusing cached expansion for ${ctx.txid.take(12)}.. (${cached.size} entries)")
                        return@async cached
                    }
                    try {
                        val (fee, outputs) = client.getTransactionDetail(ctx.txid)
                        Log.d(TAG, "Expanding consolidate txid=${ctx.txid.take(12)}.. ${outputs.size} outputs, fee=$fee")
                        outputs.forEach { Log.d(TAG, "  output[${it.index}]: ${it.value} → ${it.address.take(16)}..") }
                        // Use the largest output as the single representative entry.
                        // Consolidations may include small dust/change outputs; picking
                        // the largest avoids the "smallest = real send" heuristic
                        // misidentifying a dust output as the consolidated amount.
                        val main = outputs.maxByOrNull { it.value } ?: outputs.first()
                        val entries = listOf(TransactionRecord(
                            txid = ctx.txid,
                            vout = main.index,
                            isSend = true,
                            address = main.address,
                            amount = main.value,
                            fee = fee,
                            timestamp = ctx.timestamp,
                            status = ctx.status,
                            blockHash = ctx.blockHash,
                            blockHeight = ctx.blockHeight,
                            confirmations = ctx.confirmations,
                        ))
                        consolidateCache[ctx.txid] = entries
                        entries
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to expand consolidate ${ctx.txid.take(12)}..", e)
                        if (ctx.fee > 0) listOf(ctx.copy(
                            isFee = true, isConsolidate = false,
                            address = "Network Fee", amount = ctx.fee, fee = 0,
                        )) else emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        expandedTxs.addAll(expandedEntries)

        // Correct recipient addresses for normal sends where the masternode
        // reports the change address instead of the actual recipient.
        // Resolved in parallel and cached to avoid repeat RPC calls.
        val wrongAddrSendTxids = normalTxs
            .filter { it.isSend && it.address in ownAddresses }
            .map { it.txid }.toSet()

        val addressCorrections: Map<String, String> = if (wrongAddrSendTxids.isEmpty()) {
            emptyMap()
        } else {
            coroutineScope {
                wrongAddrSendTxids.map { txid ->
                    async {
                        val cached = sendRecipientCache[txid]
                        if (cached != null) return@async txid to cached
                        try {
                            val (_, outputs) = client.getTransactionDetail(txid)
                            val realAddr = outputs.firstOrNull { it.address !in ownAddresses }?.address
                            if (realAddr != null) {
                                Log.d(TAG, "Resolved recipient for ${txid.take(12)}..: ${realAddr.take(16)}..")
                                sendRecipientCache[txid] = realAddr
                            }
                            txid to realAddr
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not resolve recipient for ${txid.take(12)}..: ${e.message}")
                            txid to null
                        }
                    }
                }.awaitAll().mapNotNull { (txid, addr) ->
                    if (addr != null) txid to addr else null
                }.toMap()
            }
        }

        val correctedNormalTxs = normalTxs.map { tx ->
            val correction = addressCorrections[tx.txid]
            if (tx.isSend && correction != null) tx.copy(address = correction) else tx
        }

        val allTxs = correctedNormalTxs + expandedTxs
        val processed = processTransactions(allTxs, ownAddresses)
        Log.d(TAG, "expandAndProcess: ${rawTxs.size} raw → ${processed.size} processed")
        return processed
    }

    private suspend fun cacheTransactionsToDb(processed: List<TransactionRecord>) {
        val existingMemos = transactionDao.getAll().associate { it.uniqueKey to it.memo }
        transactionDao.upsertAll(processed.map {
            TransactionEntity(
                txid = it.txid, vout = it.vout,
                isSend = it.isSend, isFee = it.isFee,
                address = it.address,
                amount = it.amount, fee = it.fee, timestamp = it.timestamp,
                status = it.status.name.lowercase(), blockHeight = it.blockHeight,
                confirmations = it.confirmations,
                memo = existingMemos[it.uniqueKey] ?: it.memo,
            )
        })
    }

    fun saveMemo(tx: TransactionRecord, memo: String) {
        scope.launch {
            transactionDao.updateMemo(tx.txid, tx.vout, tx.isSend, tx.isFee, memo)
            _transactions.value = _transactions.value.map {
                if (it.uniqueKey == tx.uniqueKey) it.copy(memo = memo) else it
            }
            if (_selectedTransaction.value?.uniqueKey == tx.uniqueKey) {
                _selectedTransaction.value = _selectedTransaction.value?.copy(memo = memo)
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

        // For each send txid, identify the real send output vouts so we can
        // filter out change send entries. The masternode uses "consolidate"
        // category for genuine self-sends; "send" category always implies at
        // least one external output. When all send entry addresses are in
        // ownAddresses it is because the masternode reports the change address
        // (not the recipient) in the send entry — this is normal masternode
        // behaviour, not a self-send. In both cases we keep the send entry and
        // filter all receive entries to own addresses as change.
        val realSendVouts = mutableMapOf<String, MutableSet<Int>>() // txid → real send vouts
        for ((txid, sends) in sendsByTxid) {
            val externalDests = sends.filter { it.address !in ownAddresses }
            if (externalDests.isNotEmpty()) {
                realSendVouts[txid] = externalDests.map { it.vout }.toMutableSet()
                Log.d(TAG, "  SEND txid=${txid.take(12)}.. → ${externalDests.size} external dests")
            } else {
                // Masternode reported change address in send entry — keep all
                // send vouts so the send entry itself is not filtered out.
                realSendVouts[txid] = sends.map { it.vout }.toMutableSet()
                Log.d(TAG, "  SEND txid=${txid.take(12)}.. all dests are own (masternode change-addr reporting)")
            }
        }

        // 3. Filter: remove change send entries and change receive entries.
        // The masternode uses "consolidate" for genuine self-sends; for all
        // "send" category entries, receive entries to own addresses are always
        // change and must be filtered regardless of vout.
        val filtered = deduped.filter { tx ->
            if (tx.isSend) {
                // Filter out send entries for change outputs (own address
                // whose vout is NOT a real send vout for this txid)
                if (tx.txid in sendTxids && tx.address in ownAddresses) {
                    val realVouts = realSendVouts[tx.txid]
                    if (realVouts != null && tx.vout !in realVouts) {
                        Log.d(TAG, "Filtering change send: txid=${tx.txid.take(12)}.. amount=${tx.amount} vout=${tx.vout} addr=${tx.address.take(12)}..")
                        return@filter false
                    }
                }
                return@filter true
            }

            // Keep receives for txids we didn't send
            if (tx.txid !in sendTxids) return@filter true

            // Receive for a txid we sent to an external address — keep it
            if (tx.address !in ownAddresses) return@filter true

            // Receive to our own address for a txid we sent: always change.
            // True self-sends come in as "consolidate" category and are
            // expanded by expandAndProcess before reaching here.
            Log.d(TAG, "Filtering change output: txid=${tx.txid.take(12)}.. amount=${tx.amount} vout=${tx.vout} addr=${tx.address.take(12)}..")
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
            if (tx.isSend && !tx.isFee && tx.fee > 0 && tx.txid !in seenFeeTxids) {
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

        val result = filtered + feeEntries
        Log.d(TAG, "processTransactions FINAL: ${result.size} entries")
        for (tx in result) {
            Log.d(TAG, "  FINAL: txid=${tx.txid.take(12)}.. " +
                "${if (tx.isFee) "FEE" else if (tx.isSend) "SEND" else "RECV"} " +
                "amount=${tx.amount} addr=${tx.address.take(16)}..")
        }

        // 6. Combine and sort: group entries by txid, order groups by their
        // max timestamp descending, then within each txid: RECV, FEE, SEND.
        val groupTimestamp = result.groupBy { it.txid }
            .mapValues { (_, entries) -> entries.maxOf { it.timestamp } }
        return result.sortedWith(
            compareByDescending<TransactionRecord> { groupTimestamp[it.txid] ?: it.timestamp }
                .thenBy { it.txid }
                .thenBy { when {
                    !it.isSend && !it.isFee -> 0  // RECV first (top)
                    it.isFee -> 1                 // FEE middle
                    else -> 2                     // SEND last (bottom)
                }}
        )
    }

    fun refreshUtxos() {
        scope.launch {
            try { refreshUtxosSync() }
            catch (e: Exception) { Log.e(TAG, "refreshUtxos failed", e) }
        }
    }

    private suspend fun refreshUtxosSync(markSynced: Boolean = true) {
        val w = wallet ?: run { Log.w(TAG, "refreshUtxos: wallet is null"); return }
        val client = masternodeClient ?: run { Log.w(TAG, "refreshUtxos: client is null"); return }
        val utxos = client.getUtxos(w.getAddresses())
        val utxoSum = utxos.filter { it.spendable }.sumOf { it.amount }
        Log.d(TAG, "refreshUtxos: ${utxos.size} utxos, spendable=${utxos.count { it.spendable }}, sum=$utxoSum")
        w.setUtxos(utxos)
        _utxos.value = utxos
        if (markSynced) _utxoSynced.value = true
    }

    fun sendTransaction(toAddress: String, amount: Long) {
        scope.launch {
            _loading.value = true
            try {
                val w = wallet ?: throw Exception("Wallet not loaded")
                val client = masternodeClient ?: throw Exception("Not connected")

                val tx = w.createTransaction(toAddress, amount)
                val txid = client.broadcastTransaction(tx)

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
        val allAddrs = w.getAddresses()
        val newIndex = allAddrs.size - 1
        _addresses.value = allAddrs

        scope.launch {
            contactDao.upsert(ContactEntity(
                address = addr,
                label = "Address ${newIndex + 1}",
                isOwned = true,
                derivationIndex = newIndex,
            ))
            _contacts.value = contactDao.getAll()
            // Persist updated nextAddressIndex
            w.save(walletDir, currentPassword)
        }
        return addr
    }

    /**
     * Trim the address list to only addresses with actual activity.
     * Uses UTXOs and transaction history (batched RPCs) to determine
     * which addresses are active, then removes all trailing empty ones.
     */
    private suspend fun trimEmptyAddresses(client: MasternodeClient) {
        val w = wallet ?: return
        val addrs = w.getAddresses()
        if (addrs.size <= 1) return

        val activeIndices = mutableSetOf<Int>()

        // Check UTXOs — any address holding coins is active
        try {
            val utxos = client.getUtxos(addrs)
            for (utxo in utxos) {
                val idx = addrs.indexOf(utxo.address)
                if (idx >= 0) activeIndices.add(idx)
            }
        } catch (e: Exception) {
            Log.w(TAG, "trimEmptyAddresses: UTXO check failed", e)
        }

        // Check transaction history — any address with activity
        try {
            val txs = client.getTransactionsMulti(addrs, 200)
            for (tx in txs) {
                val idx = addrs.indexOf(tx.address)
                if (idx >= 0) activeIndices.add(idx)
            }
        } catch (e: Exception) {
            Log.w(TAG, "trimEmptyAddresses: tx history check failed", e)
        }

        // Any address with a contact entry (user-generated or user-labeled) must not be
        // trimmed — the user may have shared it or assigned a label before it received funds.
        val contactedAddresses = contactDao.getAll().map { it.address }.toSet()
        for ((idx, addr) in addrs.withIndex()) {
            if (addr in contactedAddresses) activeIndices.add(idx)
        }

        val lastActive = if (activeIndices.isEmpty()) 0 else activeIndices.max()
        // Keep only up to lastActive + 1 (no gap padding)
        val keepCount = lastActive + 1
        if (keepCount < addrs.size) {
            Log.d(TAG, "Trimming addresses: ${addrs.size} → $keepCount (last active index=$lastActive)")
            w.trimAddresses(keepCount)
            _addresses.value = w.getAddresses()
            w.save(walletDir, currentPassword)
        }
    }

    /**
     * Address discovery: probe addresses beyond the current set until we
     * find [GAP_LIMIT] consecutive empty addresses. Only permanently adds
     * addresses up to lastActive + GAP_LIMIT to maintain the BIP-44 gap
     * invariant without inflating the address list.
     */
    /**
     * BIP-44 address discovery: probe addresses beyond the current set
     * using the 20-address gap limit. Only permanently adds addresses
     * that have actual balance or transaction history.
     */
    private suspend fun discoverAddresses() {
        val w = wallet ?: return
        val client = masternodeClient ?: return
        val gapLimit = WalletManager.GAP_LIMIT

        val startIndex = w.getAddresses().size
        Log.d(TAG, "Starting address discovery from index $startIndex")

        val activeProbes = mutableListOf<Int>()

        // Pre-seed with any owned contacts that have a known derivation index.
        // This preserves addresses the user manually generated (e.g. named
        // "Savings") even if they have no blockchain activity yet — mirrors
        // the same protection that trimEmptyAddresses applies during normal
        // startup. Without this, reindex would drop those addresses.
        val ownedContacts = contactDao.getOwnedAddresses()
        for (c in ownedContacts) {
            val idx = c.derivationIndex ?: continue
            if (idx >= startIndex) {
                Log.d(TAG, "Pre-seeding contact address[$idx]: ${c.address.take(16)}..")
                activeProbes.add(idx)
            }
        }

        var windowStart = if (activeProbes.isEmpty()) startIndex else activeProbes.max() + 1

        // Probe in windows of GAP_LIMIT addresses at a time using batched
        // multi-address calls (2 parallel RPCs instead of GAP_LIMIT×2 sequential).
        // Stop when an entire window comes back empty.
        while (true) {
            val windowIndices = (windowStart until windowStart + gapLimit).toList()
            val windowAddrs = windowIndices.map { w.deriveAddressAt(it) }
            try {
                val (txs, utxos) = coroutineScope {
                    val txJob = async { client.getTransactionsMulti(windowAddrs, 1) }
                    val utxoJob = async { client.getUtxos(windowAddrs) }
                    txJob.await() to utxoJob.await()
                }
                val activeAddrs = mutableSetOf<String>()
                txs.forEach { activeAddrs.add(it.address) }
                utxos.forEach { activeAddrs.add(it.address) }

                if (activeAddrs.isEmpty()) break  // full window empty → done

                windowAddrs.forEachIndexed { i, addr ->
                    if (addr in activeAddrs) {
                        Log.d(TAG, "Discovered active address[${windowIndices[i]}]: $addr")
                        activeProbes.add(windowIndices[i])
                    }
                }
                // Advance window past the last active address found
                windowStart = activeProbes.max() + 1
            } catch (e: Exception) {
                Log.w(TAG, "Discovery window [$windowStart..${windowStart + gapLimit - 1}] failed: ${e.message}")
                break
            }
        }

        // Only add addresses that actually have history
        if (activeProbes.isNotEmpty()) {
            // Must generate sequentially up to the last active probe
            // (BIP-44 derivation is index-based)
            val targetCount = activeProbes.max() + 1
            val toGenerate = targetCount - w.getAddresses().size
            for (i in 0 until toGenerate) {
                w.generateAddress()
            }
            // Register only active ones as contacts
            for (idx in activeProbes) {
                val addr = w.getAddresses()[idx]
                val existing = contactDao.getByAddress(addr)
                if (existing == null) {
                    contactDao.upsert(ContactEntity(
                        address = addr,
                        label = "Address ${idx + 1}",
                        isOwned = true,
                        derivationIndex = idx,
                    ))
                }
            }
            // Now trim: remove trailing empty addresses we had to generate
            // to reach the active ones (keep only up to last active + 1)
            val lastActive = activeProbes.max()
            w.trimAddresses(lastActive + 1)
            Log.d(TAG, "Discovery complete. Added ${activeProbes.size} active addresses, total=${w.getAddresses().size}")
        } else {
            Log.d(TAG, "Discovery complete. No new active addresses beyond index $startIndex")
        }

        _addresses.value = w.getAddresses()
        w.save(walletDir, currentPassword)
        _contacts.value = contactDao.getAll()

        refreshBalance()
        refreshTransactions()
        refreshUtxos()
    }

    fun saveContact(name: String, address: String, email: String = "", phone: String = "") {
        // Optimistic update — show contact immediately before DB write completes
        val now = System.currentTimeMillis() / 1000
        val existing = _contacts.value.find { it.address == address }
        val optimistic = existing?.copy(name = name, email = email, phone = phone, updatedAt = now)
            ?: ContactEntity(address = address, name = name, email = email, phone = phone, createdAt = now, updatedAt = now)
        _contacts.value = (_contacts.value.filter { it.address != address } + optimistic)
            .sortedByDescending { it.updatedAt }

        scope.launch {
            if (existing != null) {
                contactDao.upsert(optimistic)
            } else {
                contactDao.upsert(optimistic)
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

    // ── Payment request methods ──

    /**
     * Send a payment request to [payerAddress] asking them to send [amountSats] to
     * the wallet address at [fromAddressIdx]. The request is signed with that address's
     * Ed25519 keypair so the masternode can verify authenticity.
     * Rate-limited to [RATE_LIMIT_MAX] active requests per target per hour.
     */
    fun sendPaymentRequest(
        payerAddress: String,
        amountSats: Long,
        memo: String = "",
        requesterName: String = "",
        fromAddressIdx: Int = 0,
    ) {
        scope.launch {
            val w = wallet ?: run { _error.value = "Wallet not loaded"; return@launch }
            val client = masternodeClient ?: run { _error.value = "Not connected"; return@launch }

            val since = System.currentTimeMillis() / 1000 - RATE_LIMIT_WINDOW_SECS
            val activeCount = paymentRequestDao.countActiveOutgoingTo(payerAddress, since)
            if (activeCount >= RATE_LIMIT_MAX) {
                _error.value = "Too many requests to this address. Wait before sending another, or cancel a previous one."
                return@launch
            }

            val requestId = java.util.UUID.randomUUID().toString()
            val addrs = w.getAddresses()
            val requesterAddress = addrs.getOrElse(fromAddressIdx) { w.primaryAddress }
            val now = System.currentTimeMillis() / 1000

            // Sign: id || requester_address || payer_address || amount_le8 || memo || timestamp_le8
            val keypair = w.deriveKeypair(fromAddressIdx)
            val signData = buildSignData(requestId, requesterAddress, payerAddress, amountSats, memo, now)
            val pubkeyHex = keypair.publicKeyBytes().toHexString()
            val signatureHex = keypair.sign(signData).toHexString()

            val entity = PaymentRequestEntity(
                id = requestId,
                requesterAddress = requesterAddress,
                payerAddress = payerAddress,
                amountSats = amountSats,
                memo = memo,
                requesterName = requesterName,
                status = "pending",
                isOutgoing = true,
                createdAt = now,
                updatedAt = now,
                expiresAt = now + PAYMENT_REQUEST_EXPIRY_SECS,
            )
            paymentRequestDao.upsert(entity)
            _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
            _screen.value = Screen.PaymentRequests

            val delivered = client.sendPaymentRequest(
                requestId = requestId,
                requesterAddress = requesterAddress,
                payerAddress = payerAddress,
                amountSats = amountSats,
                memo = memo,
                requesterName = requesterName,
                pubkeyHex = pubkeyHex,
                signatureHex = signatureHex,
                timestamp = now,
            )
            if (delivered) {
                paymentRequestDao.markDelivered(requestId)
            }
            _success.value = "Payment request sent!"
        }
    }

    /** Build the canonical signing payload for a payment request. */
    private fun buildSignData(
        id: String,
        requesterAddress: String,
        payerAddress: String,
        amountSats: Long,
        memo: String,
        timestamp: Long,
    ): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(id.toByteArray(Charsets.UTF_8))
        buf.write(requesterAddress.toByteArray(Charsets.UTF_8))
        buf.write(payerAddress.toByteArray(Charsets.UTF_8))
        buf.write(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(amountSats).array())
        buf.write(memo.toByteArray(Charsets.UTF_8))
        buf.write(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(timestamp).array())
        return buf.toByteArray()
    }

    /** Mark pending payment requests as expired when their expiry time has passed. */
    private suspend fun expirePaymentRequests() {
        val now = System.currentTimeMillis() / 1000
        val expired = paymentRequestDao.getExpired(now)
        if (expired.isEmpty()) return
        for (entity in expired) {
            paymentRequestDao.updateStatus(id = entity.id, status = "cancelled", updatedAt = now)
        }
        _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
    }

    /** Retry delivering any outgoing payment requests that never reached the masternode. */
    private fun retryUndeliveredPaymentRequests() {
        val w = wallet ?: return
        val client = masternodeClient ?: return
        scope.launch {
            val undelivered = paymentRequestDao.getUndeliveredOutgoing()
            for (entity in undelivered) {
                // Re-check status before sending — a concurrent cancel may have run since we read the list
                val current = paymentRequestDao.getById(entity.id) ?: continue
                if (current.status != "pending") continue

                // Re-sign with the keypair for this requester address
                val addrIdx = contactDao.getByAddress(entity.requesterAddress)?.derivationIndex ?: 0
                val keypair = w.deriveKeypair(addrIdx)
                val signData = buildSignData(
                    entity.id, entity.requesterAddress, entity.payerAddress,
                    entity.amountSats, entity.memo, entity.createdAt,
                )
                val ok = client.sendPaymentRequest(
                    requestId = entity.id,
                    requesterAddress = entity.requesterAddress,
                    payerAddress = entity.payerAddress,
                    amountSats = entity.amountSats,
                    memo = entity.memo,
                    requesterName = entity.requesterName,
                    pubkeyHex = keypair.publicKeyBytes().toHexString(),
                    signatureHex = keypair.sign(signData).toHexString(),
                    timestamp = entity.createdAt,
                )
                if (ok) paymentRequestDao.markDelivered(entity.id)
            }
        }
    }

    /** Cancel an outgoing payment request. The payer is notified via WS, then the local record is deleted. */
    fun cancelPaymentRequest(requestId: String) {
        scope.launch {
            val client = masternodeClient
            // Notify masternode first (so payer gets the cancellation WS event), but always delete regardless
            val entity = paymentRequestDao.getById(requestId)
            if (client != null && entity != null && entity.delivered) {
                client.cancelPaymentRequest(requestId, entity.requesterAddress)
            }
            paymentRequestDao.deleteById(requestId)
            _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
        }
    }

    /** Show an incoming payment request on the review screen. */
    fun reviewPaymentRequest(request: PaymentRequest) {
        _selectedPaymentRequest.value = request
        _incomingPaymentRequest.value = null
        _screen.value = Screen.PaymentRequestReview
        // Notify the requester that this request has been opened
        scope.launch {
            val w = wallet ?: return@launch
            val client = masternodeClient ?: return@launch
            client.markPaymentRequestViewed(request.id, w.primaryAddress)
        }
    }

    /** Dismiss the incoming request notification without reviewing. */
    fun dismissIncomingRequest() {
        _incomingPaymentRequest.value = null
    }

    /**
     * Accept an incoming payment request: validate funds, send the transaction,
     * notify the requester, and update local state.
     */
    fun acceptPaymentRequest(requestId: String) {
        scope.launch {
            _loading.value = true
            try {
                val w = wallet ?: throw Exception("Wallet not loaded")
                val client = masternodeClient ?: throw Exception("Not connected")
                val entity = paymentRequestDao.getById(requestId)
                    ?: throw Exception("Payment request not found")

                val fee = FeeSchedule().calculateFee(entity.amountSats)
                val total = entity.amountSats + fee
                val balance = _balance.value
                if (balance.confirmed < total) {
                    throw Exception("Insufficient funds: need ${total / 100_000_000.0} TIME, have ${balance.confirmed / 100_000_000.0} TIME")
                }

                // Build and broadcast the transaction
                val tx = w.createTransaction(entity.requesterAddress, entity.amountSats)
                val txid = client.broadcastTransaction(tx)

                // Update local state
                paymentRequestDao.markPaid(
                    id = requestId,
                    txid = txid,
                    updatedAt = System.currentTimeMillis() / 1000,
                )
                _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
                _selectedPaymentRequest.value = _selectedPaymentRequest.value?.copy(
                    status = PaymentRequestStatus.Paid, paidTxid = txid
                )

                // Notify requester
                client.respondToPaymentRequest(
                    requestId = requestId,
                    payerAddress = entity.payerAddress,
                    accepted = true,
                )

                _success.value = "Payment sent! TxID: ${txid.take(16)}…"
                w.save(walletDir, currentPassword)
                refreshBalance()
                refreshTransactions()
                refreshUtxos()
                _screen.value = Screen.Overview
            } catch (e: Exception) {
                _error.value = "Failed to accept request: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    /** Decline an incoming payment request and notify the requester. */
    fun declinePaymentRequest(requestId: String) {
        scope.launch {
            val client = masternodeClient
            val entity = paymentRequestDao.getById(requestId) ?: return@launch
            paymentRequestDao.updateStatus(
                id = requestId,
                status = "declined",
                updatedAt = System.currentTimeMillis() / 1000,
            )
            _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
            _selectedPaymentRequest.value = null
            if (client != null) {
                client.respondToPaymentRequest(
                    requestId = requestId,
                    payerAddress = entity.payerAddress,
                    accepted = false,
                )
            }
            _screen.value = Screen.Overview
        }
    }

    private val _shouldExit = MutableStateFlow(false)
    val shouldExit: StateFlow<Boolean> = _shouldExit

    fun clearShouldExit() { _shouldExit.value = false }

    /**
     * Logout: flush any pending wallet save, disconnect, clear state, then
     * signal the Activity to finish so the process exits cleanly.
     */
    fun logout() {
        scope.launch {
            // Flush wallet to disk before clearing the in-memory key
            try {
                wallet?.save(walletDir, currentPassword)
            } catch (e: Exception) {
                Log.w(TAG, "logout: wallet save failed", e)
            }
            // Disconnect network
            masternodeClient?.close()
            masternodeClient = null
            wsClient?.stop()
            wsClient = null
            pollJob?.cancel()
            backgroundSyncJob?.cancel()
            pollJob = null
            // Clear sensitive state
            wallet = null
            currentPassword = null
            // Signal Activity to finish
            _shouldExit.value = true
        }
    }

    /**
     * Enroll biometrics using the currently loaded PIN. Calls [BiometricHelper.enroll]
     * on the activity so the fingerprint prompt is shown immediately.
     */
    fun enrollBiometric(activity: androidx.fragment.app.FragmentActivity, onResult: (Boolean) -> Unit) {
        val pin = currentPassword ?: run { onResult(false); return }
        BiometricHelper.enroll(activity, pin, onResult)
    }

    /** Lock the wallet: disconnect, clear sensitive state, return to PIN screen. */
    fun lockWallet() {
        Log.d(TAG, "lockWallet: disconnecting and clearing state")
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        backgroundSyncJob?.cancel()
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
        _transactionsSynced.value = false
        _screen.value = Screen.PinUnlock
    }

    /** Get the wallet file for export/sharing. */
    fun getWalletFile(): java.io.File? {
        val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
        return WalletManager.walletFile(walletDir, network)
    }

    /**
     * Change the wallet PIN. Verifies current PIN by attempting to load, then
     * re-saves with the new PIN. Returns true on success.
     */
    fun changePin(currentPin: String, newPin: String): Boolean {
        val w = wallet ?: return false
        // Verify current PIN matches the in-memory password
        if (currentPin != currentPassword) return false
        return try {
            w.save(walletDir, newPin)
            currentPassword = newPin
            // Re-enroll biometric with new PIN if available
            Log.d(TAG, "changePin: PIN changed successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "changePin failed", e)
            false
        }
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
        backgroundSyncJob?.cancel()
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
        _transactionsSynced.value = false
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
            _utxoSynced.value = false
            _transactionsSynced.value = false
            Log.d(TAG, "reindexWallet: clearing cached data and resyncing")
            try {
                // Clear DB caches
                transactionDao.deleteAll()
                consolidateCache.clear()
                sendRecipientCache.clear()
                fetchedTxLimit = INITIAL_TX_LIMIT

                // Reset in-memory state
                _balance.value = Balance()
                _transactions.value = emptyList()
                _utxos.value = emptyList()

                // Reset addresses to 1 so discovery re-scans from scratch
                // and only adds back addresses with actual history
                val w = wallet
                if (w != null) {
                    Log.d(TAG, "Trimming ${w.getAddresses().size} addresses to 1 for rediscovery")
                    w.trimAddresses(1)
                    _addresses.value = w.getAddresses()
                }

                // Resync from masternode — wait for each step to complete
                val client = masternodeClient
                if (client != null) {
                    refreshBalanceSync()
                    refreshTransactionsSync()
                    refreshUtxosSync()
                    discoverAddresses()
                    _success.value = "Wallet reindexed successfully"
                    startBackgroundSync()
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
        backgroundSyncJob?.cancel()
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
            _transactionsSynced.value = false
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
    fun getConfigText(): String = ConfigManager.readRaw(walletDir, _isTestnet.value)

    /** Save raw time.conf content. */
    fun saveConfigText(content: String) {
        ConfigManager.writeRaw(walletDir, content, _isTestnet.value)
        _success.value = "Configuration saved"
    }

    fun reconnect() {
        masternodeClient?.close()
        masternodeClient = null
        wsClient?.stop()
        wsClient = null
        pollJob?.cancel()
        backgroundSyncJob?.cancel()
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
        backgroundSyncJob?.cancel()
                pollJob = null
                _connectedPeer.value = null
                _wsConnected.value = false

                val config = ConfigManager.load(walletDir, _isTestnet.value)
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
        backgroundSyncJob?.cancel()
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
        _transactionsSynced.value = false
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
        backgroundSyncJob?.cancel()
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
    ChangePin,
    PaymentRequest,
    PaymentRequestQrScanner,
    PaymentRequestReview,
    PaymentRequests,
}
