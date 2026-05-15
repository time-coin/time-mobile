import Foundation
import Combine
import KeychainSwift

// Central state machine — equivalent to Android WalletService.
// All views read @Published properties and call methods on this object.
@MainActor
class WalletService: ObservableObject {

    enum Screen {
        case welcome, networkSelect, mnemonicSetup, mnemonicConfirm, pinSetup, pinUnlock
        case passwordUnlock, overview, send, qrScanner, receive, transactions
        case transactionDetail, connections, settings, changePin
        case paymentRequest, paymentRequestQrScanner, paymentRequestReview, paymentRequests
    }

    // MARK: - Published state (mirrors Android WalletService flows)

    @Published var screen: Screen = .welcome
    @Published var balance: Balance = .zero
    @Published var transactions: [TransactionRecord] = []
    @Published var utxos: [Utxo] = []
    @Published var addresses: [String] = []
    @Published var contacts: [Contact] = []
    @Published var health: HealthStatus? = nil
    @Published var wsConnected: Bool = false
    @Published var connectedPeer: PeerInfo? = nil
    @Published var reindexing: Bool = false
    @Published var error: String? = nil
    @Published var success: String? = nil
    @Published var paymentRequests: [PaymentRequest] = []
    @Published var incomingPaymentRequest: IncomingPaymentRequest? = nil
    @Published var consolidating: Bool = false
    @Published var consolidationStatus: String = ""
    @Published var blockRewardBreakdown: BlockRewardBreakdown? = nil
    @Published var blockRewardBreakdownLoading: Bool = false
    @Published var restoreMode: Bool = false
    @Published var hasMoreTransactions: Bool = false
    @Published var loadingMore: Bool = false
    @Published var discoveringAddresses: Bool = false
    @Published var decimalPlaces: Int = 8
    @Published var notificationsEnabled: Bool = true

    // MARK: - Private state

    @Published private(set) var isTestnet: Bool = false
    private var wallet: WalletManager?
    private var db: WalletDatabase?
    private var masternode: MasternodeClient?
    private var wsClient: WsNotificationClient?
    @Published var peers: [PeerInfo] = []
    private var selectedTxid: String? = nil
    var selectedTransaction: TransactionRecord? = nil

    // Used by MnemonicSetupView → MnemonicConfirmView handoff
    var pendingMnemonic: String = ""
    // Set by QR scanner; read and cleared by SendView / PaymentRequestView
    var scannedAddress: String = ""

    private let keychain = KeychainSwift()
    private var cachedPassword: String? = nil
    private var syncTask: Task<Void, Never>?
    private var discoveryTask: Task<Void, Never>?
    private var pollTask: Task<Void, Never>?
    private var bgSyncTask: Task<Void, Never>?
    private var bgSyncOffset: Int = 0
    private var transactionsSynced: Bool = false
    private var consolidationCancelled: Bool = false
    private let rateLimitMax = 3
    private let rateLimitWindowSecs: Int64 = 3600
    private let paymentRequestExpirySecs: Int64 = 86400

    private var filesDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private var currentDbDir: URL {
        isTestnet ? filesDir.appendingPathComponent("testnet") : filesDir
    }

    // MARK: - Startup

    func start() {
        NotificationManager.requestPermission()
        WalletManager.migrateIfNeeded(baseDir: filesDir)

        let network: NetworkType = isTestnet ? .testnet : .mainnet
        if WalletManager.exists(dir: filesDir, network: network) {
            let encrypted = WalletManager.isEncrypted(dir: filesDir, network: network)
            screen = encrypted ? .pinUnlock : .overview
            if !encrypted { loadWalletUnlocked(password: nil) }
        } else {
            screen = .welcome
        }
    }

    // MARK: - Wallet lifecycle

    func createWallet(mnemonic: String, password: String?) async {
        do {
            let network: NetworkType = isTestnet ? .testnet : .mainnet
            let w = try WalletManager.create(mnemonic: mnemonic, network: network)
            try w.save(dir: filesDir, password: password)
            wallet = w
            cachedPassword = password
            if let password { saveWalletPassword(password) }
            pendingMnemonic = ""
            initDb()
            try await syncOwnedAddresses()
            await connectAndSync()
            screen = .overview
        } catch {
            self.error = error.localizedDescription
        }
    }

