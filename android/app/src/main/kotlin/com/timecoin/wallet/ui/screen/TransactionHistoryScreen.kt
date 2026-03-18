package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import com.timecoin.wallet.model.TransactionRecord
import com.timecoin.wallet.model.TransactionStatus
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.AppHamburgerMenu
import com.timecoin.wallet.ui.component.formatTime
import com.timecoin.wallet.ui.component.formatSatoshis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionHistoryScreen(service: WalletService) {
    val transactions by service.transactions.collectAsState()
    val contacts by service.contacts.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    var searchFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val searchQuery = searchFieldValue.text

    // Build address → label map from contacts (name preferred, then label)
    val labelMap = remember(contacts) {
        contacts.associate { it.address to it.name.ifEmpty { it.label }.ifEmpty { null } }
    }

    val filtered = remember(searchQuery, transactions, labelMap, decimalPlaces) {
        if (searchQuery.isBlank()) transactions
        else {
            val q = searchQuery.trim()
            transactions.filter { tx ->
                tx.address.contains(q, ignoreCase = true) ||
                    tx.txid.contains(q, ignoreCase = true) ||
                    // Search by contact name / label
                    labelMap[tx.address]?.contains(q, ignoreCase = true) == true ||
                    // Search by amount (formatted string)
                    formatSatoshis(tx.amount, decimalPlaces).contains(q) ||
                    // Search by type
                    (q.equals("fee", ignoreCase = true) && tx.isFee) ||
                    (q.equals("sent", ignoreCase = true) && tx.isSend && !tx.isFee) ||
                    (q.equals("send", ignoreCase = true) && tx.isSend && !tx.isFee) ||
                    (q.equals("received", ignoreCase = true) && !tx.isSend && !tx.isFee) ||
                    (q.equals("receive", ignoreCase = true) && !tx.isSend && !tx.isFee)
            }
        }
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
                actions = { AppHamburgerMenu(service) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Search bar
            TextField(
                value = searchFieldValue,
                onValueChange = { searchFieldValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search name, address, amount, txid…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchFieldValue = TextFieldValue("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = MaterialTheme.shapes.medium,
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.uniqueKey }) { tx ->
                        TransactionDetailRow(
                            tx = tx,
                            label = labelMap[tx.address],
                            decimalPlaces = decimalPlaces,
                            onClick = { service.showTransaction(tx) },
                            isExpanded = false,
                        )
                        @Suppress("DEPRECATION")
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionDetailRow(
    tx: TransactionRecord,
    label: String?,
    decimalPlaces: Int,
    onClick: () -> Unit,
    isExpanded: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    tx.isFee -> Icons.Default.LocalAtm
                    tx.isSend -> Icons.Default.ArrowUpward
                    else -> Icons.Default.ArrowDownward
                },
                contentDescription = null,
                tint = when {
                    tx.isFee -> Color(0xFFFFA500)
                    tx.isSend -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        tx.isFee -> "Network Fee"
                        tx.isSend -> label ?: "Sent"
                        else -> label ?: "Received"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val addrText = if (tx.isFee) tx.txid.take(12) + "…" + tx.txid.takeLast(4)
                               else tx.address.take(14) + "…" + tx.address.takeLast(6)
                val subtitleText = if (tx.memo.isNotBlank()) "$addrText · ${tx.memo}" else addrText
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (tx.isSend || tx.isFee) "-" else "+"}${formatSatoshis(tx.amount, decimalPlaces)} TIME",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        tx.isFee -> Color(0xFFFFA500)
                        tx.isSend -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = formatTime(tx.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            // Status icon
            val statusIcon = when (tx.status) {
                TransactionStatus.Approved -> Icons.Default.CheckCircle
                TransactionStatus.Pending -> Icons.Default.HourglassEmpty
                TransactionStatus.Declined -> Icons.Default.Cancel
            }
            val statusColor = when (tx.status) {
                TransactionStatus.Approved -> Color(0xFF00C850)
                TransactionStatus.Pending -> Color(0xFFFFA500)
                TransactionStatus.Declined -> Color(0xFFFF3B30)
            }
            Icon(
                imageVector = statusIcon,
                contentDescription = tx.status.name,
                tint = statusColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Details",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
