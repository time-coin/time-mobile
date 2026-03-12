package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timecoin.wallet.model.Balance
import com.timecoin.wallet.model.TransactionRecord
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.formatTime
import com.timecoin.wallet.ui.component.formatSatoshis
import androidx.compose.ui.graphics.Color

@Composable
fun OverviewScreen(service: WalletService) {
    val balance by service.balance.collectAsState()
    val utxoSynced by service.utxoSynced.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    val transactions by service.transactions.collectAsState()
    val isTestnet by service.isTestnet.collectAsState()
    val health by service.health.collectAsState()
    val wsConnected by service.wsConnected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Network badge
        if (isTestnet) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "⚠ TESTNET",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Balance card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "Available Balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatSatoshis(balance.confirmed, decimalPlaces)} TIME",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = if (utxoSynced) Color(0xFF00C850) else Color(0xFFFFA500)
                    val statusText = if (utxoSynced) "Verified" else "Pending"
                    Card(
                        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.15f)),
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                if (balance.pending > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Locked: ${formatSatoshis(balance.pending, decimalPlaces)} TIME",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                if (balance.pending > 0) {
                    Text(
                        text = "Total: ${formatSatoshis(balance.total, decimalPlaces)} TIME",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Quick actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { service.navigateTo(Screen.Send) },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
            OutlinedButton(
                onClick = { service.navigateTo(Screen.Receive) },
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Receive")
            }
        }
        Spacer(Modifier.height(16.dp))

        // Connection status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = if (wsConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                contentDescription = null,
                tint = if (wsConnected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (wsConnected) "Connected" else "Disconnected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            health?.let {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Block: ${it.blockHeight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Recent transactions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Recent Transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = { service.navigateTo(Screen.Transactions) }) {
                Text("View All")
            }
        }

        if (transactions.isEmpty()) {
            Text(
                text = "No transactions yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            for (tx in transactions.take(5)) {
                TransactionRow(tx)
                @Suppress("DEPRECATION")
                Divider()
            }
        }
    }
}

@Composable
fun TransactionRow(tx: TransactionRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (tx.isSend) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            contentDescription = null,
            tint = if (tx.isSend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (tx.isSend) "Sent" else "Received",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = tx.address.take(12) + "..." + tx.address.takeLast(6),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${if (tx.isSend) "-" else "+"}${formatSatoshis(tx.amount)} TIME",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (tx.isSend) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = formatTime(tx.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