    func unlockWithPassword(_ password: String) async {
        // Treat empty string same as nil for unencrypted wallets
        let effectivePassword: String? = password.isEmpty ? nil : password
        do {
            let network: NetworkType = isTestnet ? .testnet : .mainnet
            let w = try WalletManager.load(dir: filesDir, network: network, password: effectivePassword)
            wallet = w
            cachedPassword = effectivePassword
            if let effectivePassword { saveWalletPassword(effectivePassword) }
            initDb()
            try await syncOwnedAddresses()
            await connectAndSync()
            screen = .overview
        } catch {
            self.error = "Wrong password"
        }
    }

    func unlockWithPin(_ pin: String) async {
        let key = pinKeychainKey()
        guard let stored = keychain.get(key), stored == pin else {
            error = "Wrong PIN"
            return
        }
        let password = keychain.get(walletPasswordKey())
        if let password {
            await unlockWithPassword(password)
        } else {
            loadWalletUnlocked(password: nil)
        }
    }

    func unlockWithBiometrics() async {
        let password = keychain.get(walletPasswordKey())
        if let password {
            await unlockWithPassword(password)
        } else {
            loadWalletUnlocked(password: nil)
        }
    }

    func savePin(_ pin: String) {
        keychain.set(pin, forKey: pinKeychainKey())
    }

    func saveWalletPassword(_ password: String) {
        keychain.set(password, forKey: walletPasswordKey())
    }

    func lockWallet() {
        wallet = nil
        cachedPassword = nil
        wsClient?.stop()
        wsClient = nil
        syncTask?.cancel()
        pollTask?.cancel()
        bgSyncTask?.cancel()
        transactionsSynced = false
        screen = .pinUnlock
    }

    func switchNetwork(toTestnet: Bool) async {
        guard toTestnet != isTestnet else { return }
        wsClient?.stop()
        wsClient = nil
        syncTask?.cancel()
        pollTask?.cancel()
        bgSyncTask?.cancel()
        transactionsSynced = false
        wallet = nil
        cachedPassword = nil
        isTestnet = toTestnet

        let network: NetworkType = toTestnet ? .testnet : .mainnet
        if WalletManager.exists(dir: filesDir, network: network) {
            start()
        } else {
            screen = .welcome
        }
    }

    // MARK: - Address management

    func generateAddress() async {
        guard let w = wallet else { return }
        let addr = w.generateAddress()
        try? w.save(dir: filesDir, password: cachedPassword)
        addresses = w.getAddresses()
        let contact = Contact(address: addr, isOwned: true, derivationIndex: addresses.count - 1)
        try? db?.upsertContact(contact)
        contacts = (try? db?.getAllContacts()) ?? []
        // Resubscribe WS to include the new address
        wsClient?.updateAddresses(w.getAddresses())
    }

    func discoverAddresses() async {
        guard let w = wallet, let mn = masternode else { return }
        discoveringAddresses = true
        defer { discoveringAddresses = false }

        var lastUsedIndex = 0
        var gapCount = 0
        var index = 0

        while gapCount < WalletManager.gapLimit {
            guard let addr = w.deriveAddressAt(index: index) else { break }
            let bal = (try? await mn.getBalances(addresses: [addr])) ?? .zero
            if bal.total > 0 {
                lastUsedIndex = index
                gapCount = 0
                // Ensure address is in wallet
                while w.getAddresses().count <= index { _ = w.generateAddress() }
            } else {
                gapCount += 1
            }
            index += 1
        }

        // Trim to only keep used addresses (+ 1 primary minimum)
        let keepCount = max(lastUsedIndex + 1, 1)
        w.trimAddresses(count: keepCount)
        try? w.save(dir: filesDir, password: cachedPassword)
        addresses = w.getAddresses()
        try await syncOwnedAddresses()
    }

    // MARK: - Send

