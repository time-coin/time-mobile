package com.timecoin.wallet.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.timecoin.wallet.model.TransactionRecord
import com.timecoin.wallet.model.TransactionStatus
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.formatSatoshis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(service: WalletService) {
    val tx by service.selectedTransaction.collectAsState()
    val contacts by service.contacts.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    val context = LocalContext.current

    val labelMap = remember(contacts) {
        contacts.associate { it.address to it.label.ifEmpty { it.name }.ifEmpty { null } }
    }

    val transaction = tx
    if (transaction == null) {
        service.navigateTo(Screen.Overview)
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transaction Details") },
                navigationIcon = {
                    IconButton(onClick = { service.navigateTo(Screen.Transactions) }) {
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Amount header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (transaction.isSend)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = when {
                            transaction.isFee -> Icons.Default.LocalAtm
                            transaction.isSend -> Icons.Default.ArrowUpward
                            else -> Icons.Default.ArrowDownward
                        },
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = if (transaction.isSend)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            transaction.isFee -> "Network Fee"
                            transaction.isSend -> "Sent"
                            else -> "Received"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (transaction.isSend)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${if (transaction.isSend) "-" else "+"}${formatSatoshis(transaction.amount, decimalPlaces)} TIME",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (transaction.isSend)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    // Status badge
                    Spacer(Modifier.height(12.dp))
                    val statusColor = when (transaction.status) {
                        TransactionStatus.Approved -> Color(0xFF00C850)
                        TransactionStatus.Pending -> Color(0xFFFFA500)
                        TransactionStatus.Declined -> Color(0xFFFF3B30)
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = statusColor.copy(alpha = 0.15f),
                        ),
                    ) {
                        Text(
                            text = transaction.status.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Details card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Address
                    val addrLabel = labelMap[transaction.address]
                    if (addrLabel != null) {
                        TxDetailField("Label", addrLabel)
                        @Suppress("DEPRECATION")
                        Divider(Modifier.padding(vertical = 8.dp))
                    }
                    TxDetailField(
                        label = if (transaction.isSend) "To Address" else "Address",
                        value = transaction.address,
                        monospace = true,
                        copyable = true,
                        context = context,
                    )

                    @Suppress("DEPRECATION")
                    Divider(Modifier.padding(vertical = 8.dp))

                    // Date
                    TxDetailField(
                        "Date",
                        if (transaction.timestamp > 0) {
                            java.text.SimpleDateFormat(
                                "MMM d, yyyy  h:mm:ss a",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(transaction.timestamp * 1000))
                        } else "Pending"
                    )

                    @Suppress("DEPRECATION")
                    Divider(Modifier.padding(vertical = 8.dp))

                    // Confirmations
                    TxDetailField("Confirmations", transaction.confirmations.toString())

                    @Suppress("DEPRECATION")
                    Divider(Modifier.padding(vertical = 8.dp))

                    // Block height
                    TxDetailField(
                        "Block",
                        if (transaction.blockHeight > 0) transaction.blockHeight.toString() else "Unconfirmed"
                    )

                    if (transaction.fee > 0) {
                        @Suppress("DEPRECATION")
                        Divider(Modifier.padding(vertical = 8.dp))
                        TxDetailField("Fee", "${formatSatoshis(transaction.fee, decimalPlaces)} TIME")
                    }

                    @Suppress("DEPRECATION")
                    Divider(Modifier.padding(vertical = 8.dp))

                    // Transaction ID
                    TxDetailField(
                        label = "Transaction ID",
                        value = transaction.txid,
                        monospace = true,
                        copyable = true,
                        context = context,
                    )

                    if (transaction.blockHash.isNotEmpty()) {
                        @Suppress("DEPRECATION")
                        Divider(Modifier.padding(vertical = 8.dp))
                        TxDetailField(
                            label = "Block Hash",
                            value = transaction.blockHash,
                            monospace = true,
                            copyable = true,
                            context = context,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TxDetailField(
    label: String,
    value: String,
    monospace: Boolean = false,
    copyable: Boolean = false,
    context: Context? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.weight(1f),
            )
            if (copyable && context != null) {
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
