import Foundation
import Network

// Peer discovery — mirrors Android PeerDiscovery.kt.
// Flow: collect candidates → parallel probe → consensus filter → rank → gossip → cache
actor PeerDiscovery {
    private static let mainnetAPIURL = "https://time-coin.io/api/peers"
    private static let testnetAPIURL = "https://time-coin.io/api/testnet/peers"
    private static let mainnetRPCPort = 24001
    private static let testnetRPCPort = 24101
    private static let healthTimeoutSecs = 4.0
    private static let maxBlockLag: Int64 = 3
    private static let maxPeers = 8

    nonisolated static let shared = PeerDiscovery()

    private var blacklist: Set<String> = []

    func blacklistPeer(endpoint: String) {
        let host = endpoint
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .components(separatedBy: ":").first ?? endpoint
        blacklist.insert(host)
    }

    func discoverAndRank(
        isTestnet: Bool,
        manualEndpoints: [String] = [],
        credentials: (String, String)? = nil,
        filesDir: URL? = nil,
        expectedGenesisHash: String? = nil
    ) async -> [PeerInfo] {
        let rpcPort = isTestnet ? Self.testnetRPCPort : Self.mainnetRPCPort

        // 1. Collect candidates
        var candidates = Set<String>(manualEndpoints)
        do {
            let apiPeers = try await Self.fetchFromAPI(isTestnet: isTestnet, rpcPort: rpcPort)
            candidates.formUnion(apiPeers)
            if let dir = filesDir { Self.saveCache(isTestnet: isTestnet, peers: Array(apiPeers), dir: dir) }
        } catch {
            if let dir = filesDir, let cached = Self.loadCache(isTestnet: isTestnet, dir: dir) {
                candidates.formUnion(cached)
            }
        }

        let bl = blacklist
        candidates = candidates.filter { ep in
            let host = ep.replacingOccurrences(of: "https://", with: "")
                .replacingOccurrences(of: "http://", with: "")
                .components(separatedBy: ":").first ?? ep
            return !bl.contains(host)
        }

        if candidates.isEmpty { return [] }

        // 2. Parallel probe
        var peerInfos = await probeAll(
            endpoints: Array(candidates), credentials: credentials,
            rpcPort: rpcPort, expectedGenesisHash: expectedGenesisHash)

        // 3. Consensus filter
        peerInfos = Self.consensusFilter(peers: peerInfos)

        // 4. Sort
        peerInfos = Self.rankPeers(peerInfos)

        // 5. Gossip from top 3 healthy peers
        let healthyPeers = peerInfos.filter { $0.isHealthy }.prefix(3)
        let knownEndpoints = Set(peerInfos.map { $0.endpoint })
        var gossipEndpoints = Set<String>()

        for peer in healthyPeers {
            let client = MasternodeClient(endpoint: peer.endpoint, credentials: credentials)
            if let peerAddresses = try? await client.getPeerAddresses() {
                for addr in peerAddresses {
                    let ip = addr.components(separatedBy: ":").first ?? addr
                    if bl.contains(ip) { continue }
                    let ep = "https://\(ip):\(rpcPort)"
                    if !knownEndpoints.contains(ep) { gossipEndpoints.insert(ep) }
                }
            }
        }

        if !gossipEndpoints.isEmpty {
            let gossipInfos = await probeAll(
                endpoints: Array(gossipEndpoints), credentials: credentials,
                rpcPort: rpcPort, expectedGenesisHash: expectedGenesisHash)
            let filtered = Self.consensusFilter(peers: gossipInfos, existingPeers: peerInfos)
            let merged = Dictionary(grouping: peerInfos + filtered, by: { $0.endpoint })
                .compactMap { $0.value.first }
            peerInfos = Self.rankPeers(merged)
        }

        // 6. Cap at max
        peerInfos = Array(peerInfos.prefix(Self.maxPeers))

        // 7. Cache healthy peers
        if let dir = filesDir {
            let healthyEndpoints = peerInfos.filter { $0.isHealthy }.map { $0.endpoint }
            if !healthyEndpoints.isEmpty { Self.saveCache(isTestnet: isTestnet, peers: healthyEndpoints, dir: dir) }
        }

        return peerInfos
    }

    private func probeAll(
        endpoints: [String], credentials: (String, String)?,
        rpcPort: Int, expectedGenesisHash: String?
    ) async -> [PeerInfo] {
        await withTaskGroup(of: PeerInfo.self) { group in
            for ep in endpoints {
                group.addTask {
                    await self.probePeer(endpoint: ep, credentials: credentials,
                                        rpcPort: rpcPort, expectedGenesisHash: expectedGenesisHash)
                }
            }
            var results: [PeerInfo] = []
            for await info in group { results.append(info) }
            return results
        }
    }

    private func probePeer(
        endpoint: String, credentials: (String, String)?,
        rpcPort: Int, expectedGenesisHash: String?
    ) async -> PeerInfo {
        let url = endpoint.hasPrefix("http") ? endpoint : "https://\(endpoint)"
        let host = url.replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
            .components(separatedBy: ":").first ?? url
        let port = Int(url.components(separatedBy: ":").last ?? "") ?? rpcPort

        let pingMs = await Self.measureTcpPing(host: host, port: port)

        var blockHeight: Int64? = nil
        var version: String? = nil
        var isHealthy = false
        var isSyncing = false
        var workingURL = url

        for scheme in ["https", "http"] {
            let tryURL = "\(scheme)://\(host):\(port)"
            let client = MasternodeClient(endpoint: tryURL, credentials: credentials)
            if let health = try? await withPeerTimeout(seconds: Self.healthTimeoutSecs) { try await client.healthCheck() } {
                blockHeight = health.blockHeight
                version = health.version
                isSyncing = health.isSyncing
                workingURL = tryURL

                if let expected = expectedGenesisHash,
                   let genesis = try? await withPeerTimeout(seconds: Self.healthTimeoutSecs, operation: { try await client.getGenesisHash() }) {
                    if genesis != expected {
                        await blacklistPeer(endpoint: tryURL)
                        blockHeight = nil
                        break
                    }
                }
                isHealthy = true
                break
            }
        }

        let wsAvailable = isHealthy ? await Self.checkWsAvailable(host: host, wsPort: port + 1) : false

        return PeerInfo(endpoint: workingURL, isActive: false, isHealthy: isHealthy,
                       wsAvailable: wsAvailable, pingMs: pingMs, blockHeight: blockHeight,
                       version: version, isSyncing: isSyncing)
    }

    private static func measureTcpPing(host: String, port: Int) async -> Int64? {
        await withCheckedContinuation { continuation in
            let start = Date()
            let conn = NWConnection(host: NWEndpoint.Host(host),
                                    port: NWEndpoint.Port(integerLiteral: UInt16(port)),
                                    using: .tcp)
            var done = false
            conn.stateUpdateHandler = { state in
                guard !done else { return }
                switch state {
                case .ready:
                    done = true
                    let ms = Int64(Date().timeIntervalSince(start) * 1000)
                    conn.cancel()
                    continuation.resume(returning: ms)
                case .failed, .cancelled:
                    done = true
                    continuation.resume(returning: nil)
                default: break
                }
            }
            conn.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + 1) {
                guard !done else { return }
                done = true
                conn.cancel()
                continuation.resume(returning: nil)
            }
        }
    }

    private static func checkWsAvailable(host: String, wsPort: Int) async -> Bool {
        let ping = await measureTcpPing(host: host, port: wsPort)
        return ping != nil
    }

    private static func consensusFilter(peers: [PeerInfo], existingPeers: [PeerInfo] = []) -> [PeerInfo] {
        let allHeights = (peers + existingPeers).compactMap { $0.blockHeight }
        guard let bestHeight = allHeights.max() else { return peers }
        return peers.filter { p in
            guard let h = p.blockHeight else { return true }
            return (bestHeight - h) <= maxBlockLag
        }
    }

    private static func rankPeers(_ peers: [PeerInfo]) -> [PeerInfo] {
        peers.sorted {
            if $0.isHealthy != $1.isHealthy { return $0.isHealthy }
            if $0.isSyncing != $1.isSyncing { return !$0.isSyncing }
            if $0.wsAvailable != $1.wsAvailable { return $0.wsAvailable }
            let p0 = $0.pingMs ?? Int64.max
            let p1 = $1.pingMs ?? Int64.max
            return p0 < p1
        }
    }

    private static func fetchFromAPI(isTestnet: Bool, rpcPort: Int) async throws -> [String] {
        let urlString = isTestnet ? testnetAPIURL : mainnetAPIURL
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        let (data, _) = try await URLSession.shared.data(from: url)
        guard let ips = try? JSONDecoder().decode([String].self, from: data) else {
            throw URLError(.cannotParseResponse)
        }
        guard !ips.isEmpty else { throw URLError(.zeroByteResource) }
        return ips.map { "https://\($0):\(rpcPort)" }
    }

    private static func saveCache(isTestnet: Bool, peers: [String], dir: URL) {
        let file = dir.appendingPathComponent(isTestnet ? "peers-testnet.json" : "peers-mainnet.json")
        try? JSONEncoder().encode(peers).write(to: file)
    }

    private static func loadCache(isTestnet: Bool, dir: URL) -> [String]? {
        let file = dir.appendingPathComponent(isTestnet ? "peers-testnet.json" : "peers-mainnet.json")
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode([String].self, from: data)
    }
}

private func withPeerTimeout<T>(seconds: Double, operation: @escaping () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            throw CancellationError()
        }
        guard let result = try await group.next() else { throw CancellationError() }
        group.cancelAll()
        return result
    }
}
