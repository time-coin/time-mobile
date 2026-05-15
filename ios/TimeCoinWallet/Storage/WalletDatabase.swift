import Foundation

// JSON-file backed store — equivalent to Android Room's four tables.
// Each table is a separate JSON file in the wallet directory.
// Mainnet and testnet use separate directories (matching Android).
class WalletDatabase {
    private let dir: URL

    init(dir: URL) {
        self.dir = dir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    // MARK: - Contacts

    func upsertContact(_ contact: Contact) throws {
        var all = try loadContacts()
        all[contact.address] = contact
        try saveContacts(all)
    }

    func deleteContact(address: String) throws {
        var all = try loadContacts()
        all.removeValue(forKey: address)
        try saveContacts(all)
    }

    func getContact(address: String) throws -> Contact? {
        try loadContacts()[address]
    }

    func getAllContacts() throws -> [Contact] {
        Array(try loadContacts().values).sorted { $0.createdAt < $1.createdAt }
    }

    func getOwnedAddresses() throws -> [Contact] {
        try getAllContacts().filter { $0.isOwned }
    }

    // MARK: - Transactions

    func upsertTransaction(_ tx: TransactionRecord) throws {
        var all = try loadTransactions()
        all[tx.uniqueKey] = tx
        try saveTransactions(all)
    }

    func upsertTransactions(_ txs: [TransactionRecord]) throws {
        var all = try loadTransactions()
        for tx in txs { all[tx.uniqueKey] = tx }
        try saveTransactions(all)
    }

    func getAllTransactions() throws -> [TransactionRecord] {
        Array(try loadTransactions().values)
            .sorted { $0.timestamp > $1.timestamp }
    }

    func getTransaction(uniqueKey: String) throws -> TransactionRecord? {
        try loadTransactions()[uniqueKey]
    }

    func updateTransactionStatus(txid: String, status: TransactionStatus) throws {
        var all = try loadTransactions()
        for (key, tx) in all where tx.txid == txid {
            all[key] = TransactionRecord(
                txid: tx.txid, vout: tx.vout, isSend: tx.isSend, address: tx.address,
                amount: tx.amount, fee: tx.fee, timestamp: tx.timestamp, status: status,
                isFee: tx.isFee, isConsolidate: tx.isConsolidate, blockHash: tx.blockHash,
                blockHeight: tx.blockHeight, confirmations: tx.confirmations, memo: tx.memo
            )
        }
        try saveTransactions(all)
    }

    func transactionCount() throws -> Int {
        try loadTransactions().count
    }

    // MARK: - Settings

    func getSetting(key: String) throws -> String? {
        try loadSettings()[key]
    }

    func setSetting(key: String, value: String) throws {
        var all = try loadSettings()
        all[key] = value
        try saveSettings(all)
    }

    func removeSetting(key: String) throws {
        var all = try loadSettings()
        all.removeValue(forKey: key)
        try saveSettings(all)
    }

    // MARK: - Payment Requests

    func upsertPaymentRequest(_ pr: PaymentRequest) throws {
        var all = try loadPaymentRequests()
        all[pr.id] = pr
        try savePaymentRequests(all)
    }

    func getPaymentRequest(id: String) throws -> PaymentRequest? {
        try loadPaymentRequests()[id]
    }

    func getAllPaymentRequests() throws -> [PaymentRequest] {
        Array(try loadPaymentRequests().values)
            .sorted { $0.createdAt > $1.createdAt }
    }

    func deletePaymentRequest(id: String) throws {
        var all = try loadPaymentRequests()
        all.removeValue(forKey: id)
        try savePaymentRequests(all)
    }

    func markPaymentRequestViewed(id: String) throws {
        var all = try loadPaymentRequests()
        guard let pr = all[id] else { return }
        all[id] = PaymentRequest(id: pr.id, requesterAddress: pr.requesterAddress,
                                 payerAddress: pr.payerAddress, amountSats: pr.amountSats,
                                 memo: pr.memo, requesterName: pr.requesterName,
                                 status: pr.status, isOutgoing: pr.isOutgoing,
                                 viewed: true)
        try savePaymentRequests(all)
    }

    func updatePaymentRequestStatus(id: String, status: PaymentRequestStatus, updatedAt: Int64) throws {
        var all = try loadPaymentRequests()
        guard let pr = all[id] else { return }
        all[id] = PaymentRequest(
            id: pr.id, requesterAddress: pr.requesterAddress, payerAddress: pr.payerAddress,
            amountSats: pr.amountSats, memo: pr.memo, requesterName: pr.requesterName,
            status: status, isOutgoing: pr.isOutgoing, createdAt: pr.createdAt,
            updatedAt: updatedAt, paidTxid: pr.paidTxid, delivered: pr.delivered,
            viewed: pr.viewed, expiresAt: pr.expiresAt)
        try savePaymentRequests(all)
    }

    func markPaymentRequestPaid(id: String, txid: String, updatedAt: Int64) throws {
        var all = try loadPaymentRequests()
        guard let pr = all[id] else { return }
        all[id] = PaymentRequest(
            id: pr.id, requesterAddress: pr.requesterAddress, payerAddress: pr.payerAddress,
            amountSats: pr.amountSats, memo: pr.memo, requesterName: pr.requesterName,
            status: .paid, isOutgoing: pr.isOutgoing, createdAt: pr.createdAt,
            updatedAt: updatedAt, paidTxid: txid, delivered: pr.delivered,
            viewed: pr.viewed, expiresAt: pr.expiresAt)
        try savePaymentRequests(all)
    }

    func markPaymentRequestDelivered(id: String) throws {
        var all = try loadPaymentRequests()
        guard let pr = all[id] else { return }
        all[id] = PaymentRequest(
            id: pr.id, requesterAddress: pr.requesterAddress, payerAddress: pr.payerAddress,
            amountSats: pr.amountSats, memo: pr.memo, requesterName: pr.requesterName,
            status: pr.status, isOutgoing: pr.isOutgoing, createdAt: pr.createdAt,
            updatedAt: pr.updatedAt, paidTxid: pr.paidTxid, delivered: true,
            viewed: pr.viewed, expiresAt: pr.expiresAt)
        try savePaymentRequests(all)
    }

    func getExpiredPaymentRequests(now: Int64) throws -> [PaymentRequest] {
        try getAllPaymentRequests().filter { $0.status == .pending && $0.expiresAt > 0 && $0.expiresAt < now }
    }

    func getUndeliveredOutgoingPaymentRequests() throws -> [PaymentRequest] {
        try getAllPaymentRequests().filter { $0.isOutgoing && $0.status == .pending && !$0.delivered }
    }

    func countActiveOutgoingPaymentRequests(to address: String, since: Int64) throws -> Int {
        try getAllPaymentRequests().filter {
            $0.isOutgoing && $0.payerAddress == address &&
            $0.status == .pending && $0.createdAt >= since
        }.count
    }

    func updateTransactionMemo(txid: String, vout: Int, isSend: Bool, isFee: Bool, memo: String) throws {
        var all = try loadTransactions()
        let key = "\(txid):\(isSend):\(isFee):\(vout)"
        guard let tx = all[key] else { return }
        all[key] = TransactionRecord(
            txid: tx.txid, vout: tx.vout, isSend: tx.isSend, address: tx.address,
            amount: tx.amount, fee: tx.fee, timestamp: tx.timestamp, status: tx.status,
            isFee: tx.isFee, isConsolidate: tx.isConsolidate, blockHash: tx.blockHash,
            blockHeight: tx.blockHeight, confirmations: tx.confirmations, memo: memo)
        try saveTransactions(all)
    }

    func getPendingTransactionIds() throws -> [String] {
        try getAllTransactions().filter { $0.status == .pending }.map { $0.txid }
    }

    // MARK: - Private I/O

    private func contactsFile() -> URL { dir.appendingPathComponent("contacts.json") }
    private func transactionsFile() -> URL { dir.appendingPathComponent("transactions.json") }
    private func settingsFile() -> URL { dir.appendingPathComponent("settings.json") }
    private func paymentRequestsFile() -> URL { dir.appendingPathComponent("payment_requests.json") }

    private func loadContacts() throws -> [String: Contact] {
        load(from: contactsFile()) ?? [:]
    }
    private func saveContacts(_ data: [String: Contact]) throws {
        try save(data, to: contactsFile())
    }

    private func loadTransactions() throws -> [String: TransactionRecord] {
        load(from: transactionsFile()) ?? [:]
    }
    private func saveTransactions(_ data: [String: TransactionRecord]) throws {
        try save(data, to: transactionsFile())
    }

    private func loadSettings() throws -> [String: String] {
        load(from: settingsFile()) ?? [:]
    }
    private func saveSettings(_ data: [String: String]) throws {
        try save(data, to: settingsFile())
    }

    private func loadPaymentRequests() throws -> [String: PaymentRequest] {
        load(from: paymentRequestsFile()) ?? [:]
    }
    private func savePaymentRequests(_ data: [String: PaymentRequest]) throws {
        try save(data, to: paymentRequestsFile())
    }

    private func load<T: Decodable>(from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private func save<T: Encodable>(_ value: T, to url: URL) throws {
        let data = try JSONEncoder().encode(value)
        try data.write(to: url, options: .atomic)
    }
}
