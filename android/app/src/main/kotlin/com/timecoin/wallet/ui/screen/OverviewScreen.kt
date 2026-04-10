package com.timecoin.wallet.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.timecoin.wallet.model.Balance
import com.timecoin.wallet.model.PaymentRequestStatus
import com.timecoin.wallet.model.TransactionRecord
import com.timecoin.wallet.model.TransactionStatus
import com.timecoin.wallet.ui.component.AppHamburgerMenu
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.formatTime
import com.timecoin.wallet.ui.component.formatSatoshis

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun OverviewScreen(service: WalletService) {
    val balance by service.balance.collectAsState()
    val utxoSynced by service.utxoSynced.collectAsState()
    val txSynced by service.transactionsSynced.collectAsState()
    val hasMoreTransactions by service.hasMoreTransactions.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    val transactions by service.transactions.collectAsState()
    val contacts by service.contacts.collectAsState()
    val isTestnet by service.isTestnet.collectAsState()
    val health by service.health.collectAsState()
    val wsConnected by service.wsConnected.collectAsState()
    val isRefreshing by service.manualRefreshing.collectAsState()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { service.manualRefresh() },
    )

    // Build address → label map from contacts
    val labelMap = remember(contacts) {
        contacts.associate { it.address to it.label.ifEmpty { it.name }.ifEmpty { null } }
    }

    val recentTransactions = remember(transactions) {
        transactions.take(5)
    }


    // Pulse animation: triggers when balance increases
    var previousBalance by remember { mutableLongStateOf(balance.confirmed) }
    var pulseActive by remember { mutableStateOf(false) }

    LaunchedEffect(balance.confirmed) {
        if (balance.confirmed > previousBalance && previousBalance > 0L) {
            pulseActive = true
        }
        previousBalance = balance.confirmed
    }

    // Glow alpha animates 0→1→0, scale does a subtle bump
    val glowAlpha by animateFloatAsState(
        targetValue = if (pulseActive) 1f else 0f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        finishedListener = {
            if (pulseActive) pulseActive = false
        },
        label = "glowAlpha",
    )
    val cardScale by animateFloatAsState(
        targetValue = if (pulseActive) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cardScale",
    )

    // New transaction highlight tracking
    var knownTxKeys by remember { mutableStateOf<Set<String>?>(null) }
    var highlightedTxKeys by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(recentTransactions) {
        val currentKeys = recentTransactions.map { it.uniqueKey }.toSet()
        val known = knownTxKeys
        if (known != null) {
            val newKeys = currentKeys - known
            if (newKeys.isNotEmpty()) {
                highlightedTxKeys = newKeys
                delay(1500)
                highlightedTxKeys = emptySet()
            }
        }
        knownTxKeys = currentKeys
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TIME Wallet") },
                actions = { AppHamburgerMenu(service) },
            )
        },
    ) { innerPadding ->

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState),
    ) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
    ) {
        // Network badge
        if (isTestnet) {
            item {
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
        }

        // Sync warning banner
        health?.let { h ->
            if (h.isSyncing) {
                item {
                    val pct = (h.syncProgress * 100).toInt()
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFA500).copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = null,
                                tint = Color(0xFFFFA500),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Masternode syncing — $pct% complete. Balance may be incomplete.",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFA500),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Balance card
        item {
            val glowColor = Color(0xFF00C850)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(cardScale)
                    .then(
                        if (glowAlpha > 0f) Modifier.border(
                            width = (2 * glowAlpha).dp,
                            color = glowColor.copy(alpha = glowAlpha * 0.8f),
                            shape = RoundedCornerShape(12.dp),
                        ) else Modifier
                    ),
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
                    FlowRow(
                        verticalArrangement = Arrangement.Center,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${formatSatoshis(balance.confirmed, decimalPlaces)} TIME",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        val synced = utxoSynced && txSynced && health?.isSyncing != true
                        val statusColor = if (synced) Color(0xFF00C850) else Color(0xFFFFA500)
                        val statusText = if (synced) "Verified" else "Pending"
                        Card(
                            modifier = Modifier.align(Alignment.CenterVertically),
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
                            text = "Pending: ${formatSatoshis(balance.pending, decimalPlaces)} TIME",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    if (balance.locked > 0) {
                        Text(
                            text = "Locked (Collateral): ${formatSatoshis(balance.locked, decimalPlaces)} TIME",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    if (balance.pending > 0 || balance.locked > 0) {
                        Text(
                            text = "Total: ${formatSatoshis(balance.total, decimalPlaces)} TIME",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Quick actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { service.navigateTo(Screen.Send) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Send", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = { service.navigateTo(Screen.Receive) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Receive", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = { service.lockWallet() },
                    modifier = Modifier.weight(1f).height(56.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Lock", maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Connection status
        item {
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
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "Transactions: ${transactions.size}${if (hasMoreTransactions) "+" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }


        // Recent transactions header
        item {
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
        }

        val cutoff24h = System.currentTimeMillis() / 1000 - 86_400
        val recentTxs = recentTransactions

        if (recentTxs.isEmpty()) {
            item {
                Text(
                    text = if (transactions.isEmpty()) "No transactions yet"
                           else "No transactions in the last 24 hours",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        } else {
            items(recentTxs, key = { it.uniqueKey }) { tx ->
                TransactionRow(
                    tx = tx,
                    label = labelMap[tx.address],
                    decimalPlaces = decimalPlaces,
                    onClick = { service.showTransaction(tx) },
                    isHighlighted = tx.uniqueKey in highlightedTxKeys,
                    modifier = Modifier.animateItemPlacement(),
                )
                @Suppress("DEPRECATION")
                Divider()
            }
        }
    }

        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = innerPadding.calculateTopPadding()),
        )
    } // end Box

    } // end Scaffold
}

@Composable
fun TransactionRow(
    tx: TransactionRecord,
    label: String?,
    decimalPlaces: Int,
    onClick: () -> Unit = {},
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (isHighlighted) 0.8f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "txGlow",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glowAlpha > 0f) Modifier.background(Color(0xFF00C850).copy(alpha = glowAlpha * 0.12f))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Direction icon
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
            // Line 1: label or generic direction name
            Text(
                text = when {
                    tx.isFee -> "Network Fee"
                    tx.isSend -> label ?: "Sent"
                    else -> label ?: "Received"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            // Line 2: address + memo inline
            val addrText = if (tx.isFee) tx.txid.take(12) + "…" + tx.txid.takeLast(4)
                           else tx.address.take(12) + "…" + tx.address.takeLast(4)
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
    }
}

