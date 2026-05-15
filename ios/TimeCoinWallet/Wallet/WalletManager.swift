import Foundation

// Holds keys in memory, derives HD addresses, creates/signs transactions.
// File format: same as Android — encrypted JSON with salt/nonce/ciphertext fields.
class WalletManager {
    let network: NetworkType
    private let mnemonic: String
    private var nextAddressIndex: Int
    private var addresses: [String] = []
    private var utxos: [Utxo] = []
    private(set) var balance: Int64 = 0

    static let gapLimit = 20
    static let walletFilename = "time-wallet.dat"

    var primaryAddress: String { addresses.first ?? "" }

    private init(mnemonic: String, network: NetworkType, nextAddressIndex: Int = 1) {
        self.mnemonic = mnemonic
        self.network = network
        self.nextAddressIndex = nextAddressIndex

        // Derive all previously generated addresses
        for i in 0..<nextAddressIndex {
            if let addr = try? MnemonicHelper.deriveAddress(phrase: mnemonic, index: i, network: network) {
                addresses.append(addr)
            }
        }
    }

    // MARK: - Factory

    static func create(mnemonic: String, network: NetworkType) throws -> WalletManager {
        guard MnemonicHelper.validate(mnemonic) else {
            throw WalletError.invalidMnemonic
        }
        return WalletManager(mnemonic: mnemonic, network: network)
    }

    static func load(dir: URL, network: NetworkType, password: String?) throws -> WalletManager {
        let walletFile = walletFileURL(dir: dir, network: network)
        guard FileManager.default.fileExists(atPath: walletFile.path) else {
            throw WalletError.fileNotFound
        }
        let content = try String(contentsOf: walletFile, encoding: .utf8)
        let json: String

        if password != nil {
            let envelope = try JSONDecoder().decode(EncryptedEnvelope.self, from: Data(content.utf8))
            let salt = try Data(hexString: envelope.salt)
            let nonce = try Data(hexString: envelope.nonce)
            let ciphertext = try Data(hexString: envelope.ciphertext)
            let encrypted = WalletEncryption.EncryptedData(salt: salt, nonce: nonce, ciphertext: ciphertext, version: envelope.version)
            let plaintext = try WalletEncryption.decrypt(encrypted: encrypted, password: password!)
            json = String(data: plaintext, encoding: .utf8) ?? ""
        } else {
            json = content
        }

        let data = try JSONDecoder().decode(WalletFileData.self, from: Data(json.utf8))
        let net = NetworkType(rawValue: data.network) ?? network
        return WalletManager(mnemonic: data.mnemonic, network: net, nextAddressIndex: data.nextAddressIndex)
    }

    static func exists(dir: URL, network: NetworkType) -> Bool {
        FileManager.default.fileExists(atPath: walletFileURL(dir: dir, network: network).path)
    }

    static func isEncrypted(dir: URL, network: NetworkType) -> Bool {
        let file = walletFileURL(dir: dir, network: network)
        guard let content = try? String(contentsOf: file, encoding: .utf8) else { return false }
        return content.contains("\"salt\"")
    }

    // MARK: - Persistence

    func save(dir: URL, password: String?) throws {
        let targetDir = Self.walletDir(dir: dir, network: network)
        try FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true)
        let walletFile = targetDir.appendingPathComponent(Self.walletFilename)

        let data = WalletFileData(version: 3, network: network.rawValue, mnemonic: mnemonic, nextAddressIndex: nextAddressIndex)
        let plaintext = try JSONEncoder().encode(data)

