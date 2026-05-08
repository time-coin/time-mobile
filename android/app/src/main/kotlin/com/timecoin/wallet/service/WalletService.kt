package com.timecoin.wallet.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.timecoin.wallet.R
import com.timecoin.wallet.TimeCoinWalletApp
import com.timecoin.wallet.ui.MainActivity
import com.timecoin.wallet.ui.component.formatSatoshis
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
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                // Prevent uncaught coroutine exceptions (e.g. network ProtocolException)
                // from crashing the app process. All call sites already log errors locally;
                // this is a last-resort safety net.
                android.util.Log.e(TAG, "Uncaught exception in WalletService scope", throwable)
            }
    )

    // Separate databases mirror the wallet file layout:
    // mainnet → {filesDir}/wallet.db, testnet → {filesDir}/testnet/wallet.db
    private val mainnetDb by lazy {
        val dbFile = java.io.File(context.filesDir, "wallet.db")
        Room.databaseBuilder(context, WalletDatabase::class.java, dbFile.absolutePath)
            .fallbackToDestructiveMigration().build()
    }
    private val testnetDb by lazy {
        val testnetDir = java.io.File(context.filesDir, "testnet").also { it.mkdirs() }
        val dbFile = java.io.File(testnetDir, "wallet.db")
        Room.databaseBuilder(context, WalletDatabase::class.java, dbFile.absolutePath)
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
        private const val NOTIF_ID_RECEIVE = 1001
    }

    private fun showReceiveNotification(amountSats: Long) {
        val notifManager = context.getSystemService(NotificationManager::class.java)
        // Check permission (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!notifManager.areNotificationsEnabled()) return
        }
        val tapIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dp = _decimalPlaces.value
        val amountText = formatSatoshis(amountSats, dp)
        val notification = NotificationCompat.Builder(context, TimeCoinWalletApp.CHANNEL_TRANSACTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TIME Received")
            .setContentText("+$amountText TIME")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
        notifManager.notify(NOTIF_ID_RECEIVE, notification)

        // Also play a direct tone so sound works regardless of notification channel settings
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME)
                .startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
        } catch (_: Exception) { }
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
    private var bgSyncOffset = 0
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

    private val _manualRefreshing = MutableStateFlow(false)
    val manualRefreshing: StateFlow<Boolean> = _manualRefreshing

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

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled

    private val _selectedTransaction = MutableStateFlow<TransactionRecord?>(null)
    val selectedTransaction: StateFlow<TransactionRecord?> = _selectedTransaction

    private val _reindexing = MutableStateFlow(false)
    val reindexing: StateFlow<Boolean> = _reindexing

    private val _discoveringAddresses = MutableStateFlow(false)
    val discoveringAddresses: StateFlow<Boolean> = _discoveringAddresses

    private val _restoreMode = MutableStateFlow(false)
    val restoreMode: StateFlow<Boolean> = _restoreMode

    fun setRestoreMode(restore: Boolean) { _restoreMode.value = restore }

    // ── UTXO consolidation ──
    private val _consolidating = MutableStateFlow(false)
    val consolidating: StateFlow<Boolean> = _consolidating
    private val _consolidationStatus = MutableStateFlow<String?>(null)
    val consolidationStatus: StateFlow<String?> = _consolidationStatus
    @Volatile private var consolidationCancelled = false

    fun consolidateUtxos() {
        val w = wallet ?: run { _error.value = "Wallet not loaded"; return }
        val client = masternodeClient ?: run { _error.value = "Not connected"; return }
        if (_consolidating.value) return

        consolidationCancelled = false
        _consolidating.value = true
        _consolidationStatus.value = "Fetching UTXOs…"

        scope.launch {
            try {
                val addresses = w.getAddresses()
                var totalBatches = 0
                var doneBatches = 0
                var totalConsolidated = 0

                // Fetch fresh UTXOs per address from masternode
                data class AddrUtxos(val address: String, val utxos: List<com.timecoin.wallet.model.Utxo>)
                val addrUtxosList = addresses.mapNotNull { addr ->
                    try {
                        val utxos = client.getUtxos(listOf(addr))
                            .filter { it.spendable }
                            .sortedBy { it.amount }  // smallest first — consolidate dust first
                        if (utxos.size > 1) AddrUtxos(addr, utxos) else null
                    } catch (e: Exception) {
                        Log.w(TAG, "consolidate: getUtxos failed for $addr: ${e.message}")
                        null
                    }
                }

                val batchSize = 50
                totalBatches = addrUtxosList.sumOf { (it.utxos.size + batchSize - 1) / batchSize }

                if (totalBatches == 0) {
                    _consolidationStatus.value = "Nothing to consolidate — already 1 UTXO or fewer per address."
                    return@launch
                }

                for ((addr, utxos) in addrUtxosList) {
                    for (chunk in utxos.chunked(batchSize)) {
                        if (consolidationCancelled) {
                            _consolidationStatus.value = "Cancelled after $doneBatches batch(es)."
                            return@launch
                        }
                        doneBatches++
                        _consolidationStatus.value = "Batch $doneBatches / $totalBatches…"
                        try {
                            val total = chunk.sumOf { it.amount }
                            val fee = com.timecoin.wallet.model.FeeSchedule().calculateFee(total)
                            val net = total - fee
                            if (net <= 0) continue

                            val addrIdx = w.getAddresses().indexOf(addr)
                            val keypair = if (addrIdx >= 0) w.deriveKeypair(addrIdx) else w.deriveKeypair(0)

                            val tx = com.timecoin.wallet.model.Transaction()
                            val destAddr = Address.fromString(addr)
                            for (utxo in chunk) {
                                tx.addInput(com.timecoin.wallet.model.TxInput.new(utxo.txid.hexToByteArray(), utxo.vout))
                            }
                            tx.addOutput(com.timecoin.wallet.model.TxOutput.new(net, destAddr))
                            tx.signAll(keypair)

                            client.broadcastTransaction(tx)
                            totalConsolidated += chunk.size
                        } catch (e: Exception) {
                            Log.w(TAG, "consolidate: batch $doneBatches failed: ${e.message}")
                        }
                    }
                }
                _consolidationStatus.value = "Done — consolidated $totalConsolidated UTXOs."
                refreshBalance()
                refreshUtxos()
            } catch (e: Exception) {
                _consolidationStatus.value = "Failed: ${e.message}"
                Log.e(TAG, "consolidateUtxos failed", e)
            } finally {
                _consolidating.value = false
            }
        }
    }

    fun cancelConsolidation() {
        consolidationCancelled = true
    }

    // ── Block reward breakdown ──
    private val _blockRewardBreakdown = MutableStateFlow<BlockRewardBreakdown?>(null)
    val blockRewardBreakdown: StateFlow<BlockRewardBreakdown?> = _blockRewardBreakdown
    private val _blockRewardBreakdownLoading = MutableStateFlow(false)
    val blockRewardBreakdownLoading: StateFlow<Boolean> = _blockRewardBreakdownLoading

    fun fetchBlockRewardBreakdown(blockHeight: Long) {
        val client = masternodeClient ?: return
        scope.launch {
            _blockRewardBreakdownLoading.value = true
            try {
                val bd = client.getBlockRewardBreakdown(blockHeight)
                _blockRewardBreakdown.value = bd
            } catch (e: Exception) {
                Log.w(TAG, "fetchBlockRewardBreakdown failed: ${e.message}")
            } finally {
                _blockRewardBreakdownLoading.value = false
            }
        }
    }

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
    private var networkJob: Job? = null   // tracks connectToNetwork coroutine
    private var discoveryJob: Job? = null // tracks trimEmptyAddresses + discoverAddresses coroutine
    private var pendingMnemonic: String? = null
    @Volatile private var networkSession = 0  // incremented on each network switch; stale coroutines check this

    private val prefs by lazy {
        context.getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE)
    }

    private fun saveNetworkPref(isTestnet: Boolean) {
        prefs.edit().putBoolean("is_testnet", isTestnet).apply()
    }

    private val walletDir get() = context.filesDir

    // ── Wallet lifecycle ──

    init {
        scope.launch {
            val saved = settingDao.get("decimal_places")
            if (saved != null) _decimalPlaces.value = saved.toIntOrNull() ?: 2
            val notifSaved = settingDao.get("notifications_enabled")
            if (notifSaved != null) _notificationsEnabled.value = notifSaved != "false"
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

    fun setNotificationsEnabled(enabled: Boolean) {
        _notificationsEnabled.value = enabled
        scope.launch {
            settingDao.set(com.timecoin.wallet.db.SettingEntity("notifications_enabled", enabled.toString()))
        }
    }

    fun checkExistingWallet() {
        // Migrate legacy flat testnet file to subdirectory
        WalletManager.migrateIfNeeded(walletDir)

        val mainExists = WalletManager.exists(walletDir, NetworkType.Mainnet)
        val testExists = WalletManager.exists(walletDir, NetworkType.Testnet)
        if (mainExists || testExists) {
            // Restore last-used network; fall back to whichever wallet exists
            val savedTestnet = prefs.getBoolean("is_testnet", false)
            val network = when {
                savedTestnet && testExists -> NetworkType.Testnet
                !savedTestnet && mainExists -> NetworkType.Mainnet
                testExists -> NetworkType.Testnet
                else -> NetworkType.Mainnet
            }
            _isTestnet.value = network == NetworkType.Testnet
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
        saveNetworkPref(isTestnet)
        _screen.value = Screen.MnemonicSetup
    }

    /** Store mnemonic temporarily and navigate to PIN setup. */
    fun setPendingMnemonic(mnemonic: String) {
        pendingMnemonic = mnemonic
        _restoreMode.value = false
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
                _addresses.value = listOf(wallet!!.primaryAddress)
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
                saveNetworkPref(network == NetworkType.Testnet)
                _walletLoaded.value = true
                _addresses.value = listOf(wallet!!.primaryAddress)
                Log.d(TAG, "loadWallet: exposing primary address only until discovery runs")
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
        networkJob?.cancel()
        discoveryJob?.cancel()
        networkJob = scope.launch networkJob@{
            val session = networkSession
            try {
                val isTestnet = _isTestnet.value
                val network = if (isTestnet) NetworkType.Testnet else NetworkType.Mainnet
                Log.d(TAG, "Connecting to network, isTestnet=$isTestnet")

                // Load manual config (testnet config lives in testnet/ subdirectory)
                val config = ConfigManager.load(walletDir, isTestnet)
                val manualEndpoints = ConfigManager.manualEndpoints(config)
                val rpcCreds = ConfigManager.rpcCredentials(config)

                // Fast path: try last successful peer before running full discovery.
                // This gets balance on screen quickly while discovery runs in background.
                val lastPeer = settingDao.get("last_peer")
                if (lastPeer != null && session == networkSession) {
                    try {
                        if (tryConnect(lastPeer, rpcCreds)) {
                            Log.d(TAG, "connectToNetwork: fast path via cached peer $lastPeer")
                            scope.launch {
                                if (session != networkSession) return@launch
                                val rankedPeers = PeerDiscovery.discoverAndRank(
                                    isTestnet = isTestnet,
                                    manualEndpoints = manualEndpoints,
                                    credentials = rpcCreds,
                                    cacheDir = walletDir,
                                    expectedGenesisHash = network.genesisHash,
                                )
                                if (session != networkSession) return@launch
                                _peerInfos.value = rankedPeers.map {
                                    it.copy(isActive = it.endpoint == lastPeer)
                                }
                                _peers.value = rankedPeers.map { it.endpoint }
                            }
                            return@networkJob
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "connectToNetwork: cached peer $lastPeer unavailable, starting full discovery")
                    }
                }

                // Discover and rank all peers (parallel probe + gossip + consensus)
                val rankedPeers = PeerDiscovery.discoverAndRank(
                    isTestnet = isTestnet,
                    manualEndpoints = manualEndpoints,
                    credentials = rpcCreds,
                    cacheDir = walletDir,
                    expectedGenesisHash = network.genesisHash,
                )

                _peerInfos.value = rankedPeers
                _peers.value = rankedPeers.map { it.endpoint }

                if (session != networkSession) return@networkJob  // switched network while discovering

                if (rankedPeers.isEmpty()) {
                    _error.value = "No masternodes found"
                    return@networkJob
                }

                Log.d(TAG, "Ranked ${rankedPeers.size} peers, " +
                    "${rankedPeers.count { it.isHealthy }} healthy")

                // Connect to the best healthy peer
                val healthyPeers = rankedPeers.filter { it.isHealthy }
                if (healthyPeers.isEmpty()) {
                    _error.value = "No healthy masternodes found"
                    return@networkJob
                }

                for (peer in healthyPeers) {
                    if (session != networkSession) return@networkJob  // switched network mid-connect
                    if (tryConnect(peer.endpoint, rpcCreds)) {
                        // Mark the active peer
                        _peerInfos.value = rankedPeers.map {
                            it.copy(isActive = it.endpoint == peer.endpoint)
                        }
                        return@networkJob
                    }
                }

                Log.e(TAG, "Could not connect to any masternode")
                _error.value = "Could not connect to any masternode"
            } catch (e: CancellationException) {
                // Job cancelled (network switch) — don't show an error
                Log.d(TAG, "connectToNetwork cancelled (network switched)")
                throw e
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
            val network = if (_isTestnet.value) NetworkType.Testnet else NetworkType.Mainnet
            val genesisHash = client.getGenesisHash()
            if (genesisHash != network.genesisHash) {
                Log.w(TAG, "Genesis hash mismatch at $url: expected ${network.genesisHash}, got $genesisHash")
                client.close()
                PeerDiscovery.blacklistPeer(url)
                PeerDiscovery.clearPeerCache(_isTestnet.value, walletDir)
                return false
            }
            masternodeClient = client
            _health.value = h
            _connectedPeer.value = url
            settingDao.set(SettingEntity("last_peer", url))
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
            discoveryJob?.cancel()
            _discoveringAddresses.value = true
            discoveryJob = scope.launch {
                try {
                    val session = networkSession
                    trimEmptyAddresses(client)
                    if (session != networkSession) return@launch
                    discoverAddresses() // calls refreshTransactionsSync/refreshUtxosSync with markSynced=true at end
                } finally {
                    _discoveringAddresses.value = false
                }
            }

            startBackgroundSync()
            registerAddressPubkeys()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Peer $url failed: ${e.message}")
            false
        }
    }

    /**
     * Register Ed25519 public keys for all owned addresses with the connected masternode.
     * This enables the node to encrypt memos to wallet addresses before transactions arrive,
     * matching the desktop wallet's behavior (wallet-gui/src/service.rs).
     * Runs in the background and skips addresses that are already registered.
     */
    private fun registerAddressPubkeys() {
        scope.launch {
            val w = wallet ?: return@launch
            val client = masternodeClient ?: return@launch
            for (address in w.getAddresses()) {
                try {
                    val existing = client.getAddressPubkey(address)
                    val pubkeyHex = w.getPublicKeyHex(address) ?: continue
                    if (existing == pubkeyHex) continue  // already registered
                    val ok = client.registerAddressPubkey(address, pubkeyHex)
                    if (ok) Log.d(TAG, "registerPubkeys: registered $address")
                } catch (e: Exception) {
                    Log.w(TAG, "registerPubkeys: failed for $address: ${e.message}")
                }
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
                        // Push notification for incoming coins (not sends)
                        if (!isSend && _notificationsEnabled.value) {
                            showReceiveNotification(notif.amount)
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
     * After the initial sync shows "Verified", progressively download the full
     * transaction history using offset-based pagination (limit=100 per call so
     * the masternode never has to return a huge response). Each page is upserted
     * into Room and the UI is refreshed from the DB so the list grows in place.
     * The offset is persisted in settings so subsequent sessions resume where
     * they left off instead of re-downloading everything.
     */
    private fun startBackgroundSync() {
        backgroundSyncJob?.cancel()
        backgroundSyncJob = scope.launch {
            // Wait until the initial sync is verified (timeout after 2 min)
            withTimeoutOrNull(120_000) { transactionsSynced.first { it } }
                ?: run { Log.w(TAG, "backgroundSync: timed out waiting for initial sync"); return@launch }

            // Restore saved offset so we resume across sessions
            bgSyncOffset = settingDao.get("bg_sync_offset")?.toIntOrNull() ?: INITIAL_TX_LIMIT
            Log.d(TAG, "backgroundSync: starting at offset=$bgSyncOffset")

            while (isActive) {
                delay(500) // pace requests — short gap to avoid overwhelming the masternode
                try {
                    val w = wallet ?: break
                    val client = masternodeClient ?: break
                    Log.d(TAG, "backgroundSync: fetching limit=$TX_PAGE_SIZE offset=$bgSyncOffset")

                    val rawTxs = client.getTransactionsMulti(w.getAddresses(), TX_PAGE_SIZE, bgSyncOffset)

                    if (rawTxs.isEmpty()) {
                        Log.d(TAG, "backgroundSync: complete (no more transactions)")
                        _hasMoreTransactions.value = false
                        break
                    }

                    val processed = expandAndProcess(rawTxs, w, client)
                    cacheTransactionsToDb(processed)
                    _transactions.value = transactionDao.getAll().map { it.toTransactionRecord() }
                    bgSyncOffset += rawTxs.size
                    settingDao.set(SettingEntity("bg_sync_offset", bgSyncOffset.toString()))
                    Log.d(TAG, "backgroundSync: cached ${processed.size} transactions, offset now $bgSyncOffset")

                    if (rawTxs.size < TX_PAGE_SIZE) {
                        Log.d(TAG, "backgroundSync: complete (partial page, full history loaded)")
                        _hasMoreTransactions.value = false
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "backgroundSync: batch failed, retrying", e)
                    delay(10_000)
                }
            }
        }
    }

    // ── Actions ──

    fun manualRefresh() {
        scope.launch {
            _manualRefreshing.value = true
            try {
                refreshBalanceSync()
                refreshTransactionsSync()
                refreshUtxosSync()
            } catch (e: Exception) {
                Log.e(TAG, "manualRefresh failed", e)
            } finally {
                _manualRefreshing.value = false
            }
        }
    }

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
        // Always fetch the most recent page (offset=0) to keep recent statuses/confirmations fresh.
        // Background sync handles older history separately.
        val rawTxs = client.getTransactionsMulti(w.getAddresses(), INITIAL_TX_LIMIT)
        Log.d(TAG, "refreshTransactions: ${rawTxs.size} raw entries")

        val processed = expandAndProcess(rawTxs, w, client)
        cacheTransactionsToDb(processed)

        // Purge ghost transactions: pending txids in the DB that the node no longer knows about.
        // This covers broadcast failures, dropped mempool entries, and censorship attempts.
        // Transactions confirmed via TimeVote (finalized=true) are upgraded to "approved" —
        // in TIME Coin, TimeVote consensus IS confirmation; block archival is optional/eventual.
        val freshTxids = rawTxs.map { it.txid }.toSet()
        val pendingTxids = transactionDao.getPendingTxids()
        val possiblyGhost = pendingTxids.filter { it !in freshTxids }
        for (txid in possiblyGhost) {
            try {
                val detail = client.getTransactionDetail(txid)
                if (detail.finalized) {
                    // TimeVote consensus reached — this is confirmed in TIME Coin protocol
                    Log.i(TAG, "Tx ${txid.take(12)}… is TimeVote-finalized — marking approved")
                    transactionDao.updateStatusByTxid(txid, "approved")
                }
                // Otherwise still pending on node — leave it
            } catch (e: MasternodeException) {
                if (e.message?.contains("RPC error -5") == true ||
                    e.message?.contains("No information available") == true) {
                    Log.w(TAG, "Ghost tx ${txid.take(12)}… not found on node — removing")
                    transactionDao.deleteByTxid(txid)
                }
                // Other errors (network timeout etc.) — leave pending, try again next refresh
            } catch (_: Exception) {
                // Network issue — leave pending
            }
        }

        // Reload from DB so any background-synced history is preserved in the display list
        _transactions.value = transactionDao.getAll().map { it.toTransactionRecord() }
        if (markSynced) _transactionsSynced.value = true
    }

    /** Restart background sync if it has stopped (e.g. after a connection error). */
    fun loadMoreTransactions() {
        if (_hasMoreTransactions.value && backgroundSyncJob?.isActive != true) {
            startBackgroundSync()
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
                        val entries = listOf(
                            // Send entry
                            TransactionRecord(
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
                            ),
                            // Receive entry — self-send, coins land at own address
                            TransactionRecord(
                                txid = ctx.txid,
                                vout = main.index,
                                isSend = false,
                                address = main.address,
                                amount = main.value,
                                fee = 0,
                                timestamp = ctx.timestamp,
                                status = ctx.status,
                                blockHash = ctx.blockHash,
                                blockHeight = ctx.blockHeight,
                                confirmations = ctx.confirmations,
                            ),
                        )
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
        // filter out change send entries.
        // selfSendTxids: txids where ALL send entries go to own addresses.
        // This covers two cases:
        //   (a) Normal send where masternode reports the change address as the
        //       destination — expandAndProcess corrects this before we get here.
        //   (b) Genuine self-send (user sent to their own address) — after
        //       expandAndProcess the send entry still points to an own address
        //       because there is no external recipient to correct to.
        // We track selfSendTxids to preserve the matching receive entry (b).
        val selfSendTxids = mutableSetOf<String>()
        val realSendVouts = mutableMapOf<String, MutableSet<Int>>() // txid → real send vouts
        for ((txid, sends) in sendsByTxid) {
            val externalDests = sends.filter { it.address !in ownAddresses }
            if (externalDests.isNotEmpty()) {
                realSendVouts[txid] = externalDests.map { it.vout }.toMutableSet()
                Log.d(TAG, "  SEND txid=${txid.take(12)}.. → ${externalDests.size} external dests")
            } else {
                // All send entries point to own addresses — potential self-send.
                selfSendTxids.add(txid)
                realSendVouts[txid] = sends.map { it.vout }.toMutableSet()
                Log.d(TAG, "  SEND txid=${txid.take(12)}.. all dests are own (self-send or masternode change-addr reporting)")
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

            // Receive to our own address for a txid we sent.
            // Keep it if this is a genuine self-send and the receive amount
            // matches the send amount (same logic as the GUI wallet).
            if (tx.txid in selfSendTxids) {
                val sendAmount = sendsByTxid[tx.txid]?.firstOrNull()?.amount
                if (sendAmount != null && tx.amount == sendAmount) {
                    Log.d(TAG, "Keeping self-send receive: txid=${tx.txid.take(12)}.. amount=${tx.amount}")
                    return@filter true
                }
            }
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
                        memo = tx.memo,
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

    fun sendTransaction(toAddress: String, amount: Long, fromAddress: String? = null) {
        scope.launch {
            _loading.value = true
            try {
                val w = wallet ?: throw Exception("Wallet not loaded")
                val client = masternodeClient ?: throw Exception("Not connected")

                val tx = w.createTransaction(toAddress, amount, fromAddress = fromAddress)
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
        val currentShown = _addresses.value.toSet()
        val allWalletAddrs = w.getAddresses()

        // Consume a pre-generated address from the pool first (handles wallets created
        // before the pre-generation was removed), otherwise generate a truly new one.
        val poolAddr = allWalletAddrs.firstOrNull { it !in currentShown }
        val addr: String
        val newIndex: Int
        if (poolAddr != null) {
            addr = poolAddr
            newIndex = allWalletAddrs.indexOf(poolAddr)
        } else {
            addr = w.generateAddress()
            newIndex = w.getAddresses().size - 1
        }

        _addresses.value = _addresses.value + addr

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

        // Update display — only show addresses with activity + primary; never trim wallet file
        _addresses.value = addrs.filterIndexed { idx, _ -> idx == 0 || idx in activeIndices }
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
    /**
     * BIP-44 address discovery: scans from index 0 in windows of GAP_LIMIT.
     * Stops when an entire window has no transaction or UTXO activity.
     * Only addresses with blockchain history are kept; the wallet is trimmed
     * to the last active index + 1 so keys are preserved for signing.
     * _addresses is updated to show only funded/active addresses in the UI.
     */
    private suspend fun discoverAddresses() {
        val w = wallet ?: return
        val client = masternodeClient ?: return
        val gapLimit = WalletManager.GAP_LIMIT
        val session = networkSession

        Log.d(TAG, "BIP-44 address discovery starting from index 0")
        val activeIndices = mutableSetOf<Int>()

        var windowStart = 0
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

                windowAddrs.forEachIndexed { i, addr ->
                    if (addr in activeAddrs) {
                        Log.d(TAG, "Active address[${windowIndices[i]}]: ${addr.take(16)}..")
                        activeIndices.add(windowIndices[i])
                    }
                }

                // Stop when a full window returns no activity
                if (activeAddrs.isEmpty()) break

                // Abort if network was switched while we were scanning
                if (session != networkSession) return

                // Advance window past the last active address found
                windowStart = activeIndices.max() + 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Discovery window [$windowStart..${windowStart + gapLimit - 1}] failed: ${e.message}")
                break
            }
        }

        // Always include primary address (index 0) even if it has no history
        activeIndices.add(0)

        // Expand wallet to cover newly discovered addresses — never reduce nextAddressIndex
        val targetCount = activeIndices.max() + 1
        while (w.getAddresses().size < targetCount) w.generateAddress()

        // Register active addresses as owned contacts
        for (idx in activeIndices.sorted()) {
            val addr = w.getAddresses()[idx]
            val existing = contactDao.getByAddress(addr)
            if (existing == null) {
                contactDao.upsert(ContactEntity(
                    address = addr,
                    label = if (idx == 0) "Primary" else "Address ${idx + 1}",
                    isOwned = true,
                    derivationIndex = idx,
                ))
            }
        }

        Log.d(TAG, "Discovery complete: ${activeIndices.size} active addresses (indices ${activeIndices.sorted()})")

        // Bail out if the network was switched during discovery — don't write to wrong DB
        if (session != networkSession) return

        w.save(walletDir, currentPassword)
        _contacts.value = contactDao.getAll()

        // Full sync with discovered address set, then show only funded addresses
        refreshBalanceSync()
        refreshTransactionsSync()
        refreshUtxosSync()
        updateDisplayedAddresses()
    }

    /** Update _addresses to only show addresses that have UTXOs or transaction history. */
    private fun updateDisplayedAddresses() {
        val w = wallet ?: return
        val utxoAddrs = _utxos.value.map { it.address }.toSet()
        val txAddrs = _transactions.value.map { it.address }.toSet()
        val primary = w.getAddresses().firstOrNull()
        _addresses.value = w.getAddresses().filter { it in utxoAddrs || it in txAddrs || it == primary }
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

    /**
     * Hide an owned address from the receive list by deleting its contact entry.
     * The address is NOT removed from the wallet file — its key is still derived
     * from the mnemonic. It will reappear automatically if:
     *   - a WS TransactionReceived event arrives for it, or
     *   - discoverAddresses() finds it has on-chain activity next time it runs.
     * The primary address (index 0) cannot be deleted.
     */
    fun deleteOwnedAddress(address: String) {
        val w = wallet ?: return
        if (address == w.primaryAddress) return  // primary address is permanent
        scope.launch {
            contactDao.deleteByAddress(address)
            _contacts.value = contactDao.getAll()
            _addresses.value = _addresses.value.filter { it != address }
        }
    }

    fun navigateTo(screen: Screen) { _screen.value = screen }
    fun showTransaction(tx: TransactionRecord) {
        _selectedTransaction.value = tx
        _screen.value = Screen.TransactionDetail
    }
    /** Navigate to transaction detail by txid; no-op if not found in the cached list. */
    fun showTransactionByTxid(txid: String) {
        val tx = _transactions.value.firstOrNull { it.txid == txid } ?: return
        showTransaction(tx)
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

    /** Delete a resolved (declined / cancelled / paid) payment request from local history. */
    fun deletePaymentRequest(requestId: String) {
        scope.launch {
            paymentRequestDao.deleteById(requestId)
            _paymentRequests.value = paymentRequestDao.getAll().map { it.toPaymentRequest() }
        }
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

                // Attach the payment request memo to the send row in the DB so it
                // shows in transaction history even though it's not on-chain.
                if (entity.memo.isNotBlank()) {
                    val sendRows = transactionDao.getAll()
                        .filter { it.txid == txid && it.isSend && !it.isFee && it.memo.isBlank() }
                    for (row in sendRows) {
                        transactionDao.updateMemo(txid, row.vout, true, false, entity.memo)
                    }
                    _transactions.value = _transactions.value.map {
                        if (it.txid == txid && it.isSend && !it.isFee && it.memo.isBlank())
                            it.copy(memo = entity.memo) else it
                    }
                }

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
        val pin = currentPassword ?: run {
            _error.value = "Cannot enable biometric unlock: please lock and unlock the wallet with your PIN first."
            onResult(false)
            return
        }
        BiometricHelper.enroll(activity, pin) { success ->
            if (!success) _error.value = "Biometric enrollment failed. Please try again."
            onResult(success)
        }
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
                settingDao.delete("bg_sync_offset")
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
                settingDao.delete("bg_sync_offset")
                consolidateCache.clear()
                sendRecipientCache.clear()
                bgSyncOffset = INITIAL_TX_LIMIT

                // Reset in-memory state
                _balance.value = Balance()
                _transactions.value = emptyList()
                _utxos.value = emptyList()

                // Resync from masternode — wait for each step to complete.
                // If the current node is unresponsive, reconnect first to pick a healthy peer.
                if (masternodeClient != null) {
                    try {
                        refreshBalanceSync()
                        refreshTransactionsSync()
                        refreshUtxosSync()
                        discoverAddresses()
                        _success.value = "Wallet reindexed successfully"
                        startBackgroundSync()
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "reindexWallet: current peer failed (${e.message}), reconnecting to find healthy peer")
                        masternodeClient = null
                    }
                }
                // Reconnect — discoverAddresses is called at the end of tryConnect
                _error.value = "Reindex: reconnecting to masternode…"
                connectToNetwork()
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
                settingDao.delete("bg_sync_offset")
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

        // Disconnect current network and reset all state regardless of target wallet existence
        networkSession++          // invalidate any in-flight coroutines from the old network
        networkJob?.cancel()
        networkJob = null
        discoveryJob?.cancel()
        discoveryJob = null
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
        _balance.value = Balance()
        _transactions.value = emptyList()
        _utxos.value = emptyList()
        _addresses.value = emptyList()
        _contacts.value = emptyList()
        _paymentRequests.value = emptyList()
        _utxoSynced.value = false
        _transactionsSynced.value = false
        _walletLoaded.value = false
        wallet = null
        _isTestnet.value = toTestnet
        saveNetworkPref(toTestnet)

        // No wallet for target network — go to welcome screen to create/restore
        if (!WalletManager.exists(walletDir, targetNetwork)) {
            _screen.value = Screen.Welcome
            return
        }

        // Load wallet for target network (encrypted → need PIN)
        val encrypted = WalletManager.isEncrypted(walletDir, targetNetwork)
        if (encrypted) {
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
