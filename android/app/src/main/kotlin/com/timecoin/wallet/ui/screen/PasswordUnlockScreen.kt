package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.wallet.WalletManager

@Composable
fun PasswordUnlockScreen(service: WalletService) {
    var password by remember { mutableStateOf("") }
    val loading by service.loading.collectAsState()
    val error by service.error.collectAsState()

    // Detect which network has a wallet file
    val walletDir = androidx.compose.ui.platform.LocalContext.current.filesDir
    val hasMainnet = WalletManager.exists(walletDir, NetworkType.Mainnet)
    val network = if (hasMainnet) NetworkType.Mainnet else NetworkType.Testnet

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("⏱", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Unlock Wallet",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Enter your password to decrypt your wallet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                service.clearError()
            },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            isError = error != null,
            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { service.loadWallet(network, password) },
            enabled = password.isNotEmpty() && !loading,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Unlock", fontSize = 18.sp)
            }
        }
    }
}
