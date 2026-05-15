import Foundation
import CryptoKit

// Ed25519 keypair — wraps CryptoKit, matches BouncyCastle Ed25519 (RFC 8032).
struct Keypair {
    let privateKey: Curve25519.Signing.PrivateKey
    var publicKey: Curve25519.Signing.PublicKey { privateKey.publicKey }

    var publicKeyBytes: Data { publicKey.rawRepresentation }
    var secretKeyBytes: Data { privateKey.rawRepresentation }
    var secretKeyHex: String { secretKeyBytes.hexString }

    static func generate() -> Keypair {
        Keypair(privateKey: Curve25519.Signing.PrivateKey())
    }

    static func fromBytes(_ secretBytes: Data) throws -> Keypair {
        guard secretBytes.count == 32 else {
            throw KeypairError.invalidSecretKeyLength(secretBytes.count)
        }
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: secretBytes)
        return Keypair(privateKey: key)
    }

    static func fromHex(_ hex: String) throws -> Keypair {
        let bytes = try Data(hexString: hex)
        return try fromBytes(bytes)
    }

    func sign(_ message: Data) throws -> Data {
        try privateKey.signature(for: message)
    }

    func verify(message: Data, signature: Data) -> Bool {
        publicKey.isValidSignature(signature, for: message)
    }

    static func verifyWithPublicKey(_ publicKeyBytes: Data, message: Data, signature: Data) -> Bool {
        guard let pubKey = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKeyBytes) else {
            return false
        }
        return pubKey.isValidSignature(signature, for: message)
    }
}

enum KeypairError: Error, LocalizedError {
    case invalidSecretKeyLength(Int)
    case invalidHex

    var errorDescription: String? {
        switch self {
        case .invalidSecretKeyLength(let l): return "Secret key must be 32 bytes, got \(l)"
        case .invalidHex: return "Invalid hex string"
        }
    }
}

// MARK: - Hex utilities

extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init(hexString: String) throws {
        guard hexString.count % 2 == 0 else {
            throw KeypairError.invalidHex
        }
        var data = Data()
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<next], radix: 16) else {
                throw KeypairError.invalidHex
            }
            data.append(byte)
            index = next
        }
        self = data
    }
}
