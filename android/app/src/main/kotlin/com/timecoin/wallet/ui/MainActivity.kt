package com.timecoin.wallet.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        walletService.checkExistingWallet()

        setContent {
            TimeCoinWalletTheme {
                WalletApp(walletService)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) walletService.shutdown()
    }
}

@Composable
fun WalletApp(service: WalletService) {
    val currentScreen by service.screen.collectAsState()
    val error by service.error.collectAsState()
    val success by service.success.collectAsState()

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
                Screen.Connections -> ConnectionsScreen(service)
                Screen.Settings -> SettingsScreen(service)
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
}
