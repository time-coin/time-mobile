import Foundation
import CryptoKit

enum NetworkType: String, Codable {
    case mainnet = "Mainnet"
    case testnet = "Testnet"

    var rpcPort: Int {
        switch self {
        case .mainnet: return 24001
        case .testnet: return 24101
        }
    }

    var genesisHash: String {
        switch self {
        case .mainnet: return "45181d4c65a3a2bcc2215d037267bee4cc2248f21764466846d2b7218b601ce5"
        case .testnet: return "b9523431d4e59a1b41d757a8c0f01ed023c11123761b1455e4644ef9d5599ff6"
        }
    }
}

struct Address: Equatable, CustomStringConvertible {
    let network: NetworkType
    let payload: Data  // 20 bytes

    init(network: NetworkType, payload: Data) {
        precondition(payload.count == 20, "Payload must be 20 bytes")
        self.network = network
        self.payload = payload
    }

    var isTestnet: Bool { network == .testnet }

    var description: String { formatted }

    private var formatted: String {
        let digit: Character = network == .testnet ? "0" : "1"
        var data = Data(payload)
        data.append(checksum(of: payload))
        return "TIME\(digit)\(Base58.encode(data))"
    }

    static func fromPublicKey(_ publicKey: Data, network: NetworkType) -> Address {
        precondition(publicKey.count == 32, "Public key must be 32 bytes")
        let payload = hashPublicKey(publicKey)
        return Address(network: network, payload: payload)
    }

    static func fromString(_ s: String) throws -> Address {
        guard s.count >= 35 && s.count <= 45 else {
            throw AddressError.invalidLength(s.count)
        }
        guard s.hasPrefix("TIME") else {
            throw AddressError.missingPrefix
        }
        let networkChar = s[s.index(s.startIndex, offsetBy: 4)]
        let network: NetworkType
        switch networkChar {
        case "0": network = .testnet
        case "1": network = .mainnet
        default: throw AddressError.invalidNetworkDigit(networkChar)
        }
        let encoded = String(s.dropFirst(5))
        let decoded = try Base58.decode(encoded)
        guard decoded.count == 24 else {
            throw AddressError.invalidDecodedLength(decoded.count)
        }
        let payloadBytes = decoded.prefix(20)
        let storedChecksum = decoded.suffix(4)
        let computedChecksum = checksum(of: Data(payloadBytes))
        guard Data(storedChecksum) == computedChecksum else {
            throw AddressError.checksumMismatch
        }
        return Address(network: network, payload: Data(payloadBytes))
    }

    static func isValid(_ s: String) -> Bool {
        (try? fromString(s)) != nil
    }

    // SHA-256 → first 20 bytes
    private static func hashPublicKey(_ publicKey: Data) -> Data {
        let hash = SHA256.hash(data: publicKey)
        return Data(hash.prefix(20))
    }
}

// Double SHA-256 → first 4 bytes
private func checksum(of data: Data) -> Data {
    let hash1 = SHA256.hash(data: data)
    let hash2 = SHA256.hash(data: Data(hash1))
    return Data(hash2.prefix(4))
}

enum AddressError: Error, LocalizedError {
    case invalidLength(Int)
    case missingPrefix
    case invalidNetworkDigit(Character)
    case invalidDecodedLength(Int)
    case checksumMismatch
    case invalidCharacter(Character)

    var errorDescription: String? {
        switch self {
        case .invalidLength(let l): return "Invalid address length: \(l)"
        case .missingPrefix: return "Address must start with TIME"
        case .invalidNetworkDigit(let c): return "Invalid network digit: \(c)"
        case .invalidDecodedLength(let l): return "Invalid decoded payload length: \(l)"
        case .checksumMismatch: return "Invalid address checksum"
        case .invalidCharacter(let c): return "Invalid Base58 character: \(c)"
        }
    }
}

enum Base58 {
    private static let alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
    private static let base = 58

    static func encode(_ data: Data) -> String {
        var num = BigUInt(data)
        var result: [Character] = []
        let bigBase = BigUInt(base)

        while num > 0 {
            let (quotient, remainder) = num.quotientAndRemainder(dividingBy: bigBase)
            result.insert(alphabet[Int(remainder)], at: result.startIndex)
            num = quotient
        }

        for byte in data {
            if byte == 0 {
                result.insert("1", at: result.startIndex)
            } else {
                break
            }
        }

        return String(result)
    }

    static func decode(_ s: String) throws -> Data {
        var num = BigUInt(0)
        let bigBase = BigUInt(base)

        for c in s {
            guard let idx = alphabet.firstIndex(of: c) else {
                throw AddressError.invalidCharacter(c)
            }
            num = num * bigBase + BigUInt(idx)
        }

        var bytes = num.toData()

        let leadingOnes = s.prefix(while: { $0 == "1" }).count
        let padding = Data(repeating: 0, count: leadingOnes)
        return padding + bytes
    }
}

// Minimal big integer for Base58 (no external dependency needed)
struct BigUInt: Comparable, ExpressibleByIntegerLiteral {
    private var words: [UInt32]  // little-endian 32-bit limbs

