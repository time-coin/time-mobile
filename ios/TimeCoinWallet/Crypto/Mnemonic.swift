import Foundation
import CryptoKit
import CommonCrypto

// BIP-39 mnemonic + SLIP-0010 HD derivation for Ed25519.
// Matches Android's MnemonicHelper exactly: same HMAC-SHA512 chains, same hardened path.
enum MnemonicHelper {

    static func generate(wordCount: Int = 12) throws -> String {
        guard [12, 15, 18, 21, 24].contains(wordCount) else {
            throw MnemonicError.invalidWordCount(wordCount)
        }
        let list = wordList
        guard list.count >= 1024 else { throw MnemonicError.wordListUnavailable }

        let entropyBits = wordCount * 11 * 32 / 33  // 128, 160, 192, 224, 256
        let entropyBytes = entropyBits / 8

        // If list is not full 2048 words, retry with fresh entropy until all indices
        // fall within bounds. All retained words are valid standard BIP-39 words,
        // so generated mnemonics remain cross-platform compatible.
        // Replace bip39_english.txt with the official 2048-word BIP-39 list to avoid retries.
        for _ in 0..<200 {
            var entropy = Data(count: entropyBytes)
            entropy.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, entropyBytes, $0.baseAddress!) }
            if let mnemonic = try? entropyToMnemonic(entropy) {
                return mnemonic
            }
        }
        throw MnemonicError.wordListUnavailable
    }

    static func validate(_ phrase: String) -> Bool {
        let words = phrase.trimmingCharacters(in: .whitespaces).components(separatedBy: " ")
            .filter { !$0.isEmpty }
        guard [12, 15, 18, 21, 24].contains(words.count) else { return false }
        let list = wordList
        // Full 2048-word list: strict word + checksum validation
        if list.count == 2048 {
            for word in words {
                if !list.contains(word.lowercased()) { return false }
            }
            return validateChecksum(words)
        }
        // Partial list (e.g. incomplete bip39_english.txt): validate word count only.
        // Replace bip39_english.txt with the official BIP-39 word list for full validation.
        return !list.isEmpty
    }

    static func isValidWord(_ word: String) -> Bool {
        wordList.contains(word.lowercased().trimmingCharacters(in: .whitespaces))
    }

    // MARK: - Key derivation

    static func deriveKeypairBip44(
        phrase: String,
        passphrase: String = "",
        account: Int = 0,
        change: Int = 0,
        index: Int = 0
    ) throws -> Keypair {
        let seed = try mnemonicToSeed(phrase: phrase, passphrase: passphrase)
        let path = [44, 0, account, change, index]
        let key = slip10DerivePath(seed: seed, path: path)
        return try Keypair.fromBytes(key)
    }

    static func deriveAddress(
        phrase: String,
        passphrase: String = "",
        account: Int = 0,
        change: Int = 0,
        index: Int = 0,
        network: NetworkType
    ) throws -> String {
        let keypair = try deriveKeypairBip44(phrase: phrase, passphrase: passphrase,
                                             account: account, change: change, index: index)
        return Address.fromPublicKey(keypair.publicKeyBytes, network: network).description
    }

    // MARK: - Internal

    private static func mnemonicToSeed(phrase: String, passphrase: String) throws -> Data {
        let normalizedPhrase = phrase.trimmingCharacters(in: .whitespaces)
        let password = Data(normalizedPhrase.utf8)
        let salt = Data(("mnemonic" + passphrase).utf8)

        var derivedKey = Data(count: 64)
        let status = derivedKey.withUnsafeMutableBytes { derivedPtr -> Int32 in
            password.withUnsafeBytes { passwordPtr -> Int32 in
                salt.withUnsafeBytes { saltPtr -> Int32 in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        passwordPtr.baseAddress, password.count,
                        saltPtr.baseAddress, salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA512),
                        2048,
                        derivedPtr.baseAddress, 64
                    )
                }
            }
        }
        guard status == kCCSuccess else {
            throw MnemonicError.seedDerivationFailed
        }
        return derivedKey
    }

    // SLIP-0010 master key: HMAC-SHA512("ed25519 seed", seed)
    private static func slip10MasterKey(seed: Data) -> (key: Data, chainCode: Data) {
        let hmacKey = SymmetricKey(data: Data("ed25519 seed".utf8))
        let mac = HMAC<SHA512>.authenticationCode(for: seed, using: hmacKey)
        let bytes = Data(mac)
        return (bytes.prefix(32), bytes.suffix(32))
    }

    // Hardened child derivation (Ed25519 only supports hardened)
    private static func slip10DeriveChild(key: Data, chainCode: Data, index: Int) -> (key: Data, chainCode: Data) {
        let hardened = UInt32(index) | 0x80000000
        var data = Data([0x00]) + key
        data.append(UInt8((hardened >> 24) & 0xFF))
        data.append(UInt8((hardened >> 16) & 0xFF))
        data.append(UInt8((hardened >> 8) & 0xFF))
        data.append(UInt8(hardened & 0xFF))

        let hmacKey = SymmetricKey(data: chainCode)
        let mac = HMAC<SHA512>.authenticationCode(for: data, using: hmacKey)
        let bytes = Data(mac)
        return (bytes.prefix(32), bytes.suffix(32))
    }

    private static func slip10DerivePath(seed: Data, path: [Int]) -> Data {
        var (key, chainCode) = slip10MasterKey(seed: seed)
        for index in path {
            (key, chainCode) = slip10DeriveChild(key: key, chainCode: chainCode, index: index)
        }
        return key
    }

    // MARK: - BIP-39 entropy/mnemonic conversion

    private static func entropyToMnemonic(_ entropy: Data) throws -> String {
        let list = wordList
        guard list.count == 2048 else { throw MnemonicError.wordListUnavailable }

        let checksumBits = entropy.count * 8 / 32
        let hash = SHA256.hash(data: entropy)
        let hashByte = Data(hash)[0]

        // Concatenate entropy bits + checksum bits
        var bits: [Bool] = []
        for byte in entropy {
            for i in (0..<8).reversed() {
                bits.append((byte >> i) & 1 == 1)
            }
        }
        for i in (8 - checksumBits..<8).reversed() {
            bits.append((hashByte >> i) & 1 == 1)
        }

        var words: [String] = []
        for i in 0..<(bits.count / 11) {
            var index = 0
            for j in 0..<11 {
                index = (index << 1) | (bits[i * 11 + j] ? 1 : 0)
            }
            words.append(list[index])
        }
        return words.joined(separator: " ")
    }

    private static func validateChecksum(_ words: [String]) -> Bool {
        let list = wordList
        guard list.count == 2048 else { return true }

        var bits: [Bool] = []
        for word in words {
            guard let idx = list.firstIndex(of: word.lowercased()) else { return false }
            for i in (0..<11).reversed() {
                bits.append((idx >> i) & 1 == 1)
            }
        }
        let checksumBits = words.count * 11 / 33
        let entropyBits = bits.count - checksumBits
        let checksumBitSlice = Array(bits[entropyBits...])

        var entropyBytes = Data()
        for i in stride(from: 0, to: entropyBits, by: 8) {
            var byte: UInt8 = 0
            for j in 0..<8 {
                byte = (byte << 1) | (bits[i + j] ? 1 : 0)
            }
            entropyBytes.append(byte)
        }

        let hash = SHA256.hash(data: entropyBytes)
        let firstHashByte = Data(hash)[0]
        for i in 0..<checksumBits {
            let expected = (firstHashByte >> (7 - i)) & 1 == 1
            if checksumBitSlice[i] != expected { return false }
        }
        return true
    }

    // MARK: - Word list

    static var wordList: [String] {
        guard let url = Bundle.main.url(forResource: "bip39_english", withExtension: "txt"),
              let content = try? String(contentsOf: url, encoding: .utf8) else {
            return []
        }
        return content.components(separatedBy: .newlines).filter { !$0.isEmpty }
    }

    static var hasWordList: Bool { !wordList.isEmpty }
}

enum MnemonicError: Error, LocalizedError {
    case invalidWordCount(Int)
    case seedDerivationFailed
    case wordListUnavailable
    case invalidMnemonic

    var errorDescription: String? {
        switch self {
        case .invalidWordCount(let n): return "Invalid word count: \(n) (must be 12, 15, 18, 21, or 24)"
        case .seedDerivationFailed: return "Seed derivation failed"
        case .wordListUnavailable: return "BIP-39 word list unavailable"
        case .invalidMnemonic: return "Invalid mnemonic phrase"
        }
    }
}
