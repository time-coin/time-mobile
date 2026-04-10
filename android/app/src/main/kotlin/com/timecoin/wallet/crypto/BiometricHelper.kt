package com.timecoin.wallet.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Handles biometric authentication and secure PIN storage using Android Keystore.
 *
 * Flow:
 *  1. User sets a 4-digit PIN → wallet encrypted with PIN via Argon2+AES
 *  2. User optionally enrolls biometric → PIN encrypted with Keystore key, stored in prefs
 *  3. On unlock: biometric decrypts stored PIN → PIN decrypts wallet
 */
object BiometricHelper {
    private const val TAG = "BiometricHelper"
    private const val KEYSTORE_ALIAS = "time_wallet_biometric_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS_NAME = "time_wallet_biometric"
    private const val PREF_ENCRYPTED_PIN = "encrypted_pin"
    private const val PREF_IV = "encrypted_pin_iv"

    /** Check if device supports biometric authentication. */
    fun isAvailable(context: Context): Boolean {
        val mgr = BiometricManager.from(context)
        return mgr.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Check if the user has enrolled biometric for this wallet. */
    fun isEnrolled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(PREF_ENCRYPTED_PIN)
    }

    /**
     * Enroll biometric: encrypt the PIN with a Keystore-backed key and store it.
     * Must be called from a FragmentActivity context.
     */
    fun enroll(activity: FragmentActivity, pin: String, onResult: (Boolean) -> Unit) {
        try {
            var key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key)
            } catch (e: java.security.InvalidKeyException) {
                // Key was permanently invalidated (new biometrics enrolled, etc.) — recreate it
                Log.w(TAG, "Keystore key invalidated, recreating", e)
                unenroll(activity)
                key = getOrCreateKey()
                cipher.init(Cipher.ENCRYPT_MODE, key)
            }

            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val authedCipher = result.cryptoObject?.cipher ?: cipher
                            val encrypted = authedCipher.doFinal(pin.toByteArray())
                            val iv = authedCipher.iv

                            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(PREF_ENCRYPTED_PIN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                                .apply()

                            onResult(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Enrollment encryption failed", e)
                            onResult(false)
                        }
                    }

                    override fun onAuthenticationFailed() { /* retry allowed */ }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        Log.w(TAG, "Biometric enrollment error: $errString")
                        onResult(false)
                    }
                },
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable Biometric Unlock")
                .setSubtitle("Authenticate to link your fingerprint")
                .setNegativeButtonText("Cancel")
                .build()

            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Log.e(TAG, "Biometric enrollment failed", e)
            onResult(false)
        }
    }

    /**
     * Authenticate with biometric and retrieve the stored PIN.
     */
    fun authenticate(activity: FragmentActivity, onResult: (String?) -> Unit) {
        try {
            val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encryptedB64 = prefs.getString(PREF_ENCRYPTED_PIN, null)
            val ivB64 = prefs.getString(PREF_IV, null)
            if (encryptedB64 == null || ivB64 == null) {
                onResult(null)
                return
            }

            val encrypted = Base64.decode(encryptedB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)

            val key = getKey()
            if (key == null) {
                onResult(null)
                return
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        try {
                            val authedCipher = result.cryptoObject?.cipher ?: cipher
                            val pinBytes = authedCipher.doFinal(encrypted)
                            onResult(String(pinBytes))
                        } catch (e: Exception) {
                            Log.e(TAG, "Biometric decryption failed", e)
                            onResult(null)
                        }
                    }

                    override fun onAuthenticationFailed() { /* retry allowed */ }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        Log.w(TAG, "Biometric auth error: $errString")
                        onResult(null)
                    }
                },
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Wallet")
                .setSubtitle("Authenticate to access your wallet")
                .setNegativeButtonText("Use PIN")
                .build()

            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Log.e(TAG, "Biometric authentication failed", e)
            onResult(null)
        }
    }

    /** Remove biometric enrollment. */
    fun unenroll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(KEYSTORE_ALIAS)
        } catch (_: Exception) { }
    }

    private fun getOrCreateKey(): SecretKey {
        getKey()?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return keyGen.generateKey()
    }

    private fun getKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    }
}
