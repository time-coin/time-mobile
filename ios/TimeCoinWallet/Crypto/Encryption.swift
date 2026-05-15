import Foundation
import CryptoKit
import Argon2Swift

// AES-256-GCM + Argon2id KDF — byte-for-byte compatible with Android wallet files.
enum WalletEncryption {
    private static let aesKeySize = 32
    private static let gcmNonceSize = 12
    private static let saltSize = 16
    private static let argon2Iterations: UInt32 = 3
    private static let argon2MemoryKB: UInt32 = 65536  // 64 MB
    private static let argon2Parallelism: UInt32 = 1

    struct EncryptedData: Equatable {
        let salt: Data       // 16 bytes
        let nonce: Data      // 12 bytes
        let ciphertext: Data // ciphertext + 16-byte GCM tag
        var version: Int = 1
    }

    static func encrypt(plaintext: Data, password: String) throws -> EncryptedData {
        var saltBytes = Data(count: saltSize)
        saltBytes.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, saltSize, $0.baseAddress!) }

        var nonceBytes = Data(count: gcmNonceSize)
        nonceBytes.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, gcmNonceSize, $0.baseAddress!) }

        let key = try deriveKey(password: password, salt: saltBytes)
        let symmetricKey = SymmetricKey(data: key)
        let nonce = try AES.GCM.Nonce(data: nonceBytes)
        let sealedBox = try AES.GCM.seal(plaintext, using: symmetricKey, nonce: nonce)

        // Combined: ciphertext + tag (matches Android's GCM output)
        let ciphertext = sealedBox.ciphertext + sealedBox.tag

        return EncryptedData(salt: saltBytes, nonce: nonceBytes, ciphertext: ciphertext)
    }

    static func decrypt(encrypted: EncryptedData, password: String) throws -> Data {
        let key = try deriveKey(password: password, salt: encrypted.salt)
        let symmetricKey = SymmetricKey(data: key)
        let nonce = try AES.GCM.Nonce(data: encrypted.nonce)

        guard encrypted.ciphertext.count > 16 else {
            throw EncryptionError.decryptionFailed
        }
        let ciphertextOnly = encrypted.ciphertext.prefix(encrypted.ciphertext.count - 16)
        let tag = encrypted.ciphertext.suffix(16)

        let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertextOnly, tag: tag)
        do {
            return try AES.GCM.open(sealedBox, using: symmetricKey)
        } catch {
            throw EncryptionError.wrongPassword
        }
    }

    private static func deriveKey(password: String, salt: Data) throws -> Data {
        let result = try Argon2Swift.hashRaw(
            password: Data(password.utf8),
            salt: salt,
            iterations: argon2Iterations,
            memory: argon2MemoryKB,
            parallelism: argon2Parallelism,
            hashLength: Int32(aesKeySize),
            type: .id
        )
        return result.hashData()
    }
}

enum EncryptionError: Error, LocalizedError {
    case wrongPassword
    case decryptionFailed

    var errorDescription: String? {
        switch self {
        case .wrongPassword: return "Wrong password"
        case .decryptionFailed: return "Decryption failed"
        }
    }
}
