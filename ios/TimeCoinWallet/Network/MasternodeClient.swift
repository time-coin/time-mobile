import Foundation
import Alamofire

// JSON-RPC 2.0 client for masternode communication.
// Mirrors Android MasternodeClient: same RPC methods, same batching (20 per call),
// same trust-all TLS for self-signed masternode certs.
class MasternodeClient {
    let rpcEndpoint: String
    private let credentials: (String, String)?
    private let session: Session
    private var requestIdCounter: Int = 1

    static let batchSize = 20

    init(endpoint: String, credentials: (String, String)? = nil) {
        if endpoint.hasPrefix("http://") || endpoint.hasPrefix("https://") {
            self.rpcEndpoint = endpoint
        } else {
            self.rpcEndpoint = "https://\(endpoint)"
        }
        self.credentials = credentials

        // Trust all TLS certs (masternodes use self-signed)
        let trustManager = ServerTrustManager(allHostsMustBeEvaluated: false, evaluators: [:])
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 30
        self.session = Session(configuration: configuration, serverTrustManager: trustManager)
    }

    // MARK: - RPC methods

    func getBalance(address: String) async throws -> Balance {
        let result = try await rpcCall(method: "getbalance", params: [address])
        return parseBalance(result)
    }

    func getBalances(addresses: [String]) async throws -> Balance {
        if addresses.count <= Self.batchSize {
            let result = try await rpcCall(method: "getbalances", params: [addresses])
            return parseBalance(result)
        }
        var confirmed: Int64 = 0; var total: Int64 = 0; var locked: Int64 = 0
        for chunk in addresses.chunked(into: Self.batchSize) {
            let result = try await rpcCall(method: "getbalances", params: [chunk])
            let b = parseBalance(result)
            confirmed += b.confirmed; total += b.total; locked += b.locked
        }
        return Balance(confirmed: confirmed, pending: total - confirmed - locked, total: total, locked: locked)
    }

    func getTransactions(address: String, limit: Int = 50) async throws -> [TransactionRecord] {
        let result = try await rpcCall(method: "listtransactions", params: [address, limit])
        return parseTransactionList(result)
    }

    func getTransactionsMulti(addresses: [String], limit: Int = 50, offset: Int = 0) async throws -> [TransactionRecord] {
        if addresses.count <= Self.batchSize {
            let result = try await rpcCall(method: "listtransactionsmulti", params: [addresses, limit, offset])
            return parseTransactionList(result)
        }
        var all: [TransactionRecord] = []
        var seen = Set<String>()
        for chunk in addresses.chunked(into: Self.batchSize) {
            let result = try await rpcCall(method: "listtransactionsmulti", params: [chunk, limit, offset])
            for tx in parseTransactionList(result) {
                if seen.insert(tx.uniqueKey).inserted { all.append(tx) }
            }
        }
        return all
    }

    func getUtxos(addresses: [String]) async throws -> [Utxo] {
        var all: [Utxo] = []
        for chunk in (addresses.count <= Self.batchSize ? [addresses] : addresses.chunked(into: Self.batchSize)) {
            let result = try await rpcCall(method: "listunspentmulti", params: [chunk])
            if let arr = result as? [[String: Any]] {
                for u in arr {
                    guard let txid = u["txid"] as? String,
                          let vout = u["vout"] as? Int else { continue }
                    let amount = jsonToSatoshis(u["amount"])
                    let addr = u["address"] as? String ?? ""
                    let confs = u["confirmations"] as? Int ?? 0
                    let spendable = u["spendable"] as? Bool ?? true
                    all.append(Utxo(txid: txid, vout: vout, amount: amount, address: addr, confirmations: confs, spendable: spendable))
                }
            }
        }
        return all
    }

    func broadcastTransaction(_ tx: TimeCoinTransaction) async throws -> String {
        let encoder = JSONEncoder()
        let txData = try encoder.encode(tx)
        let txObj = try JSONSerialization.jsonObject(with: txData)
        let result = try await rpcCall(method: "sendrawtransaction", params: [txObj])
        return (result as? String) ?? "\(result)"
    }

