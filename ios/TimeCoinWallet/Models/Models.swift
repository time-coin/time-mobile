import Foundation

struct Utxo: Codable, Identifiable {
    let txid: String
    let vout: Int
    let amount: Int64
    let address: String
    let confirmations: Int
    let spendable: Bool

    var id: String { "\(txid):\(vout)" }

    init(txid: String, vout: Int, amount: Int64, address: String, confirmations: Int = 0, spendable: Bool = true) {
        self.txid = txid; self.vout = vout; self.amount = amount
        self.address = address; self.confirmations = confirmations; self.spendable = spendable
    }
}

struct Balance: Codable, Equatable {
    let confirmed: Int64
    let pending: Int64
    let total: Int64
    let locked: Int64

    static let zero = Balance(confirmed: 0, pending: 0, total: 0, locked: 0)

    init(confirmed: Int64 = 0, pending: Int64 = 0, total: Int64 = 0, locked: Int64 = 0) {
        self.confirmed = confirmed; self.pending = pending; self.total = total; self.locked = locked
    }
}

enum TransactionStatus: String, Codable {
    case pending = "Pending"
    case approved = "Approved"
    case declined = "Declined"
}

struct TransactionRecord: Codable, Identifiable {
    let txid: String
    let vout: Int
    let isSend: Bool
    let address: String
    let amount: Int64
    let fee: Int64
    let timestamp: Int64
    let status: TransactionStatus
    let isFee: Bool
    let isConsolidate: Bool
    let blockHash: String
    let blockHeight: Int64
    let confirmations: Int64
    let memo: String

    var id: String { uniqueKey }
    var uniqueKey: String { "\(txid):\(isSend):\(isFee):\(vout)" }

    init(
        txid: String, vout: Int = 0, isSend: Bool, address: String, amount: Int64,
        fee: Int64 = 0, timestamp: Int64, status: TransactionStatus = .pending,
        isFee: Bool = false, isConsolidate: Bool = false, blockHash: String = "",
        blockHeight: Int64 = 0, confirmations: Int64 = 0, memo: String = ""
    ) {
        self.txid = txid; self.vout = vout; self.isSend = isSend; self.address = address
        self.amount = amount; self.fee = fee; self.timestamp = timestamp; self.status = status
        self.isFee = isFee; self.isConsolidate = isConsolidate; self.blockHash = blockHash
        self.blockHeight = blockHeight; self.confirmations = confirmations; self.memo = memo
    }
}

struct HealthStatus: Codable {
    let status: String
    let version: String
    let blockHeight: Int64
    let peerCount: Int
    let isSyncing: Bool
    let syncProgress: Double

    init(status: String, version: String, blockHeight: Int64, peerCount: Int,
         isSyncing: Bool = false, syncProgress: Double = 1.0) {
        self.status = status; self.version = version; self.blockHeight = blockHeight
        self.peerCount = peerCount; self.isSyncing = isSyncing; self.syncProgress = syncProgress
    }
}

struct FinalityStatus: Codable {
    let txid: String
    let finalized: Bool
    let confirmations: Int
}

struct PeerInfo: Codable, Identifiable {
    let endpoint: String
    let isActive: Bool
    let isHealthy: Bool
    let wsAvailable: Bool
    let pingMs: Int64?
    let blockHeight: Int64?
    let version: String?
    let isSyncing: Bool

    var id: String { endpoint }

    init(endpoint: String, isActive: Bool = false, isHealthy: Bool = false,
         wsAvailable: Bool = false, pingMs: Int64? = nil, blockHeight: Int64? = nil,
         version: String? = nil, isSyncing: Bool = false) {
        self.endpoint = endpoint; self.isActive = isActive; self.isHealthy = isHealthy
        self.wsAvailable = wsAvailable; self.pingMs = pingMs; self.blockHeight = blockHeight
        self.version = version; self.isSyncing = isSyncing
    }
}

struct TxOutputInfo {
    let value: Int64
    let index: Int
    let address: String
}

struct TxDetail {
    let fee: Int64
    let outputs: [TxOutputInfo]
    let finalized: Bool
}

