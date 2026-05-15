import Foundation

// WebSocket client for real-time notifications.
// Mirrors Android WsNotificationClient: wss:// first then ws:// fallback,
// exponential backoff (1s→60s), batch subscribe, 25s ping heartbeat.
class WsNotificationClient: NSObject {
    enum Event {
        case connected(url: String)
        case disconnected(reason: String)
        case transactionReceived(TxNotification)
        case utxoFinalized(txid: String, outputIndex: Int)
        case transactionRejected(txid: String, reason: String)
        case paymentRequestReceived(IncomingPaymentRequest)
        case paymentRequestCancelled(requestId: String)
        case paymentRequestResponse(requestId: String, accepted: Bool)
        case paymentRequestViewed(requestId: String)
    }

    var onEvent: ((Event) -> Void)?

    private let wsUrl: String
    private let addresses: [String]
    private var webSocketTask: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var running = false
    private var reconnectTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?

    init(wsUrl: String, addresses: [String]) {
        self.wsUrl = wsUrl
        self.addresses = addresses
        super.init()
    }

    func start() {
        running = true
        reconnectTask = Task { await connectLoop() }
    }

    func stop() {
        running = false
        pingTask?.cancel()
        reconnectTask?.cancel()
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
    }

    func updateAddresses(_ newAddresses: [String]) {
        guard let task = webSocketTask else { return }
        let batchMsg = [
            "method": "subscribe_batch",
            "params": ["addresses": newAddresses] as [String: Any]
        ] as [String: Any]
        if let data = try? JSONSerialization.data(withJSONObject: batchMsg),
           let text = String(data: data, encoding: .utf8) {
            task.send(.string(text)) { _ in }
        }
    }

    // MARK: - Connection loop

    private func connectLoop() async {
        var backoffSecs: UInt64 = 1
        let maxBackoff: UInt64 = 60

        while running && !Task.isCancelled {
            let connected = await connect()
            if connected {
                backoffSecs = 1
                while running && webSocketTask != nil && !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
            pingTask?.cancel()
            guard running else { break }
            try? await Task.sleep(nanoseconds: backoffSecs * 1_000_000_000)
            backoffSecs = min(backoffSecs * 2, maxBackoff)
        }
    }

    private func connect() async -> Bool {
        let wssUrl = wsUrl.hasPrefix("ws://") ? wsUrl.replacingOccurrences(of: "ws://", with: "wss://", range: wsUrl.range(of: "ws://")) : wsUrl
        let wsPlainUrl = wsUrl.hasPrefix("wss://") ? wsUrl.replacingOccurrences(of: "wss://", with: "ws://", range: wsUrl.range(of: "wss://")) : wsUrl

        if await tryConnect(url: wssUrl) { return true }
        return await tryConnect(url: wsPlainUrl)
    }

    private func tryConnect(url: String) async -> Bool {
        guard let wsURL = URL(string: url) else { return false }

        let config = URLSessionConfiguration.default
        // Trust all certs for self-signed masternode certs
        let delegate = TrustAllDelegate()
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        self.urlSession = session

        let task = session.webSocketTask(with: wsURL)
        self.webSocketTask = task
        task.resume()

        // Wait for open confirmation via first message or timeout
        return await withCheckedContinuation { continuation in
            var resolved = false
            func resolve(_ value: Bool) {
                guard !resolved else { return }
                resolved = true
                continuation.resume(returning: value)
            }

            // Subscribe after connecting
            Task {
                try? await Task.sleep(nanoseconds: 500_000_000)  // brief delay for handshake
                guard self.webSocketTask == task else { resolve(false); return }

                self.onEvent?(.connected(url: url))

                let batchMsg = [
                    "method": "subscribe_batch",
                    "params": ["addresses": self.addresses] as [String: Any]
                ] as [String: Any]
                if let data = try? JSONSerialization.data(withJSONObject: batchMsg),
                   let text = String(data: data, encoding: .utf8) {
                    task.send(.string(text)) { _ in }
                }

                self.startPingLoop(task: task)
                self.receiveLoop(task: task)
                resolve(true)
            }

            // Failure fallback
            Task {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                resolve(false)
            }
        }
    }

    private func receiveLoop(task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self = self, self.running, self.webSocketTask == task else { return }
            switch result {
            case .success(let message):
                switch message {
                case .string(let text): self.handleMessage(text)
                case .data(let data):
                    if let text = String(data: data, encoding: .utf8) { self.handleMessage(text) }
                @unknown default: break
                }
                self.receiveLoop(task: task)
            case .failure(let error):
                self.webSocketTask = nil
                self.onEvent?(.disconnected(reason: error.localizedDescription))
            }
        }
    }

