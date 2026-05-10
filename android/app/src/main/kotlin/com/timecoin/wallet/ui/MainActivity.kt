package com.timecoin.wallet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.formatSatoshis
import com.timecoin.wallet.ui.screen.*
import com.timecoin.wallet.ui.theme.TimeCoinWalletTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var walletService: WalletService

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(this) }

    // Launcher for the immediate update flow (re-checks if user dismissed and came back)
    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Log.w("AppUpdate", "Immediate update flow cancelled or failed: ${result.resultCode}")
            // User dismissed — re-check so we can prompt again on next resume
        }
    }

    // Listener for flexible fallback (downloaded in background)
    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            showUpdateSnackbar()
        }
    }

    private var snackbarHostState: SnackbarHostState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        walletService.checkExistingWallet()
        handleShareIntent(intent)
        appUpdateManager.registerListener(installStateListener)
        checkForUpdate()
        requestNotificationPermission()

        setContent {
            TimeCoinWalletTheme {
                val hostState = remember { SnackbarHostState() }
                snackbarHostState = hostState
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        WalletApp(walletService)
                        SnackbarHost(
                            hostState = hostState,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            when {
                // Immediate update was interrupted (e.g. app killed mid-download) — resume it
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
                    appUpdateManager.startUpdateFlowForResult(
                        info,
                        updateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    )
                }
                // Flexible download finished while app was backgrounded
                info.installStatus() == InstallStatus.DOWNLOADED -> showUpdateSnackbar()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        appUpdateManager.unregisterListener(installStateListener)
        if (isFinishing) walletService.shutdown()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }
    }

    private fun checkForUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                val options = when {
                    info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) ->
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                    else -> return@addOnSuccessListener
                }
                appUpdateManager.startUpdateFlowForResult(info, updateLauncher, options)
            }
        }
    }

    private fun showUpdateSnackbar() {
        val hostState = snackbarHostState ?: return
        // Run on main thread — listener may fire on a background thread
        mainExecutor.execute {
            kotlinx.coroutines.MainScope().launch {
                val result = hostState.showSnackbar(
                    message = "Update downloaded",
                    actionLabel = "Restart",
                    duration = SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    appUpdateManager.completeUpdate()
                }
            }
        }
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
    val incomingPaymentRequest by service.incomingPaymentRequest.collectAsState()
    val shouldExit by service.shouldExit.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as? Activity

    // Back press on any post-login screen navigates to Overview instead of exiting.
    // Only Overview (and pre-wallet screens) let the system handle back (which exits).
    val backToOverviewScreens = remember {
        setOf(
            Screen.Send, Screen.Receive,
            Screen.Transactions, Screen.TransactionDetail,
            Screen.Connections, Screen.Settings, Screen.ChangePin,
            Screen.PaymentRequest, Screen.PaymentRequestReview,
            Screen.PaymentRequests,
        )
    }
    BackHandler(enabled = currentScreen in backToOverviewScreens) {
        service.navigateTo(Screen.Overview)
    }

    LaunchedEffect(shouldExit) {
        if (shouldExit) {
            service.clearShouldExit()
            activity?.finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
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
                Screen.PaymentRequest -> PaymentRequestScreen(service)
                Screen.PaymentRequestQrScanner -> QrScannerScreen(
                    onResult = { address ->
                        service.setScannedAddress(address)
                        service.navigateTo(Screen.PaymentRequest)
                    },
                    onBack = { service.navigateTo(Screen.PaymentRequest) },
                )
                Screen.PaymentRequestReview -> PaymentRequestReviewScreen(service)
                Screen.PaymentRequests -> PaymentRequestsScreen(service)
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

    // Incoming payment request notification dialog
    incomingPaymentRequest?.let { req ->
        IncomingPaymentRequestDialog(
            requesterName = req.requesterName,
            requesterAddress = req.requesterAddress,
            amountSats = req.amountSats,
            memo = req.memo,
            onReview = { service.reviewPaymentRequest(req) },
            onDismiss = { service.dismissIncomingRequest() },
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

@Composable
private fun IncomingPaymentRequestDialog(
    requesterName: String,
    requesterAddress: String,
    amountSats: Long,
    memo: String,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.RequestPage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        },
        title = { Text("Payment Request Received") },
        text = {
            Column {
                Text(
                    "Someone has requested a TIME payment from you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (requesterName.isNotBlank()) {
                    Text("From", style = MaterialTheme.typography.labelMedium)
                    Text(requesterName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                }
                Text("Address", style = MaterialTheme.typography.labelMedium)
                Text(
                    requesterAddress.take(14) + "…" + requesterAddress.takeLast(8),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))
                Text("Amount", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${formatSatoshis(amountSats)} TIME",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (memo.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Memo", style = MaterialTheme.typography.labelMedium)
                    Text(memo, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReview) { Text("Review") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        },
    )
}