struct Contact: Codable, Identifiable {
    let address: String
    let label: String
    let name: String
    let email: String
    let phone: String
    let isOwned: Bool
    let derivationIndex: Int?
    let createdAt: Int64
    let updatedAt: Int64

    var id: String { address }

    init(address: String, label: String = "", name: String = "", email: String = "",
         phone: String = "", isOwned: Bool = false, derivationIndex: Int? = nil,
         createdAt: Int64 = Int64(Date().timeIntervalSince1970),
         updatedAt: Int64 = Int64(Date().timeIntervalSince1970)) {
        self.address = address; self.label = label; self.name = name; self.email = email
        self.phone = phone; self.isOwned = isOwned; self.derivationIndex = derivationIndex
        self.createdAt = createdAt; self.updatedAt = updatedAt
    }
}

// Fee tiers matching Android/desktop wallet
struct FeeSchedule {
    static let satoshisPerTime: Int64 = 100_000_000
    static let minTxFee: Int64 = 1_000_000  // 0.01 TIME

    struct FeeTier {
        let upTo: Int64
        let rateBps: Int64
    }

    let tiers: [FeeTier]
    let minFee: Int64

    static let `default` = FeeSchedule(
        tiers: [
            FeeTier(upTo: 100 * satoshisPerTime, rateBps: 100),
            FeeTier(upTo: 1_000 * satoshisPerTime, rateBps: 50),
            FeeTier(upTo: 10_000 * satoshisPerTime, rateBps: 25),
            FeeTier(upTo: Int64.max, rateBps: 10),
        ],
        minFee: minTxFee
    )

    func calculateFee(sendAmount: Int64) -> Int64 {
        let rateBps = tiers.first(where: { sendAmount < $0.upTo })?.rateBps ?? 10
        let proportional = sendAmount * rateBps / 10_000
        return max(proportional, minFee)
    }
}

struct BlockRewardEntry {
    let address: String
    let amount: Int64
}

struct BlockRewardBreakdown {
    let blockHeight: Int64
    let rewards: [BlockRewardEntry]
}

enum PaymentRequestStatus: String, Codable {
    case pending = "Pending"
    case accepted = "Accepted"
    case declined = "Declined"
    case cancelled = "Cancelled"
    case paid = "Paid"
}

struct PaymentRequest: Codable, Identifiable {
    let id: String
    let requesterAddress: String
    let payerAddress: String
    let amountSats: Int64
    let memo: String
    let requesterName: String
    let status: PaymentRequestStatus
    let isOutgoing: Bool
    let createdAt: Int64
    let updatedAt: Int64
    let paidTxid: String
    let delivered: Bool
    let viewed: Bool
    let expiresAt: Int64

    init(
        id: String, requesterAddress: String, payerAddress: String, amountSats: Int64,
        memo: String = "", requesterName: String = "", status: PaymentRequestStatus = .pending,
        isOutgoing: Bool, createdAt: Int64 = Int64(Date().timeIntervalSince1970),
        updatedAt: Int64 = Int64(Date().timeIntervalSince1970), paidTxid: String = "",
        delivered: Bool = false, viewed: Bool = false, expiresAt: Int64 = 0
    ) {
        self.id = id; self.requesterAddress = requesterAddress; self.payerAddress = payerAddress
        self.amountSats = amountSats; self.memo = memo; self.requesterName = requesterName
        self.status = status; self.isOutgoing = isOutgoing; self.createdAt = createdAt
        self.updatedAt = updatedAt; self.paidTxid = paidTxid; self.delivered = delivered
        self.viewed = viewed; self.expiresAt = expiresAt
    }
}

// MARK: - Formatting helpers

extension Int64 {
    var timeString: String {
        let whole = self / 100_000_000
        let frac = self % 100_000_000
        return "\(whole).\(String(format: "%08d", frac)) TIME"
    }

    var timeDisplay: String {
        let whole = self / 100_000_000
        let frac = self % 100_000_000
        if frac == 0 { return "\(whole)" }
        let fracStr = String(format: "%08d", frac).replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
        return "\(whole).\(fracStr)"
    }
}
