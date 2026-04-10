package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timecoin.wallet.R
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService

@Composable
fun WelcomeScreen(service: WalletService) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.time_logo),
            contentDescription = "TIME Coin Logo",
            modifier = Modifier
                .size(96.dp)
                .padding(4.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "TIME Coin Wallet",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Secure mobile wallet for TIME Coin",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = {
                service.setRestoreMode(false)
                service.navigateTo(Screen.NetworkSelect)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Create New Wallet", fontSize = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                service.setRestoreMode(true)
                service.navigateTo(Screen.NetworkSelect)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Restore from Mnemonic", fontSize = 18.sp)
        }
    }
}

@Composable
fun NetworkSelectScreen(service: WalletService) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Select Network",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose the network for your wallet",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))

        Button(
            onClick = { service.selectNetwork(isTestnet = false) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Mainnet", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Real TIME coins • Port 24001",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedButton(
            onClick = { service.selectNetwork(isTestnet = true) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("Testnet", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Test coins (no real value) • Port 24101",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