    func validateAddress(_ address: String) async throws -> Bool {
        let result = try await rpcCall(method: "validateaddress", params: [address])
        if let obj = result as? [String: Any] {
            return obj["isvalid"] as? Bool ?? false
        }
        return false
    }

    func healthCheck() async throws -> HealthStatus {
        let result = try await rpcCall(method: "getblockchaininfo", params: [])
        guard let obj = result as? [String: Any] else {
            throw MasternodeError.unexpectedResponse
        }
        let height = obj["blocks"] as? Int64 ?? obj["height"] as? Int64 ?? 0
        let chain = obj["chain"] as? String ?? "unknown"
        let isSyncing = obj["initialblockdownload"] as? Bool ?? false
        let syncProgress = obj["verificationprogress"] as? Double ?? 1.0

        var peerCount = 0; var version = ""
        if let ni = try? await rpcCall(method: "getnetworkinfo", params: []),
           let niObj = ni as? [String: Any] {
            peerCount = niObj["connections"] as? Int ?? 0
            version = ((niObj["subversion"] as? String) ?? "").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        }
        return HealthStatus(status: "healthy", version: "\(chain) (\(version))",
                           blockHeight: height, peerCount: peerCount,
                           isSyncing: isSyncing, syncProgress: syncProgress)
    }

    func getBlockHeight() async throws -> Int64 {
        let result = try await rpcCall(method: "getblockcount", params: [])
        return result as? Int64 ?? 0
    }

    func getGenesisHash() async throws -> String {
        let result = try await rpcCall(method: "getblockhash", params: [0])
        guard let hash = result as? String else { throw MasternodeError.unexpectedResponse }
        return hash
    }

    func getTransactionFinality(txid: String) async throws -> FinalityStatus {
        let result = try await rpcCall(method: "gettransactionfinality", params: [txid])
        guard let obj = result as? [String: Any] else { throw MasternodeError.unexpectedResponse }
        return FinalityStatus(
            txid: txid,
            finalized: obj["finalized"] as? Bool ?? false,
            confirmations: obj["confirmations"] as? Int ?? 0
        )
    }

    func getPeerAddresses() async throws -> [String] {
        let result = try await rpcCall(method: "getpeerinfo", params: [])
        guard let arr = result as? [[String: Any]] else { return [] }
        return arr.compactMap { $0["addr"] as? String }
    }

    func getTransactionDetail(txid: String) async throws -> TxDetail {
        let result = try await rpcCall(method: "gettransaction", params: [txid])
        guard let obj = result as? [String: Any] else { throw MasternodeError.unexpectedResponse }
        let fee = jsonToSatoshisAbs(obj["fee"])
        let finalized = obj["finalized"] as? Bool ?? false
        var outputs: [TxOutputInfo] = []
        if let vouts = obj["vout"] as? [[String: Any]] {
            for vout in vouts {
                let value = jsonToSatoshisAbs(vout["value"])
                guard let n = vout["n"] as? Int else { continue }
                let addr = (vout["scriptPubKey"] as? [String: Any])?["address"] as? String ?? ""
                outputs.append(TxOutputInfo(value: value, index: n, address: addr))
            }
        }
        return TxDetail(fee: fee, outputs: outputs, finalized: finalized)
    }

    func getBlockRewardBreakdown(height: Int64) async throws -> BlockRewardBreakdown {
        let result = try await rpcCall(method: "getblock", params: [height])
        guard let obj = result as? [String: Any] else { return BlockRewardBreakdown(blockHeight: height, rewards: []) }
        let rewards = (obj["masternode_rewards"] as? [[String: Any]] ?? []).compactMap { r -> BlockRewardEntry? in
            guard let addr = r["address"] as? String, let amount = r["amount"] as? Int64 else { return nil }
            return BlockRewardEntry(address: addr, amount: amount)
        }
        return BlockRewardBreakdown(blockHeight: height, rewards: rewards)
    }