    private func startPingLoop(task: URLSessionWebSocketTask) {
        pingTask?.cancel()
        pingTask = Task {
            while running && webSocketTask == task && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                guard running && !Task.isCancelled else { break }
                let ping = "{\"method\":\"ping\",\"params\":{}}"
                task.send(.string(ping)) { _ in }
            }
        }
    }

    private func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let msg = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        if let error = msg["error"] as? String, error == "capacity" {
            webSocketTask?.cancel(with: .normalClosure, reason: nil)
            webSocketTask = nil
            onEvent?(.disconnected(reason: "Server at capacity"))
            return
        }

        guard let type = msg["type"] as? String else { return }
        let msgData = msg["data"] as? [String: Any]

        switch type {
        case "pong", "subscribed", "subscribed_batch": break

        case "tx_notification":
            guard let d = msgData,
                  let txid = d["txid"] as? String else { break }
            let notif = TxNotification(
                txid: txid,
                address: d["address"] as? String ?? "",
                amount: MasternodeClient.jsonToSatoshis(d["amount"]),
                outputIndex: d["output_index"] as? Int ?? 0,
                timestamp: d["timestamp"] as? Int64 ?? 0
            )
            onEvent?(.transactionReceived(notif))

        case "utxo_finalized":
            guard let d = msgData, let txid = d["txid"] as? String else { break }
            onEvent?(.utxoFinalized(txid: txid, outputIndex: d["output_index"] as? Int ?? 0))

        case "tx_rejected", "tx_declined":
            guard let d = msgData, let txid = d["txid"] as? String else { break }
            onEvent?(.transactionRejected(txid: txid, reason: d["reason"] as? String ?? ""))

        case "payment_request":
            guard let d = msgData, let id = d["id"] as? String else { break }
            let req = IncomingPaymentRequest(
                id: id,
                requesterAddress: d["requester_address"] as? String ?? "",
                payerAddress: d["payer_address"] as? String ?? "",
                amountSats: d["amount"] as? Int64 ?? MasternodeClient.jsonToSatoshis(d["amount"]),
                memo: d["memo"] as? String ?? "",
                requesterName: d["requester_name"] as? String ?? "",
                timestamp: d["timestamp"] as? Int64 ?? Int64(Date().timeIntervalSince1970)
            )
            onEvent?(.paymentRequestReceived(req))

        case "payment_request_cancelled":
            if let id = msgData?["id"] as? String { onEvent?(.paymentRequestCancelled(requestId: id)) }

        case "payment_request_response":
            guard let d = msgData, let id = d["id"] as? String else { break }
            onEvent?(.paymentRequestResponse(requestId: id, accepted: d["accepted"] as? Bool ?? false))

        case "payment_request_viewed":
            if let id = msgData?["id"] as? String { onEvent?(.paymentRequestViewed(requestId: id)) }

        default: break
        }
    }
}

// Accepts self-signed masternode TLS certificates
private class TrustAllDelegate: NSObject, URLSessionDelegate {
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}

struct TxNotification {
    let txid: String
    let address: String
    let amount: Int64
    let outputIndex: Int
    let timestamp: Int64
}

struct IncomingPaymentRequest {
    let id: String
    let requesterAddress: String
    let payerAddress: String
    let amountSats: Int64
    let memo: String
    let requesterName: String
    let timestamp: Int64
}
