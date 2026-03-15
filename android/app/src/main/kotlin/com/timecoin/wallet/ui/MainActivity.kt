package com.timecoin.wallet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.screen.*
import com.timecoin.wallet.ui.theme.TimeCoinWalletTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var walletService: WalletService

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        walletService.checkExistingWallet()
        handleShareIntent(intent)

        setContent {
            TimeCoinWalletTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WalletApp(walletService)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) walletService.shutdown()
    }

    /**
     * Extract TIME addresses and phone numbers from shared text.
     * Handles SMS shares, clipboard shares, etc.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return

        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return

        // Extract TIME address (TIME0... or TIME1...)
        val addressRegex = Regex("""TIME[01][123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{30,40}""")
        val addressMatch = addressRegex.find(text)
        val address = addressMatch?.value

        // Validate the address with full checksum verification
        if (address == null || !Address.isValid(address)) return

        // Extract phone number — try EXTRA_PHONE_NUMBER first, then parse from text
        val phoneFromExtra = intent.getStringExtra("address") // SMS app may pass sender
        val phoneRegex = Regex("""(?:\+?1?[-.\s]?)?\(?[0-9]{3}\)?[-.\s]?[0-9]{3}[-.\s]?[0-9]{4}""")
        val phoneMatch = phoneRegex.find(text)
        val phone = phoneFromExtra ?: phoneMatch?.value?.trim() ?: ""

        walletService.setSharedContact(
            WalletService.SharedContact(
                address = address,
                phone = phone,
                rawText = text.take(500),
            )
        )
    }
}

@Composable
fun WalletApp(service: WalletService) {
    val currentScreen by service.screen.collectAsState()
    val error by service.error.collectAsState()
    val success by service.success.collectAsState()
    val sharedContact by service.sharedContact.collectAsState()
    val shouldExit by service.shouldExit.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity
    LaunchedEffect(shouldExit) {
        if (shouldExit) activity?.finishAndRemoveTask()
    }

    Scaffold(
        bottomBar = {
            if (currentScreen in listOf(Screen.Overview, Screen.Transactions, Screen.Connections, Screen.Settings)) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentScreen == Screen.Overview,
                        onClick = { service.navigateTo(Screen.Overview) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.Transactions,
                        onClick = { service.navigateTo(Screen.Transactions) },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        label = { Text("History") },
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.Connections,
                        onClick = { service.navigateTo(Screen.Connections) },
                        icon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                        label = { Text("Peers") },
                    )
                    NavigationBarItem(
                        selected = currentScreen == Screen.Settings,
                        onClick = { service.navigateTo(Screen.Settings) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
        snackbarHost = {
            // Error/success snackbars handled below
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (currentScreen) {
                Screen.Welcome -> WelcomeScreen(service)
                Screen.NetworkSelect -> NetworkSelectScreen(service)
                Screen.MnemonicSetup -> MnemonicSetupScreen(service)
                Screen.MnemonicConfirm -> MnemonicSetupScreen(service) // reuse for now
                Screen.PinSetup -> PinSetupScreen(service)
                Screen.PinUnlock -> PinUnlockScreen(service)
                Screen.PasswordUnlock -> PasswordUnlockScreen(service)
                Screen.Overview -> OverviewScreen(service)
                Screen.Send -> SendScreen(
                    service = service,
                    onScanQr = { service.navigateTo(Screen.QrScanner) },
                )
                Screen.QrScanner -> QrScannerScreen(
                    onResult = { address ->
                        service.setScannedAddress(address)
                        service.navigateTo(Screen.Send)
                    },
                    onBack = { service.navigateTo(Screen.Send) },
                )
                Screen.Receive -> ReceiveScreen(service)
                Screen.Transactions -> TransactionHistoryScreen(service)
                Screen.TransactionDetail -> TransactionDetailScreen(service)
                Screen.Connections -> ConnectionsScreen(service)
                Screen.Settings -> SettingsScreen(service)
                Screen.ChangePin -> ChangePinScreen(service)
            }
        }
    }

    // Error dialog
    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { service.clearError() },
            title = { Text("Error") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { service.clearError() }) { Text("OK") }
            },
        )
    }

    // Success dialog
    success?.let { msg ->
        AlertDialog(
            onDismissRequest = { service.clearSuccess() },
            title = { Text("Success") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { service.clearSuccess() }) { Text("OK") }
            },
        )
    }

    // Shared contact dialog — shown when a TIME address is shared to the app
    sharedContact?.let { contact ->
        SharedContactDialog(
            contact = contact,
            onSave = { name, phone ->
                service.saveContact(
                    name = name,
                    address = contact.address,
                    phone = phone,
                )
                service.clearSharedContact()
            },
            onSendTo = { name, phone ->
                service.saveContact(
                    name = name,
                    address = contact.address,
                    phone = phone,
                )
                service.setScannedAddress(contact.address)
                service.navigateTo(Screen.Send)
                service.clearSharedContact()
            },
            onDismiss = { service.clearSharedContact() },
        )
    }
}

@Composable
private fun SharedContactDialog(
    contact: WalletService.SharedContact,
    onSave: (name: String, phone: String) -> Unit,
    onSendTo: (name: String, phone: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf(contact.phone) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.PersonAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        },
        title = { Text("TIME Address Found") },
        text = {
            Column {
                Text(
                    "A TIME address was detected in the shared message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Show the detected address
                Text("Address", style = MaterialTheme.typography.labelMedium)
                Text(
                    contact.address,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSave(name.trim(), phone.trim()) }) {
                    Text("Save Contact")
                }
                TextButton(onClick = { onSendTo(name.trim(), phone.trim()) }) {
                    Text("Send TIME")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}