    init(integerLiteral value: UInt64) {
        if value == 0 {
            words = []
        } else if value <= UInt64(UInt32.max) {
            words = [UInt32(value)]
        } else {
            words = [UInt32(value & 0xFFFFFFFF), UInt32(value >> 32)]
        }
        normalize()
    }

    init(_ data: Data) {
        words = []
        var n = BigUInt(0)
        for byte in data {
            n = n * BigUInt(256) + BigUInt(integerLiteral: UInt64(byte))
        }
        self = n
    }

    init(_ value: UInt32) {
        words = value == 0 ? [] : [value]
    }

    private mutating func normalize() {
        while words.last == 0 { words.removeLast() }
    }

    static func < (lhs: BigUInt, rhs: BigUInt) -> Bool {
        if lhs.words.count != rhs.words.count { return lhs.words.count < rhs.words.count }
        for i in stride(from: lhs.words.count - 1, through: 0, by: -1) {
            if lhs.words[i] != rhs.words[i] { return lhs.words[i] < rhs.words[i] }
        }
        return false
    }

    static func == (lhs: BigUInt, rhs: BigUInt) -> Bool {
        lhs.words == rhs.words
    }

    static func + (lhs: BigUInt, rhs: BigUInt) -> BigUInt {
        var result = BigUInt(0)
        let maxLen = max(lhs.words.count, rhs.words.count)
        result.words = Array(repeating: 0, count: maxLen + 1)
        var carry: UInt64 = 0
        for i in 0..<maxLen {
            let l: UInt64 = i < lhs.words.count ? UInt64(lhs.words[i]) : 0
            let r: UInt64 = i < rhs.words.count ? UInt64(rhs.words[i]) : 0
            let sum = l + r + carry
            result.words[i] = UInt32(sum & 0xFFFFFFFF)
            carry = sum >> 32
        }
        result.words[maxLen] = UInt32(carry)
        result.normalize()
        return result
    }

    static func * (lhs: BigUInt, rhs: BigUInt) -> BigUInt {
        if lhs.words.isEmpty || rhs.words.isEmpty { return BigUInt(0) }
        var result = BigUInt(0)
        result.words = Array(repeating: 0, count: lhs.words.count + rhs.words.count)
        for i in 0..<lhs.words.count {
            var carry: UInt64 = 0
            for j in 0..<rhs.words.count {
                let cur = UInt64(result.words[i + j])
                let prod = UInt64(lhs.words[i]) * UInt64(rhs.words[j]) + cur + carry
                result.words[i + j] = UInt32(prod & 0xFFFFFFFF)
                carry = prod >> 32
            }
            result.words[i + rhs.words.count] += UInt32(carry)
        }
        result.normalize()
        return result
    }

    func quotientAndRemainder(dividingBy divisor: BigUInt) -> (BigUInt, BigUInt) {
        guard !divisor.words.isEmpty else { fatalError("Division by zero") }
        if self < divisor { return (BigUInt(0), self) }

        // Simple long division for single-limb divisors (sufficient for Base58)
        if divisor.words.count == 1 {
            let d = UInt64(divisor.words[0])
            var remainder: UInt64 = 0
            var quotientWords = Array(repeating: UInt32(0), count: words.count)
            for i in stride(from: words.count - 1, through: 0, by: -1) {
                let cur = (remainder << 32) | UInt64(words[i])
                quotientWords[i] = UInt32(cur / d)
                remainder = cur % d
            }
            var q = BigUInt(0)
            q.words = quotientWords
            q.normalize()
            return (q, BigUInt(integerLiteral: remainder))
        }

        // Multi-limb divisors are not needed for Base58 (divisor is always 58, a single limb).
        // If this fires, the caller is using BigUInt beyond its intended scope.
        fatalError("Multi-limb divisor not supported")
    }

    static func - (lhs: BigUInt, rhs: BigUInt) -> BigUInt {
        var result = BigUInt(0)
        result.words = Array(repeating: 0, count: lhs.words.count)
        var borrow: Int64 = 0
        for i in 0..<lhs.words.count {
            let l = Int64(lhs.words[i])
            let r: Int64 = i < rhs.words.count ? Int64(rhs.words[i]) : 0
            let diff = l - r - borrow
            if diff < 0 {
                result.words[i] = UInt32(bitPattern: Int32(diff + (1 << 32)))
                borrow = 1
            } else {
                result.words[i] = UInt32(diff)
                borrow = 0
            }
        }
        result.normalize()
        return result
    }

    func toData() -> Data {
        if words.isEmpty { return Data() }
        var result = Data()
        for word in words.reversed() {
            result.append(UInt8((word >> 24) & 0xFF))
            result.append(UInt8((word >> 16) & 0xFF))
            result.append(UInt8((word >> 8) & 0xFF))
            result.append(UInt8(word & 0xFF))
        }
        // Strip leading zero bytes (BigInteger sign padding)
        while result.first == 0 && result.count > 1 {
            result.removeFirst()
        }
        if result == Data([0]) { return Data() }
        return result
    }

    var isZero: Bool { words.isEmpty }

    static func > (lhs: BigUInt, rhs: BigUInt) -> Bool { rhs < lhs }
    static func <= (lhs: BigUInt, rhs: BigUInt) -> Bool { !(rhs < lhs) }
    static func >= (lhs: BigUInt, rhs: BigUInt) -> Bool { !(lhs < rhs) }
}
