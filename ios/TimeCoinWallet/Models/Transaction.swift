import Foundation
import CryptoKit

// Transaction types — matches desktop wallet transaction.rs and Android Transaction.kt.
// TXID = SHA-256 of JSON serialization (same as masternode).
// Signing: v2 format = CHAIN_ID(u32 LE=1) || txid || input_index(u32 LE) || sha256(bincode(outputs))

struct OutPoint: Codable, Equatable {
    let txid: Data   // 32 bytes
    let vout: Int

    init(txid: Data, vout: Int) {
        precondition(txid.count == 32)
        self.txid = txid; self.vout = vout
    }
}

struct TxInput: Codable, Equatable {
    let previousOutput: OutPoint
    var scriptSig: Data     // [32-byte pubkey || 64-byte signature]
    let sequence: UInt64

    init(previousOutput: OutPoint, scriptSig: Data = Data(), sequence: UInt64 = 0xFFFFFFFF) {
        self.previousOutput = previousOutput; self.scriptSig = scriptSig; self.sequence = sequence
    }

    static func new(prevTx: Data, prevIndex: Int) -> TxInput {
        TxInput(previousOutput: OutPoint(txid: prevTx, vout: prevIndex))
    }

    enum CodingKeys: String, CodingKey {
        case previousOutput = "previous_output"
        case scriptSig = "script_sig"
        case sequence
    }
}

struct TxOutput: Codable, Equatable {
    let value: Int64
    let scriptPubkey: Data   // address string as UTF-8 bytes

    var addressString: String { String(data: scriptPubkey, encoding: .utf8) ?? "" }

    static func new(amount: Int64, address: Address) -> TxOutput {
        TxOutput(value: amount, scriptPubkey: Data(address.description.utf8))
    }

    enum CodingKeys: String, CodingKey {
        case value
        case scriptPubkey = "script_pubkey"
    }
}

struct TimeCoinTransaction: Codable {
    let version: Int
    var inputs: [TxInput]
    var outputs: [TxOutput]
    let lockTime: Int
    let timestamp: Int64

    init(version: Int = 2, lockTime: Int = 0,
         timestamp: Int64 = Int64(Date().timeIntervalSince1970)) {
        self.version = version; self.inputs = []; self.outputs = []
        self.lockTime = lockTime; self.timestamp = timestamp
    }

    mutating func addInput(_ input: TxInput) { inputs.append(input) }

    mutating func addOutput(_ output: TxOutput) {
        precondition(output.value > 0, "Output value must be > 0")
        outputs.append(output)
    }

    // SHA-256 of JSON serialization (matches masternode txid())
    func hash() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = []
        let json = try encoder.encode(self)
        return Data(SHA256.hash(data: json))
    }

    func txid() throws -> String {
        try hash().hexString
    }

    var totalOutput: Int64 { outputs.reduce(0) { $0 + $1.value } }

    // Bincode-format outputs: u64 LE count, then for each: u64 LE value, u64 LE len, bytes
    private func outputsToBincode() -> Data {
        var buf = Data()
        func writeU64LE(_ v: Int64) {
            var val = UInt64(bitPattern: v)
            withUnsafeBytes(of: &val) { buf.append(contentsOf: $0) }
        }
        func writeU64LEu(_ v: UInt64) {
            var val = v
            withUnsafeBytes(of: &val) { buf.append(contentsOf: $0) }
        }
        writeU64LEu(UInt64(outputs.count))
        for output in outputs {
            writeU64LE(output.value)
            writeU64LEu(UInt64(output.scriptPubkey.count))
            buf.append(output.scriptPubkey)
        }
        return buf
    }

    // v2 signing message: CHAIN_ID(u32 LE=1) || txid || input_index(u32 LE) || sha256(bincode(outputs))
    private func createSignatureMessage(inputIdx: Int) throws -> Data {
        // Clear all script sigs for signing
        var signingTx = self
        signingTx.inputs = inputs.map { TxInput(previousOutput: $0.previousOutput, scriptSig: Data(), sequence: $0.sequence) }
        let txHash = try signingTx.hash()

        var indexBytes = UInt32(inputIdx).littleEndian
        let indexData = withUnsafeBytes(of: &indexBytes) { Data($0) }

        let outputsHash = Data(SHA256.hash(data: outputsToBincode()))

        var msg = Data()
        if version >= 2 {
            var chainId = UInt32(1).littleEndian
            msg.append(contentsOf: withUnsafeBytes(of: &chainId) { Data($0) })
        }
        msg.append(txHash)
        msg.append(indexData)
        msg.append(outputsHash)
        return msg
    }

    mutating func sign(keypair: Keypair, inputIndex: Int) throws {
        precondition(inputIndex < inputs.count)
        let message = try createSignatureMessage(inputIdx: inputIndex)
        let signature = try keypair.sign(message)
        let pubkey = keypair.publicKeyBytes

        // script_sig = [32-byte pubkey || 64-byte signature]
        var scriptSig = Data(count: 96)
        scriptSig.replaceSubrange(0..<32, with: pubkey)
        scriptSig.replaceSubrange(32..<96, with: signature)
        inputs[inputIndex] = TxInput(
            previousOutput: inputs[inputIndex].previousOutput,
            scriptSig: scriptSig,
            sequence: inputs[inputIndex].sequence
        )
    }

    mutating func signAll(keypair: Keypair) throws {
        for i in inputs.indices { try sign(keypair: keypair, inputIndex: i) }
    }

    func verifyInput(inputIndex: Int) throws -> Bool {
        guard inputIndex < inputs.count else { return false }
        let input = inputs[inputIndex]
        guard input.scriptSig.count == 96 else { return false }
        let pubkeyBytes = input.scriptSig.prefix(32)
        let signature = input.scriptSig.suffix(64)
        let message = try createSignatureMessage(inputIdx: inputIndex)
        return Keypair.verifyWithPublicKey(Data(pubkeyBytes), message: message, signature: Data(signature))
    }

    enum CodingKeys: String, CodingKey {
        case version, inputs, outputs, timestamp
        case lockTime = "lock_time"
    }
}
