package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timecoin.wallet.model.TransactionRecord
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.formatTime
import com.timecoin.wallet.ui.component.formatSatoshis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(service: WalletService) {
    val transactions by service.transactions.collectAsState()
    val contacts by service.contacts.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedTx by remember { mutableStateOf<TransactionRecord?>(null) }

    // Build address → label map from contacts
    val labelMap = remember(contacts) {
        contacts.associate { it.address to it.label.ifEmpty { it.name }.ifEmpty { null } }
    }

    val filtered = if (searchQuery.isBlank()) transactions
    else transactions.filter {
        it.address.contains(searchQuery, ignoreCase = true) ||
            it.txid.contains(searchQuery, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction History") },
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
                .padding(padding),
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by address or txid") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
            )

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (searchQuery.isNotEmpty()) "No matching transactions"
                               else "No transactions yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    items(filtered, key = { it.txid }) { tx ->
                        TransactionDetailRow(
                            tx = tx,
                            label = labelMap[tx.address],
                            onClick = {
                                selectedTx = if (selectedTx?.txid == tx.txid) null else tx
                            },
                            isExpanded = selectedTx?.txid == tx.txid,
                        )
                        @Suppress("DEPRECATION")
                    Divider()
                    }
                }
            }
        }
    }

    // Transaction detail dialog
    selectedTx?.let { tx ->
        if (selectedTx?.txid == tx.txid) {
            // Shown inline via expansion instead
        }
    }
}

@Composable
fun TransactionDetailRow(
    tx: TransactionRecord,
    label: String?,
    onClick: () -> Unit,
    isExpanded: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (tx.isSend) Icons.Default.ArrowUpward
                              else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = if (tx.isSend) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (tx.isSend) "Sent" else (label ?: "Received"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = tx.address,
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
                    color = if (tx.isSend) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = formatTime(tx.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onClick, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess
                                  else Icons.Default.ExpandMore,
                    contentDescription = "Details",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // Expanded details
        if (isExpanded) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    DetailRow("TxID", tx.txid)
                    DetailRow("Date", if (tx.timestamp > 0) {
                        java.text.SimpleDateFormat("MMM d, yyyy h:mm a", java.util.Locale.getDefault())
                            .format(java.util.Date(tx.timestamp * 1000))
                    } else "Pending")
                    DetailRow("Status", tx.status.name)
                    DetailRow("Block", tx.blockHeight.toString())
                    DetailRow("Confirmations", tx.confirmations.toString())
                    if (tx.fee > 0) {
                        DetailRow("Fee", "${formatSatoshis(tx.fee)} TIME")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}