    func getAddressPubkey(address: String) async throws -> String? {
        let result = try? await rpcCall(method: "getaddresspubkey", params: [address])
        return (result as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    func registerAddressPubkey(address: String, pubkeyHex: String) async throws -> Bool {
        _ = try? await rpcCall(method: "registeraddresspubkey", params: [address, pubkeyHex])
        return true
    }

    func sendPaymentRequest(
        requestId: String, requesterAddress: String, payerAddress: String,
        amountSats: Int64, memo: String, requesterName: String,
        pubkeyHex: String = "", signatureHex: String = "",
        timestamp: Int64 = Int64(Date().timeIntervalSince1970)
    ) async throws -> Bool {
        let payload: [String: Any] = [
            "id": requestId, "requester_address": requesterAddress,
            "payer_address": payerAddress, "amount": amountSats,
            "memo": memo, "requester_name": requesterName,
            "pubkey_hex": pubkeyHex, "signature_hex": signatureHex, "timestamp": timestamp
        ]
        _ = try? await rpcCall(method: "sendpaymentrequest", params: [payload])
        return true
    }

    func markPaymentRequestViewed(requestId: String, payerAddress: String) async throws -> Bool {
        let payload: [String: Any] = ["id": requestId, "payer_address": payerAddress]
        _ = try? await rpcCall(method: "markpaymentrequestviewed", params: [payload])
        return true
    }

    func cancelPaymentRequest(requestId: String, requesterAddress: String) async throws -> Bool {
        let payload: [String: Any] = ["id": requestId, "requester_address": requesterAddress]
        _ = try? await rpcCall(method: "cancelpaymentrequest", params: [payload])
        return true
    }

    func respondToPaymentRequest(requestId: String, payerAddress: String, accepted: Bool) async throws -> Bool {
        let payload: [String: Any] = ["id": requestId, "payer_address": payerAddress, "accepted": accepted]
        _ = try? await rpcCall(method: "respondpaymentrequest", params: [payload])
        return true
    }

    // MARK: - Internal

    private func rpcCall(method: String, params: [Any]) async throws -> Any {
        let id = requestIdCounter
        requestIdCounter += 1

        var body: [String: Any] = [
            "jsonrpc": "2.0",
            "id": "\(id)",
            "method": method,
            "params": params
        ]

        return try await withCheckedThrowingContinuation { continuation in
            var headers: HTTPHeaders = [.contentType("application/json")]
            if let (user, pass) = credentials {
                let cred = "\(user):\(pass)".data(using: .utf8)!.base64EncodedString()
                headers.add(.authorization("Basic \(cred)"))
            }

            session.request(rpcEndpoint, method: .post,
                           parameters: body,
                           encoding: JSONEncoding.default,
                           headers: headers)
            .validate()
            .responseJSON { response in
                switch response.result {
                case .success(let value):
                    guard let obj = value as? [String: Any] else {
                        continuation.resume(throwing: MasternodeError.unexpectedResponse)
                        return
                    }
                    if let error = obj["error"] as? [String: Any] {
                        let code = error["code"] as? Int ?? -1
                        let msg = error["message"] as? String ?? "Unknown error"
                        continuation.resume(throwing: MasternodeError.rpcError(code, msg))
                        return
                    }
                    guard let result = obj["result"] else {
                        continuation.resume(throwing: MasternodeError.noResult)
                        return
                    }
                    continuation.resume(returning: result)
                case .failure(let error):
                    continuation.resume(throwing: MasternodeError.networkError(error.localizedDescription))
                }
            }
        }
    }

    private func parseBalance(_ result: Any) -> Balance {
        guard let obj = result as? [String: Any] else { return .zero }
        let confirmed = jsonToSatoshis(obj["available"])
        let total = jsonToSatoshis(obj["balance"])
        let locked = jsonToSatoshis(obj["locked"])
        return Balance(confirmed: confirmed, pending: total - confirmed - locked, total: total, locked: locked)
    }

    private func parseTransactionList(_ result: Any) -> [TransactionRecord] {
        var arr: [[String: Any]] = []
        if let obj = result as? [String: Any], let txs = obj["transactions"] as? [[String: Any]] {
            arr = txs
        } else if let direct = result as? [[String: Any]] {
            arr = direct
        }

        return arr.compactMap { obj in
            guard let txid = obj["txid"] as? String else { return nil }
            let category = obj["category"] as? String ?? "unknown"
            let rawAmount = jsonToSatoshisAbs(obj["amount"])
            let fee = jsonToSatoshisAbs(obj["fee"])
            let address = obj["address"] as? String ?? ""
            let vout = obj["vout"] as? Int ?? 0
            let timestamp = obj["time"] as? Int64 ?? obj["blocktime"] as? Int64 ?? obj["timestamp"] as? Int64 ?? 0
            let inBlock = obj["blockhash"] as? String != nil
            let blockHash = obj["blockhash"] as? String ?? ""
            let finalized = obj["finalized"] as? Bool ?? false
            let blockHeight = obj["blockheight"] as? Int64 ?? 0
            let confirmations = obj["confirmations"] as? Int64 ?? 0
            let txStatus: TransactionStatus = (inBlock || finalized) ? .approved : .pending
            let rpcMemo = obj["memo"] as? String ?? ""

            let isSend = category == "send"
            let isConsolidate = category == "consolidate"
            let isBlockReward = category == "generate"

            if isConsolidate {
                return TransactionRecord(txid: txid, vout: vout, isSend: true, address: address,
                                         amount: rawAmount, fee: fee, timestamp: timestamp, status: txStatus,
                                         isConsolidate: true, blockHash: blockHash, blockHeight: blockHeight,
                                         confirmations: confirmations)
            }

            let displayAmount = isSend && fee > 0 ? max(rawAmount - fee, 0) : rawAmount
            if displayAmount == 0 && !isSend && !isBlockReward { return nil }

            return TransactionRecord(txid: txid, vout: vout, isSend: isSend, address: address,
                                     amount: displayAmount, fee: fee, timestamp: timestamp, status: txStatus,
                                     blockHash: blockHash, blockHeight: blockHeight, confirmations: confirmations,
                                     memo: isBlockReward ? "Block Reward" : rpcMemo)
        }
    }

    // MARK: - Amount parsing

    static func jsonToSatoshis(_ value: Any?) -> Int64 {
        guard let value = value else { return 0 }
        let s: String
        if let d = value as? Double { s = String(d) }
        else if let i = value as? Int64 { s = String(i) }
        else if let str = value as? String { s = str }
        else { return 0 }
        let trimmed = s.trimmingCharacters(in: .whitespaces)
        if trimmed.hasPrefix("-") { return 0 }
        return parseTimeString(trimmed)
    }

    private func jsonToSatoshis(_ value: Any?) -> Int64 { Self.jsonToSatoshis(value) }

    private func jsonToSatoshisAbs(_ value: Any?) -> Int64 {
        guard let value = value else { return 0 }
        let s: String
        if let d = value as? Double { s = String(abs(d)) }
        else if let i = value as? Int64 { s = String(abs(i)) }
        else if let str = value as? String { s = str.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "-")) }
        else { return 0 }
        return Self.parseTimeString(s)
    }

    static func parseTimeString(_ s: String) -> Int64 {
        if s.lowercased().contains("e") {
            return Int64((Double(s) ?? 0) * 100_000_000)
        }
        let parts = s.components(separatedBy: ".")
        let whole = Int64(parts[0]) ?? 0
        let frac: Int64
        if parts.count > 1 {
            let fracStr = parts[1].prefix(8).padding(toLength: 8, withPad: "0", startingAt: 0)
            frac = Int64(fracStr) ?? 0
        } else {
            frac = 0
        }
        return whole * 100_000_000 + frac
    }
}

enum MasternodeError: Error, LocalizedError {
    case rpcError(Int, String)
    case noResult
    case unexpectedResponse
    case networkError(String)

    var errorDescription: String? {
        switch self {
        case .rpcError(let code, let msg): return "RPC error \(code): \(msg)"
        case .noResult: return "No result in JSON-RPC response"
        case .unexpectedResponse: return "Unexpected response format"
        case .networkError(let msg): return "Network error: \(msg)"
        }
    }
}

extension Array {
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map {
            Array(self[$0..<Swift.min($0 + size, count)])
        }
    }
}
