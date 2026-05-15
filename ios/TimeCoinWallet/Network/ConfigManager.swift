import Foundation

// Parses Bitcoin-style time.conf. Mirrors Android ConfigManager exactly.
// Mainnet: {baseDir}/time.conf — Testnet: {baseDir}/testnet/time.conf
enum ConfigManager {
    private static let configFilename = "time.conf"
    private static let testnetSubdir = "testnet"
    private static let mainnetPort = 24001
    private static let testnetPort = 24101

    struct Config {
        var peers: [String] = []
        var rpcUser: String? = nil
        var rpcPassword: String? = nil
        var testnet: Bool = false
    }

    static func load(dir: URL, isTestnet: Bool = false) -> Config {
        let file = configFile(dir: dir, isTestnet: isTestnet)
        guard let content = try? String(contentsOf: file, encoding: .utf8) else {
            return Config(testnet: isTestnet)
        }
        var config = parse(content)
        config.testnet = isTestnet
        return config
    }

    static func save(dir: URL, config: Config) {
        let cfgDir = configDir(dir: dir, isTestnet: config.testnet)
        try? FileManager.default.createDirectory(at: cfgDir, withIntermediateDirectories: true)
        let file = cfgDir.appendingPathComponent(configFilename)
        try? serialize(config).write(to: file, atomically: true, encoding: .utf8)
    }

    static func readRaw(dir: URL, isTestnet: Bool = false) -> String {
        let file = configFile(dir: dir, isTestnet: isTestnet)
        return (try? String(contentsOf: file, encoding: .utf8)) ?? defaultConfig(isTestnet: isTestnet)
    }

    static func writeRaw(dir: URL, content: String, isTestnet: Bool = false) {
        let cfgDir = configDir(dir: dir, isTestnet: isTestnet)
        try? FileManager.default.createDirectory(at: cfgDir, withIntermediateDirectories: true)
        try? content.write(to: cfgDir.appendingPathComponent(configFilename), atomically: true, encoding: .utf8)
    }

    static func manualEndpoints(config: Config) -> [String] {
        let port = config.testnet ? testnetPort : mainnetPort
        return config.peers.map { peer in
            if peer.hasPrefix("http://") || peer.hasPrefix("https://") { return peer }
            if peer.contains(":") { return "http://\(peer)" }
            return "http://\(peer):\(port)"
        }
    }

    static func rpcCredentials(config: Config) -> (String, String)? {
        guard let user = config.rpcUser, let pass = config.rpcPassword else { return nil }
        return (user, pass)
    }

    // MARK: - Internal

    static func parse(_ contents: String) -> Config {
        var peers: [String] = []
        var rpcUser: String? = nil
        var rpcPassword: String? = nil

        for raw in contents.components(separatedBy: .newlines) {
            var line = raw
            if let commentIdx = line.firstIndex(of: "#") { line = String(line[..<commentIdx]) }
            line = line.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty, let eqIdx = line.firstIndex(of: "=") else { continue }
            let key = String(line[..<eqIdx]).trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: eqIdx)...]).trimmingCharacters(in: .whitespaces)
            guard !value.isEmpty else { continue }
            switch key {
            case "addnode": peers.append(value)
            case "rpcuser": rpcUser = value
            case "rpcpassword": rpcPassword = value
            default: break
            }
        }
        return Config(peers: peers, rpcUser: rpcUser, rpcPassword: rpcPassword)
    }

    static func serialize(_ config: Config) -> String {
        var lines = ["# TIME Coin Wallet Configuration", "# Edit this file to add masternode peers manually.", ""]
        lines.append("# Masternode peers (one per line)")
        if config.peers.isEmpty {
            let port = config.testnet ? testnetPort : mainnetPort
            lines.append("# addnode=64.91.241.10:\(port)")
        } else {
            config.peers.forEach { lines.append("addnode=\($0)") }
        }
        if let user = config.rpcUser { lines.append(""); lines.append("rpcuser=\(user)") }
        if let pass = config.rpcPassword { lines.append("rpcpassword=\(pass)") }
        return lines.joined(separator: "\n")
    }

    private static func configDir(dir: URL, isTestnet: Bool) -> URL {
        isTestnet ? dir.appendingPathComponent(testnetSubdir) : dir
    }

    private static func configFile(dir: URL, isTestnet: Bool) -> URL {
        configDir(dir: dir, isTestnet: isTestnet).appendingPathComponent(configFilename)
    }

    private static func defaultConfig(isTestnet: Bool) -> String {
        serialize(Config(testnet: isTestnet))
    }
}
