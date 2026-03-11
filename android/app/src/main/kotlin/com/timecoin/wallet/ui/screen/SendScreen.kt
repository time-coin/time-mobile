package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    service: WalletService,
    onScanQr: () -> Unit = {},
) {
    val balance by service.balance.collectAsState()
    val isTestnet by service.isTestnet.collectAsState()
    val loading by service.loading.collectAsState()
    val contacts by service.contacts.collectAsState()

    var toAddress by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }

    val network = if (isTestnet) NetworkType.Testnet else NetworkType.Mainnet
    val amountSats = (amountStr.toDoubleOrNull()?.times(100_000_000))?.toLong() ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send TIME") },
                navigationIcon = {
                    IconButton(onClick = { service.navigateTo(Screen.Overview) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            // To address
            OutlinedTextField(
                value = toAddress,
                onValueChange = {
                    toAddress = it
                    addressError = if (it.isNotEmpty()) {
                        try {
                            Address.fromString(it)
                            null
                        } catch (e: Exception) {
                            e.message
                        }
                    } else null
                },
                label = { Text("Recipient Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = addressError != null,
                supportingText = addressError?.let { { Text(it) } },
                trailingIcon = {
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))

            // Quick contact picker (if any external contacts)
            val externalContacts = contacts.filter { !it.isOwned }
            if (externalContacts.isNotEmpty()) {
                Text(
                    "Contacts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    externalContacts.take(3).forEach { contact ->
                        AssistChip(
                            onClick = { toAddress = contact.address },
                            label = { Text(contact.name.ifEmpty { contact.label }) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Amount
            OutlinedTextField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = { Text("Amount (TIME)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = {
                    Text("Available: ${formatBalance(balance.confirmed)} TIME")
                },
            )
            Spacer(Modifier.height(8.dp))

            // Fee estimate
            if (amountSats > 0) {
                val feeRate = when {
                    amountSats < 100_00000000L -> 0.01
                    amountSats < 1000_00000000L -> 0.005
                    amountSats < 10000_00000000L -> 0.0025
                    else -> 0.001
                }
                val fee = maxOf((amountSats * feeRate).toLong(), 1_000_000L)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Amount:", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${formatBalance(amountSats)} TIME",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Fee:", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${formatBalance(fee)} TIME (${(feeRate * 100)}%)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        @Suppress("DEPRECATION")
                        Divider(Modifier.padding(vertical = 4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Total:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${formatBalance(amountSats + fee)} TIME",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            val canSend = toAddress.isNotEmpty() &&
                addressError == null &&
                amountSats > 0 &&
                amountSats <= balance.confirmed &&
                !loading

            Button(
                onClick = { service.sendTransaction(toAddress, amountSats) },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Send Transaction", fontSize = 18.sp)
                }
            }
        }
    }
}
