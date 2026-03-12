package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.timecoin.wallet.crypto.BiometricHelper
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.wallet.WalletManager

/**
 * PIN setup screen — shown during wallet creation after mnemonic step.
 */
@Composable
fun PinSetupScreen(service: WalletService) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var askBiometric by remember { mutableStateOf(false) }
    val loading by service.loading.collectAsState()
    val context = LocalContext.current
    val biometricAvailable = remember { BiometricHelper.isAvailable(context) }

    if (askBiometric && biometricAvailable) {
        BiometricEnrollDialog(
            onEnable = {
                val activity = context as? FragmentActivity
                if (activity != null) {
                    BiometricHelper.enroll(activity, pin) { success ->
                        // Wallet already created, just proceed
                    }
                }
                askBiometric = false
            },
            onSkip = { askBiometric = false },
        )
    }

    PinEntryLayout(
        title = if (isConfirming) "Confirm PIN" else "Set a 4-Digit PIN",
        subtitle = if (isConfirming) "Enter your PIN again to confirm"
                   else "This PIN encrypts your wallet",
        pin = if (isConfirming) confirmPin else pin,
        error = error,
        showBiometric = false,
        loading = loading,
        onDigit = { digit ->
            error = null
            if (isConfirming) {
                if (confirmPin.length < 4) confirmPin += digit
                if (confirmPin.length == 4) {
                    if (confirmPin == pin) {
                        service.createWalletWithPin(pin)
                        if (biometricAvailable) askBiometric = true
                    } else {
                        error = "PINs do not match"
                        confirmPin = ""
                    }
                }
            } else {
                if (pin.length < 4) pin += digit
                if (pin.length == 4) isConfirming = true
            }
        },
        onBackspace = {
            error = null
            if (isConfirming) {
                confirmPin = confirmPin.dropLast(1)
            } else {
                pin = pin.dropLast(1)
            }
        },
        onBiometric = {},
    )
}

/**
 * PIN unlock screen — shown when app opens and wallet is encrypted.
 */
@Composable
fun PinUnlockScreen(service: WalletService) {
    var pin by remember { mutableStateOf("") }
    val loading by service.loading.collectAsState()
    val serviceError by service.error.collectAsState()
    var localError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val biometricAvailable = remember {
        BiometricHelper.isAvailable(context) && BiometricHelper.isEnrolled(context)
    }

    val walletDir = context.filesDir
    val hasMainnet = WalletManager.exists(walletDir, NetworkType.Mainnet)
    val network = if (hasMainnet) NetworkType.Mainnet else NetworkType.Testnet

    // Auto-trigger biometric on first composition
    var biometricTriggered by remember { mutableStateOf(false) }
    LaunchedEffect(biometricAvailable) {
        if (biometricAvailable && !biometricTriggered) {
            biometricTriggered = true
            val activity = context as? FragmentActivity
            if (activity != null) {
                BiometricHelper.authenticate(activity) { recoveredPin ->
                    if (recoveredPin != null) {
                        service.loadWallet(network, recoveredPin)
                    }
                }
            }
        }
    }

    // Clear local error when service error changes
    LaunchedEffect(serviceError) {
        if (serviceError != null) {
            localError = "Incorrect PIN"
            pin = ""
            service.clearError()
        }
    }

    PinEntryLayout(
        title = "Unlock Wallet",
        subtitle = "Enter your 4-digit PIN",
        pin = pin,
        error = localError,
        showBiometric = biometricAvailable,
        loading = loading,
        onDigit = { digit ->
            localError = null
            if (pin.length < 4) pin += digit
            if (pin.length == 4) {
                service.loadWallet(network, pin)
            }
        },
        onBackspace = {
            localError = null
            pin = pin.dropLast(1)
        },
        onBiometric = {
            val activity = context as? FragmentActivity
            if (activity != null) {
                BiometricHelper.authenticate(activity) { recoveredPin ->
                    if (recoveredPin != null) {
                        service.loadWallet(network, recoveredPin)
                    }
                }
            }
        },
    )
}

@Composable
private fun BiometricEnrollDialog(onEnable: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        icon = { Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(48.dp)) },
        title = { Text("Enable Biometric Unlock?", textAlign = TextAlign.Center) },
        text = { Text("Use your fingerprint to unlock your wallet instead of entering your PIN each time.") },
        confirmButton = { TextButton(onClick = onEnable) { Text("Enable") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}

/**
 * Shared PIN entry layout — numeric keypad with 4 dot indicators.
 */
@Composable
private fun PinEntryLayout(
    title: String,
    subtitle: String,
    pin: String,
    error: String?,
    showBiometric: Boolean,
    loading: Boolean,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onBiometric: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⏱", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        // PIN dots
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }

        // Error text
        Spacer(Modifier.height(16.dp))
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        if (error == null) Spacer(Modifier.height(20.dp))

        if (loading) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
        } else {
            Spacer(Modifier.height(16.dp))

            // Numeric keypad
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf(if (showBiometric) "bio" else "", "0", "⌫"),
            )

            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { key ->
                        when (key) {
                            "" -> Spacer(Modifier.size(72.dp))
                            "⌫" -> {
                                FilledTonalIconButton(
                                    onClick = onBackspace,
                                    modifier = Modifier.size(72.dp),
                                ) {
                                    Icon(Icons.Default.Backspace, contentDescription = "Delete")
                                }
                            }
                            "bio" -> {
                                FilledTonalIconButton(
                                    onClick = onBiometric,
                                    modifier = Modifier.size(72.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Fingerprint,
                                        contentDescription = "Biometric",
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                            else -> {
                                FilledTonalButton(
                                    onClick = { onDigit(key) },
                                    modifier = Modifier.size(72.dp),
                                ) {
                                    Text(key, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
