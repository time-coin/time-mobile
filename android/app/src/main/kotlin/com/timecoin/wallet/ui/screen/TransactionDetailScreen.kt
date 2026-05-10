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
import com.timecoin.wallet.model.BlockRewardBreakdown
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
    val addresses by service.addresses.collectAsState()
    val blockRewardBreakdown by service.blockRewardBreakdown.collectAsState()
    val blockRewardBreakdownLoading by service.blockRewardBreakdownLoading.collectAsState()
    val context = LocalContext.current
    var memoText by remember(tx?.uniqueKey) { mutableStateOf(tx?.memo ?: "") }
    var editingMemo by remember { mutableStateOf(false) }

    val labelMap = remember(contacts) {
        contacts.associate { it.address to it.label.ifEmpty { it.name }.ifEmpty { null } }
    }

    val transaction = tx
    if (transaction == null) {
        service.navigateTo(Screen.Overview)
        return
    }

    val isBlockReward = transaction.memo == "Block Reward" && transaction.blockHeight > 0

    // Trigger block reward breakdown fetch when this is a block reward tx
    LaunchedEffect(transaction.blockHeight, isBlockReward) {
        if (isBlockReward) {
            val current = blockRewardBreakdown
            if (current == null || current.blockHeight != transaction.blockHeight) {
                service.fetchBlockRewardBreakdown(transaction.blockHeight)
            }
        }
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
                    Text(
                        text = "%,d satoshis".format(transaction.amount),
                        style = MaterialTheme.typography.bodySmall,
                        color = (if (transaction.isSend)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onPrimaryContainer).copy(alpha = 0.7f),
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
                    // Address — show label as primary (if any), middle-ellipsized address as subtitle
                    val addrLabel = labelMap[transaction.address]
                    val addrShort = transaction.address.take(10) + "..." + transaction.address.takeLast(8)
                    TxDetailField(
                        label = if (transaction.isSend) "To Address" else "Address",
                        value = transaction.address,
                        displayValue = addrLabel ?: addrShort,
                        subtitle = if (addrLabel != null) addrShort else null,
                        monospace = addrLabel == null,
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

                    @Suppress("DEPRECATION")
                    Divider(Modifier.padding(vertical = 8.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Memo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (!editingMemo) {
                                IconButton(
                                    onClick = { editingMemo = true },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit memo",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (editingMemo) {
                            OutlinedTextField(
                                value = memoText,
                                onValueChange = { memoText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Add a note…") },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = {
                                            service.saveMemo(transaction, memoText)
                                            editingMemo = false
                                        }) {
                                            Icon(Icons.Default.Check, contentDescription = "Save")
                                        }
                                        IconButton(onClick = {
                                            memoText = transaction.memo
                                            editingMemo = false
                                        }) {
                                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                                        }
                                    }
                                },
                            )
                        } else {
                            Text(
                                text = memoText.ifEmpty { "—" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (memoText.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            // Block reward breakdown
            if (isBlockReward) {
                Spacer(Modifier.height(16.dp))
                BlockRewardBreakdownCard(
                    breakdown = if (blockRewardBreakdown?.blockHeight == transaction.blockHeight)
                        blockRewardBreakdown else null,
                    loading = blockRewardBreakdownLoading,
                    ownedAddresses = addresses.toSet(),
                )
            }
        }
    }
}

@Composable
private fun BlockRewardBreakdownCard(
    breakdown: BlockRewardBreakdown?,
    loading: Boolean,
    ownedAddresses: Set<String>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Block Reward Breakdown",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Loading reward breakdown…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (breakdown == null) {
                Text(
                    text = "Reward data unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val myRewards = breakdown.rewards.filter { it.address in ownedAddresses }
                val displayRewards = myRewards.ifEmpty { breakdown.rewards }
                val showingAll = myRewards.isEmpty()

                if (showingAll && breakdown.rewards.isEmpty()) {
                    Text(
                        text = "No masternode rewards in this block",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (showingAll) {
                        Text(
                            text = "None of your masternodes received a reward in this block",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    // Header row
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            text = "Address",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Reward",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    displayRewards.forEachIndexed { idx, entry ->
                        if (idx > 0) {
                            @Suppress("DEPRECATION")
                            Divider(Modifier.padding(vertical = 4.dp))
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.address,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = formatSatoshis(entry.amount, 8) + " TIME",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (displayRewards.size > 1) {
                        @Suppress("DEPRECATION")
                        Divider(Modifier.padding(vertical = 4.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = "Total",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatSatoshis(displayRewards.sumOf { it.amount }, 8) + " TIME",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
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
    displayValue: String? = null,
    subtitle: String? = null,
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayValue ?: value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
