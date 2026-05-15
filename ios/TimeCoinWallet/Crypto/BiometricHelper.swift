import Foundation
import LocalAuthentication
import KeychainSwift

// Biometric authentication helper.
// Uses LocalAuthentication; PIN is stored in Keychain with biometric guard.
struct BiometricHelper {
    private static let keychainKey = "timecoin_biometric_pin"

    // MARK: - Availability

    static func isAvailable() -> Bool {
        let ctx = LAContext()
        var error: NSError?
        return ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    static func biometricType() -> String {
        let ctx = LAContext()
        var error: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else { return "None" }
        switch ctx.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        default: return "Biometrics"
        }
    }

    // MARK: - Enrollment

    static func isEnrolled() -> Bool {
        KeychainSwift().get(keychainKey) != nil
    }

    /// Authenticate with biometrics then store the PIN in Keychain.
    static func enroll(pin: String) async -> Bool {
        guard isAvailable() else { return false }
        let ctx = LAContext()
        let success = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            ctx.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: "Enroll Face ID / Touch ID for wallet unlock"
            ) { ok, _ in cont.resume(returning: ok) }
        }
        guard success else { return false }
        return KeychainSwift().set(pin, forKey: keychainKey)
    }

    /// Authenticate with biometrics and retrieve the stored PIN.
    static func authenticate() async -> String? {
        guard isAvailable(), isEnrolled() else { return nil }
        let ctx = LAContext()
        let success = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            ctx.evaluatePolicy(
                .deviceOwnerAuthenticationWithBiometrics,
                localizedReason: "Unlock your TIME Coin wallet"
            ) { ok, _ in cont.resume(returning: ok) }
        }
        guard success else { return nil }
        return KeychainSwift().get(keychainKey)
    }

    /// Remove biometric enrollment.
    static func unenroll() {
        KeychainSwift().delete(keychainKey)
    }
}