        if let password = password {
            let encrypted = try WalletEncryption.encrypt(plaintext: plaintext, password: password)
            let envelope = EncryptedEnvelope(
                salt: encrypted.salt.hexString,
                nonce: encrypted.nonce.hexString,
                ciphertext: encrypted.ciphertext.hexString,
                version: encrypted.version
            )
            let json = try JSONEncoder().encode(envelope)
            try json.write(to: walletFile)
        } else {
            try plaintext.write(to: walletFile)
        }
    }

    @discardableResult
    static func backupWallet(dir: URL, network: NetworkType) -> URL? {
        let source = walletFileURL(dir: dir, network: network)
        guard FileManager.default.fileExists(atPath: source.path) else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd_HHmmss"
        let timestamp = formatter.string(from: Date())
        let netTag = network == .testnet ? "testnet" : "mainnet"
        let backupURL = walletDir(dir: dir, network: network)
            .appendingPathComponent("time-wallet-\(netTag)-\(timestamp).dat")
        try? FileManager.default.copyItem(at: source, to: backupURL)
        return backupURL
    }

    static func deleteWallet(dir: URL, network: NetworkType) -> Bool {
        backupWallet(dir: dir, network: network)
        let file = walletFileURL(dir: dir, network: network)
        guard FileManager.default.fileExists(atPath: file.path) else { return true }
        try? FileManager.default.removeItem(at: file)
        return !FileManager.default.fileExists(atPath: file.path)
    }

    static func migrateIfNeeded(baseDir: URL) {
        let legacyTestnet = baseDir.appendingPathComponent("time-wallet-testnet.dat")
        guard FileManager.default.fileExists(atPath: legacyTestnet.path) else { return }
        let testnetDir = baseDir.appendingPathComponent("testnet")
        let target = testnetDir.appendingPathComponent(walletFilename)
        guard !FileManager.default.fileExists(atPath: target.path) else { return }
        try? FileManager.default.createDirectory(at: testnetDir, withIntermediateDirectories: true)
        try? FileManager.default.moveItem(at: legacyTestnet, to: target)
    }

    static func walletDir(dir: URL, network: NetworkType) -> URL {
        network == .testnet ? dir.appendingPathComponent("testnet") : dir
    }

    static func walletFileURL(dir: URL, network: NetworkType) -> URL {
        walletDir(dir: dir, network: network).appendingPathComponent(walletFilename)
    }

    // MARK: - Addresses

    func getAddresses() -> [String] { addresses }

    func deriveAddressAt(index: Int) -> String? {
        try? MnemonicHelper.deriveAddress(phrase: mnemonic, index: index, network: network)
    }

    func generateAddress() -> String {
        let addr = (try? MnemonicHelper.deriveAddress(phrase: mnemonic, index: nextAddressIndex, network: network)) ?? ""
        addresses.append(addr)
        nextAddressIndex += 1
        return addr
    }

    func deriveKeypair(index: Int) -> Keypair? {
        try? MnemonicHelper.deriveKeypairBip44(phrase: mnemonic, index: index)
    }

    func getPublicKeyHex(address: String) -> String? {
        guard let index = addresses.firstIndex(of: address),
              let kp = deriveKeypair(index: index) else { return nil }
        return kp.publicKeyBytes.hexString
    }

    func trimAddresses(count: Int) {
        guard count >= 1 else { return }
        while addresses.count > count { addresses.removeLast() }
        nextAddressIndex = count
    }

    // MARK: - UTXOs

    func setUtxos(_ newUtxos: [Utxo]) {
        utxos = newUtxos
        balance = utxos.filter { $0.spendable }.reduce(0) { $0 + $1.amount }
    }

    func getUtxos() -> [Utxo] { utxos }

    // MARK: - Transaction creation

    func createTransaction(
        toAddress: String,
        amount: Int64,
        feeSchedule: FeeSchedule = .default,
        fromAddress: String? = nil
    ) throws -> TimeCoinTransaction {
        let fee = feeSchedule.calculateFee(sendAmount: amount)
        let totalNeeded = amount + fee

        let spendable = utxos
            .filter { $0.spendable && (fromAddress == nil || $0.address == fromAddress) }
            .sorted { $0.amount > $1.amount }

        var accumulated: Int64 = 0
        var selected: [Utxo] = []
        for utxo in spendable {
            selected.append(utxo)
            accumulated += utxo.amount
            if accumulated >= totalNeeded { break }
        }

        guard accumulated >= totalNeeded else {
            throw WalletError.insufficientFunds(have: accumulated, need: totalNeeded)
        }

        var tx = TimeCoinTransaction()

        for utxo in selected {
            let txidBytes = try Data(hexString: utxo.txid)
            tx.addInput(TxInput.new(prevTx: txidBytes, prevIndex: utxo.vout))
        }

        let recipientAddr = try Address.fromString(toAddress)
        guard recipientAddr.network == network else {
            throw WalletError.networkMismatch(recipientAddr.network, network)
        }
        tx.addOutput(TxOutput.new(amount: amount, address: recipientAddr))

        let change = accumulated - totalNeeded
        if change > 0 {
            let changeAddrStr = fromAddress ?? primaryAddress
            let changeAddr = try Address.fromString(changeAddrStr)
            tx.addOutput(TxOutput.new(amount: change, address: changeAddr))
        }

        let signingIdx: Int
        if let fa = fromAddress, fa != primaryAddress, let idx = addresses.firstIndex(of: fa) {
            signingIdx = idx
        } else {
            signingIdx = 0
        }
        guard let signingKeypair = deriveKeypair(index: signingIdx) else {
            throw WalletError.keypairDerivationFailed
        }
        try tx.signAll(keypair: signingKeypair)

        // Remove spent UTXOs
        let spentIds = Set(selected.map { "\($0.txid):\($0.vout)" })
        utxos.removeAll { spentIds.contains("\($0.txid):\($0.vout)") }
        balance = utxos.filter { $0.spendable }.reduce(0) { $0 + $1.amount }

        return tx
    }
}

enum WalletError: Error, LocalizedError {
    case invalidMnemonic
    case fileNotFound
    case insufficientFunds(have: Int64, need: Int64)
    case networkMismatch(NetworkType, NetworkType)
    case keypairDerivationFailed

    var errorDescription: String? {
        switch self {
        case .invalidMnemonic: return "Invalid mnemonic phrase"
        case .fileNotFound: return "Wallet file not found"
        case .insufficientFunds(let have, let need):
            return "Insufficient funds: have \(have.timeString), need \(need.timeString)"
        case .networkMismatch(let addr, let wallet):
            return "Address is for \(addr), but wallet is on \(wallet)"
        case .keypairDerivationFailed: return "Keypair derivation failed"
        }
    }
}

private struct WalletFileData: Codable {
    let version: Int
    let network: String
    let mnemonic: String
    let nextAddressIndex: Int
}

private struct EncryptedEnvelope: Codable {
    let salt: String
    let nonce: String
    let ciphertext: String
    let version: Int
}