    func send(toAddress: String, amount: Int64, fromAddress: String? = nil) async {
        guard let w = wallet, let mn = masternode else { return }
        do {
            let tx = try w.createTransaction(toAddress: toAddress, amount: amount, fromAddress: fromAddress)
            let txid = try await mn.broadcastTransaction(tx)
            success = "Sent! TXID: \(txid.prefix(16))..."
            await refreshBalance()
            await refreshTransactions()
            await refreshUtxos()
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: - Network / sync

    func connectAndSync() async {
        guard let w = wallet else { return }
        let network: NetworkType = isTestnet ? .testnet : .mainnet
        let config = ConfigManager.load(dir: filesDir, isTestnet: isTestnet)
        let endpoints = ConfigManager.manualEndpoints(config: config)
        let credentials = ConfigManager.rpcCredentials(config: config)

        // Run peer discovery
        let discoveredPeers = await PeerDiscovery.shared.discoverAndRank(
            isTestnet: isTestnet,
            manualEndpoints: endpoints,
            credentials: credentials,
            filesDir: filesDir,
            expectedGenesisHash: network.genesisHash)

        peers = discoveredPeers

        // Find a live peer (use discovery results, fall back to defaults)
        let candidateEndpoints: [String]
        if !discoveredPeers.isEmpty {
            candidateEndpoints = discoveredPeers.filter { $0.isHealthy }.map { $0.endpoint }
        } else {
            candidateEndpoints = endpoints.isEmpty ? defaultEndpoints(network: network) : endpoints
        }

        var liveEndpoint: String? = nil
        for endpoint in (candidateEndpoints.isEmpty ? defaultEndpoints(network: network) : candidateEndpoints) {
            let client = MasternodeClient(endpoint: endpoint, credentials: credentials)
            if let h = try? await client.healthCheck() {
                health = h
                masternode = client
                liveEndpoint = endpoint
                connectedPeer = PeerInfo(endpoint: endpoint, isActive: true, isHealthy: true,
                                        blockHeight: h.blockHeight, version: h.version)
                break
            }
        }

        guard let endpoint = liveEndpoint else {
            error = "Cannot connect to any masternode"
            return
        }

        // Start WebSocket
        let wsBase = endpoint
            .replacingOccurrences(of: "https://", with: "wss://")
            .replacingOccurrences(of: "http://", with: "ws://")
        let ws = WsNotificationClient(wsUrl: wsBase, addresses: w.getAddresses())
        ws.onEvent = { [weak self] event in
            Task { @MainActor [weak self] in self?.handleWsEvent(event) }
        }
        ws.start()
        wsClient = ws

        loadSettings()
        await refreshBalance()
        await refreshTransactions()
        await refreshUtxos()
        transactionsSynced = true
        startPolling()
        startBackgroundSync()
        await retryUndeliveredPaymentRequests()
    }

    func refreshBalance() async {
        guard let w = wallet, let mn = masternode else { return }
        if let b = try? await mn.getBalances(addresses: w.getAddresses()) {
            balance = b
        }
    }

    func refreshTransactions() async {
        guard let w = wallet, let mn = masternode else { return }
        let txs = (try? await mn.getTransactionsMulti(addresses: w.getAddresses())) ?? []
        try? db?.upsertTransactions(txs)
        transactions = (try? db?.getAllTransactions()) ?? txs
    }

    func refreshUtxos() async {
        guard let w = wallet, let mn = masternode else { return }
        let newUtxos = (try? await mn.getUtxos(addresses: w.getAddresses())) ?? []
        w.setUtxos(newUtxos)
        utxos = newUtxos
        balance = Balance(confirmed: w.balance, pending: 0, total: w.balance, locked: 0)
    }

    func loadBlockRewardBreakdown() async {
        guard let mn = masternode, let h = health else { return }
        blockRewardBreakdownLoading = true
        blockRewardBreakdown = try? await mn.getBlockRewardBreakdown(height: h.blockHeight)
        blockRewardBreakdownLoading = false
    }

    // MARK: - Payment requests

    func sendPaymentRequest(payerAddress: String, amount: Int64, memo: String,
                            requesterName: String = "", fromAddressIdx: Int = 0) async {
        guard let w = wallet, let mn = masternode else { return }

        // Rate limit: max 3 pending requests per address per hour
        let since = Int64(Date().timeIntervalSince1970) - rateLimitWindowSecs
        let activeCount = (try? db?.countActiveOutgoingPaymentRequests(to: payerAddress, since: since)) ?? 0
        if activeCount >= rateLimitMax {
            error = "Too many requests to this address. Wait before sending another."
            return
        }

        let id = UUID().uuidString
        let addrs = w.getAddresses()
        let requesterAddress = addrs.indices.contains(fromAddressIdx) ? addrs[fromAddressIdx] : w.primaryAddress
        let now = Int64(Date().timeIntervalSince1970)

        guard let keypair = w.deriveKeypair(index: fromAddressIdx) else { error = "Key error"; return }
        let signData = buildPaymentRequestSignData(
            id: id, requesterAddress: requesterAddress, payerAddress: payerAddress,
            amountSats: amount, memo: memo, timestamp: now)
        let pubkeyHex = keypair.publicKeyBytes.hexString
        let signatureHex = (try? keypair.sign(Data(signData)))?.hexString ?? ""

        let pr = PaymentRequest(
            id: id, requesterAddress: requesterAddress, payerAddress: payerAddress,
            amountSats: amount, memo: memo, requesterName: requesterName,
            isOutgoing: true, createdAt: now, updatedAt: now,
            expiresAt: now + paymentRequestExpirySecs)
        try? db?.upsertPaymentRequest(pr)
        paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
        screen = .paymentRequests

        let delivered = (try? await mn.sendPaymentRequest(
            requestId: id, requesterAddress: requesterAddress, payerAddress: payerAddress,
            amountSats: amount, memo: memo, requesterName: requesterName,
            pubkeyHex: pubkeyHex, signatureHex: signatureHex, timestamp: now)) ?? false
        if delivered {
            try? db?.markPaymentRequestDelivered(id: id)
        }
        success = "Payment request sent!"
    }

    func acceptPaymentRequest(id: String) async {
        guard let w = wallet, let mn = masternode else { return }
        guard let pr = try? db?.getPaymentRequest(id: id) else { error = "Request not found"; return }

        let fee = FeeSchedule.default.calculateFee(sendAmount: pr.amountSats)
        let total = pr.amountSats + fee
        guard balance.confirmed >= total else {
            error = "Insufficient funds: need \(total.timeDisplay) TIME, have \(balance.confirmed.timeDisplay) TIME"
            return
        }

        do {
            let tx = try w.createTransaction(toAddress: pr.requesterAddress, amount: pr.amountSats)
            let txid = try await mn.broadcastTransaction(tx)
            let now = Int64(Date().timeIntervalSince1970)
            try? db?.markPaymentRequestPaid(id: id, txid: txid, updatedAt: now)
            paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
            _ = try? await mn.respondToPaymentRequest(requestId: id, payerAddress: pr.payerAddress, accepted: true)
            success = "Payment sent! TxID: \(txid.prefix(16))…"
            incomingPaymentRequest = nil
            screen = .overview
            await refreshBalance(); await refreshTransactions(); await refreshUtxos()
        } catch {
            self.error = "Failed: \(error.localizedDescription)"
        }
    }

    func declinePaymentRequest(id: String) async {
        guard let mn = masternode else { return }
        guard let pr = try? db?.getPaymentRequest(id: id) else { return }
        let now = Int64(Date().timeIntervalSince1970)
        try? db?.updatePaymentRequestStatus(id: id, status: .declined, updatedAt: now)
        paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
        _ = try? await mn.respondToPaymentRequest(requestId: id, payerAddress: pr.payerAddress, accepted: false)
        incomingPaymentRequest = nil
        screen = .overview
    }

    func cancelPaymentRequest(id: String) async {
        guard let mn = masternode else { return }
        if let pr = try? db?.getPaymentRequest(id: id), pr.delivered {
            _ = try? await mn.cancelPaymentRequest(requestId: id, requesterAddress: pr.requesterAddress)
        }
        try? db?.deletePaymentRequest(id: id)
        paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
    }

    func deletePaymentRequest(id: String) {
        try? db?.deletePaymentRequest(id: id)
        paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
    }

    func reviewPaymentRequest(_ req: IncomingPaymentRequest) async {
        incomingPaymentRequest = req
        screen = .paymentRequestReview
        guard let w = wallet, let mn = masternode else { return }
        _ = try? await mn.markPaymentRequestViewed(requestId: req.id, payerAddress: w.primaryAddress)
    }

    // MARK: - Contacts

    func saveContact(_ contact: Contact) {
        try? db?.upsertContact(contact)
        contacts = (try? db?.getAllContacts()) ?? []
    }

    func deleteContact(address: String) {
        try? db?.deleteContact(address: address)
        contacts = (try? db?.getAllContacts()) ?? []
    }

    // MARK: - Settings

    func getConfigRaw() -> String {
        ConfigManager.readRaw(dir: filesDir, isTestnet: isTestnet)
    }

    func saveConfigRaw(_ content: String) {
        ConfigManager.writeRaw(dir: filesDir, content: content, isTestnet: isTestnet)
    }

    func deleteWallet() {
        _ = WalletManager.deleteWallet(dir: filesDir, network: isTestnet ? .testnet : .mainnet)
        wallet = nil
        screen = .welcome
    }

    // MARK: - WebSocket events

    private func handleWsEvent(_ event: WsNotificationClient.Event) {
        switch event {
        case .connected(url: _): wsConnected = true
        case .disconnected(reason: _): wsConnected = false
        case .transactionReceived(let notif):
            if notificationsEnabled {
                NotificationManager.scheduleTransactionReceived(amount: notif.amount, address: notif.address)
            }
            Task { await refreshBalance(); await refreshTransactions(); await refreshUtxos() }
        case .utxoFinalized(txid: _, outputIndex: _):
            Task { await refreshUtxos() }
        case .transactionRejected(txid: _, let reason):
            error = "Transaction rejected: \(reason)"
        case .paymentRequestReceived(let req):
            if notificationsEnabled {
                NotificationManager.schedulePaymentRequestReceived(from: req.requesterName, amount: req.amountSats)
            }
            incomingPaymentRequest = req
            Task {
                let pr = PaymentRequest(id: req.id, requesterAddress: req.requesterAddress,
                                        payerAddress: req.payerAddress, amountSats: req.amountSats,
                                        memo: req.memo, requesterName: req.requesterName, isOutgoing: false)
                try? db?.upsertPaymentRequest(pr)
                paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
                await reviewPaymentRequest(req)
            }
        case .paymentRequestCancelled(let id):
            Task {
                if var pr = try? db?.getPaymentRequest(id: id) {
                    pr = PaymentRequest(id: pr.id, requesterAddress: pr.requesterAddress,
                                        payerAddress: pr.payerAddress, amountSats: pr.amountSats,
                                        memo: pr.memo, status: .cancelled, isOutgoing: pr.isOutgoing)
                    try? db?.upsertPaymentRequest(pr)
                    paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
                }
            }
        case .paymentRequestResponse(let id, let accepted):
            Task {
                if var pr = try? db?.getPaymentRequest(id: id) {
                    pr = PaymentRequest(id: pr.id, requesterAddress: pr.requesterAddress,
                                        payerAddress: pr.payerAddress, amountSats: pr.amountSats,
                                        memo: pr.memo, status: accepted ? .accepted : .declined,
                                        isOutgoing: pr.isOutgoing)
                    try? db?.upsertPaymentRequest(pr)
                    paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
                }
                success = accepted ? "Payment request accepted!" : "Payment request declined"
            }
        case .paymentRequestViewed(let requestId):
            Task {
                try? db?.markPaymentRequestViewed(id: requestId)
                paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
            }
        }
    }

    func setNotificationsEnabled(_ enabled: Bool) {
        notificationsEnabled = enabled
        try? db?.setSetting(key: "notifications_enabled", value: enabled ? "true" : "false")
    }

    // MARK: - Helpers

    private func loadSettings() {
        if let saved = try? db?.getSetting(key: "notifications_enabled") {
            notificationsEnabled = saved != "false"
        }
        if let saved = try? db?.getSetting(key: "decimal_places"), let val = Int(saved) {
            decimalPlaces = val
        }
    }

    private func initDb() {
        db = WalletDatabase(dir: currentDbDir)
    }

    private func syncOwnedAddresses() async throws {
        guard let w = wallet else { return }
        addresses = w.getAddresses()
        for (i, addr) in addresses.enumerated() {
            let contact = Contact(address: addr, isOwned: true, derivationIndex: i)
            try db?.upsertContact(contact)
        }
        contacts = (try? db?.getAllContacts()) ?? []
    }

    private func loadWalletUnlocked(password: String?) {
        let network: NetworkType = isTestnet ? .testnet : .mainnet
        if let w = try? WalletManager.load(dir: filesDir, network: network, password: password) {
            wallet = w
            cachedPassword = password
            initDb()
            addresses = w.getAddresses()
            Task { await connectAndSync() }
        }
    }

    private func pinKeychainKey() -> String { "timecoin_pin_\(isTestnet ? "testnet" : "mainnet")" }
    private func walletPasswordKey() -> String { "timecoin_password_\(isTestnet ? "testnet" : "mainnet")" }

    private func defaultEndpoints(network: NetworkType) -> [String] {
        let port = network.rpcPort
        return ["https://node1.time-coin.io:\(port)", "https://node2.time-coin.io:\(port)"]
    }

    // MARK: - Polling & background sync

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                guard let self, !Task.isCancelled else { break }
                await self.refreshBalance()
                await self.refreshTransactions()
                await self.refreshUtxos()
                if let mn = self.masternode { self.health = try? await mn.healthCheck() }
                await self.expirePaymentRequests()
            }
        }
    }

    private func startBackgroundSync() {
        bgSyncTask?.cancel()
        bgSyncTask = Task { [weak self] in
            guard let self else { return }
            var waited = 0
            while !self.transactionsSynced && waited < 120 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                waited += 1
            }
            guard self.transactionsSynced else { return }

            self.bgSyncOffset = Int((try? self.db?.getSetting(key: "bg_sync_offset")) ?? "") ?? 50

            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let w = self.wallet, let mn = self.masternode else { break }
                do {
                    let rawTxs = try await mn.getTransactionsMulti(
                        addresses: w.getAddresses(), limit: 100, offset: self.bgSyncOffset)
                    if rawTxs.isEmpty {
                        self.hasMoreTransactions = false
                        break
                    }
                    try? self.db?.upsertTransactions(rawTxs)
                    self.transactions = (try? self.db?.getAllTransactions()) ?? self.transactions
                    self.bgSyncOffset += rawTxs.count
                    try? self.db?.setSetting(key: "bg_sync_offset", value: "\(self.bgSyncOffset)")
                    if rawTxs.count < 100 {
                        self.hasMoreTransactions = false
                        break
                    }
                } catch {
                    try? await Task.sleep(nanoseconds: 10_000_000_000)
                }
            }
        }
    }

    func loadMoreTransactions() {
        guard !loadingMore, hasMoreTransactions else { return }
        loadingMore = true
        Task { [weak self] in
            defer { self?.loadingMore = false }
            guard let self, let w = self.wallet, let mn = self.masternode else { return }
            do {
                let rawTxs = try await mn.getTransactionsMulti(
                    addresses: w.getAddresses(), limit: 100, offset: self.bgSyncOffset)
                if rawTxs.isEmpty { self.hasMoreTransactions = false; return }
                try? self.db?.upsertTransactions(rawTxs)
                self.transactions = (try? self.db?.getAllTransactions()) ?? self.transactions
                self.bgSyncOffset += rawTxs.count
                try? self.db?.setSetting(key: "bg_sync_offset", value: "\(self.bgSyncOffset)")
                if rawTxs.count < 100 { self.hasMoreTransactions = false }
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    private func expirePaymentRequests() async {
        let now = Int64(Date().timeIntervalSince1970)
        guard let expired = try? db?.getExpiredPaymentRequests(now: now), !expired.isEmpty else { return }
        for pr in expired {
            try? db?.updatePaymentRequestStatus(id: pr.id, status: .cancelled, updatedAt: now)
        }
        paymentRequests = (try? db?.getAllPaymentRequests()) ?? []
    }

    private func retryUndeliveredPaymentRequests() async {
        guard let mn = masternode, let w = wallet else { return }
        guard let undelivered = try? db?.getUndeliveredOutgoingPaymentRequests() else { return }
        for pr in undelivered {
            guard (try? db?.getPaymentRequest(id: pr.id))?.status == .pending else { continue }
            let addrIdx = pr.requesterAddress == w.primaryAddress ? 0 :
                (w.getAddresses().firstIndex(of: pr.requesterAddress) ?? 0)
            guard let keypair = w.deriveKeypair(index: addrIdx) else { continue }
            let signData = buildPaymentRequestSignData(
                id: pr.id, requesterAddress: pr.requesterAddress, payerAddress: pr.payerAddress,
                amountSats: pr.amountSats, memo: pr.memo, timestamp: pr.createdAt)
            let ok = (try? await mn.sendPaymentRequest(
                requestId: pr.id, requesterAddress: pr.requesterAddress, payerAddress: pr.payerAddress,
                amountSats: pr.amountSats, memo: pr.memo, requesterName: pr.requesterName,
                pubkeyHex: keypair.publicKeyBytes.hexString,
                signatureHex: (try? keypair.sign(Data(signData)))?.hexString ?? "",
                timestamp: pr.createdAt)) ?? false
            if ok { try? db?.markPaymentRequestDelivered(id: pr.id) }
        }
    }

    // MARK: - Consolidation

    func consolidateUtxos() {
        guard let w = wallet, let mn = masternode else { error = "Not connected"; return }
        guard !consolidating else { return }
        consolidationCancelled = false
        consolidating = true
        consolidationStatus = "Fetching UTXOs…"

        Task { [weak self] in
            guard let self else { return }
            defer { self.consolidating = false }
            do {
                let addresses = w.getAddresses()
                var doneBatches = 0
                var totalConsolidated = 0

                struct AddrUtxos { let address: String; let utxos: [Utxo] }
                var addrUtxosList: [AddrUtxos] = []
                for addr in addresses {
                    let utxos = ((try? await mn.getUtxos(addresses: [addr])) ?? [])
                        .filter { $0.spendable }
                        .sorted { $0.amount < $1.amount }
                    if utxos.count > 1 { addrUtxosList.append(AddrUtxos(address: addr, utxos: utxos)) }
                }
                let batchCount = addrUtxosList.reduce(0) { $0 + ($1.utxos.count + 49) / 50 }
                if batchCount == 0 {
                    self.consolidationStatus = "Nothing to consolidate — already 1 UTXO or fewer per address."
                    return
                }

                for item in addrUtxosList {
                    for chunk in item.utxos.chunked(into: 50) {
                        if self.consolidationCancelled {
                            self.consolidationStatus = "Cancelled after \(doneBatches) batch(es)."
                            return
                        }
                        doneBatches += 1
                        self.consolidationStatus = "Batch \(doneBatches) / \(batchCount)…"
                        let total = chunk.reduce(0) { $0 + $1.amount }
                        let fee = FeeSchedule.default.calculateFee(sendAmount: total)
                        let net = total - fee
                        if net <= 0 { continue }
                        if let tx = try? w.createTransaction(toAddress: item.address, amount: net, fromAddress: item.address),
                           let _ = try? await mn.broadcastTransaction(tx) {
                            totalConsolidated += chunk.count
                        }
                    }
                }
                self.consolidationStatus = "Done — consolidated \(totalConsolidated) UTXOs."
                await self.refreshBalance()
                await self.refreshUtxos()
            } catch {
                self.consolidationStatus = "Failed: \(error.localizedDescription)"
            }
        }
    }

    func cancelConsolidation() { consolidationCancelled = true }

    // MARK: - Helpers

    func reconnect() {
        syncTask?.cancel()
        syncTask = Task { await connectAndSync() }
    }

    func saveMemo(for tx: TransactionRecord, memo: String) {
        try? db?.updateTransactionMemo(txid: tx.txid, vout: tx.vout, isSend: tx.isSend, isFee: tx.isFee, memo: memo)
        transactions = transactions.map {
            $0.uniqueKey == tx.uniqueKey ? TransactionRecord(
                txid: $0.txid, vout: $0.vout, isSend: $0.isSend, address: $0.address,
                amount: $0.amount, fee: $0.fee, timestamp: $0.timestamp, status: $0.status,
                isFee: $0.isFee, isConsolidate: $0.isConsolidate, blockHash: $0.blockHash,
                blockHeight: $0.blockHeight, confirmations: $0.confirmations, memo: memo
            ) : $0
        }
    }

    func setDecimalPlaces(_ places: Int) {
        decimalPlaces = places
        try? db?.setSetting(key: "decimal_places", value: "\(places)")
    }

    func enrollBiometric() async -> Bool {
        guard let pin = keychain.get(pinKeychainKey()) else { return false }
        return await BiometricHelper.enroll(pin: pin)
    }

    // MARK: - Sign data helper

    private func buildPaymentRequestSignData(id: String, requesterAddress: String, payerAddress: String,
                                              amountSats: Int64, memo: String, timestamp: Int64) -> [UInt8] {
        var data = [UInt8]()
        data.append(contentsOf: Array(id.utf8))
        data.append(contentsOf: Array(requesterAddress.utf8))
        data.append(contentsOf: Array(payerAddress.utf8))
        var amt = amountSats
        withUnsafeBytes(of: &amt) { data.append(contentsOf: $0) }
        data.append(contentsOf: Array(memo.utf8))
        var ts = timestamp
        withUnsafeBytes(of: &ts) { data.append(contentsOf: $0) }
        return data
    }
}
