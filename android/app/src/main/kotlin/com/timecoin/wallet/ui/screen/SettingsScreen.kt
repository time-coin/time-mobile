package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.AppHamburgerMenu
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(service: WalletService) {
    val isTestnet by service.isTestnet.collectAsState()
    val notificationsEnabled by service.notificationsEnabled.collectAsState()
    val health by service.health.collectAsState()
    val wsConnected by service.wsConnected.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    val peerInfoList by service.peerInfos.collectAsState()
    val peers by service.peers.collectAsState()
    val connectedPeer by service.connectedPeer.collectAsState()
    val reindexing by service.reindexing.collectAsState()
    val consolidating by service.consolidating.collectAsState()
    val consolidationStatus by service.consolidationStatus.collectAsState()
    val backups by service.backups.collectAsState()
    val context = LocalContext.current


    // Load backups on first composition
    LaunchedEffect(Unit) { service.refreshBackups() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Display ──
            Text("Display", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Decimal Places", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(2, 4, 6, 8).forEach { places ->
                            FilterChip(
                                selected = decimalPlaces == places,
                                onClick = { service.setDecimalPlaces(places) },
                                label = { Text("$places") },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Notifications ──
            Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Transaction Alerts", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Sound and notification when TIME is received",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { service.setNotificationsEnabled(it) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Network ──
            Text("Network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Network")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isTestnet) "Testnet" else "Mainnet",
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = isTestnet,
                                onCheckedChange = { service.switchNetwork(it) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("WebSocket")
                        Text(
                            if (wsConnected) "Connected" else "Disconnected",
                            color = if (wsConnected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error,
                        )
                    }
                    health?.let {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Block Height")
                            Text("${it.blockHeight}", fontWeight = FontWeight.Medium)
                        }
                    }
                    connectedPeer?.let {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Connected To")
                            Text(
                                it.removePrefix("https://").removePrefix("http://"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            // ── Peer List ──
            if (peerInfoList.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Peers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        peerInfoList.forEach { peer ->
                            val host = peer.endpoint
                                .removePrefix("https://").removePrefix("http://")
                            val isConnected = peer.isActive
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Status indicator
                                Icon(
                                    if (peer.isHealthy) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = if (peer.isHealthy) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                // Host + block height
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        host,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isConnected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val details = buildList {
                                        peer.pingMs?.let { add("${it}ms") }
                                        peer.blockHeight?.let { add("H:$it") }
                                        if (peer.wsAvailable) add("WS")
                                        if (isConnected) add("Active")
                                    }
                                    if (details.isNotEmpty()) {
                                        Text(
                                            details.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // Switch button (only for non-active healthy peers)
                                if (peer.isHealthy && !isConnected) {
                                    TextButton(
                                        onClick = { service.switchPeer(peer.endpoint) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) {
                                        Text("Switch", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (peer != peerInfoList.last()) {
                                Divider(Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Node Configuration ──
            NodeConfigSection(service)

            Spacer(Modifier.height(24.dp))

            // ── Wallet Management ──
            Text("Wallet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            var showDeleteConfirm by remember { mutableStateOf(false) }
            var showReindexConfirm by remember { mutableStateOf(false) }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    // Change PIN
                    OutlinedButton(
                        onClick = { service.navigateTo(Screen.ChangePin) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Change PIN")
                    }

                    Spacer(Modifier.height(8.dp))

                    // Lock Wallet
                    OutlinedButton(
                        onClick = { service.lockWallet() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Lock Wallet")
                    }

                    Spacer(Modifier.height(8.dp))

                    // Export Backup
                    OutlinedButton(
                        onClick = {
                            val file = service.getWalletFile()
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Export Wallet Backup"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Export Wallet Backup")
                    }

                    Spacer(Modifier.height(8.dp))

                    // Create Backup
                    OutlinedButton(
                        onClick = { service.createBackup() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create Backup Now")
                    }

                    Spacer(Modifier.height(8.dp))

                    // Consolidate UTXOs
                    var showConsolidateConfirm by remember { mutableStateOf(false) }
                    if (consolidationStatus != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (consolidating) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    consolidationStatus ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                if (consolidating) {
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(
                                        onClick = { service.cancelConsolidation() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) {
                                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = { showConsolidateConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !consolidating && !reindexing,
                    ) {
                        Icon(Icons.Default.CompareArrows, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Consolidate UTXOs")
                    }
                    if (showConsolidateConfirm) {
                        AlertDialog(
                            onDismissRequest = { showConsolidateConfirm = false },
                            title = { Text("Consolidate UTXOs?") },
                            text = {
                                Text(
                                    "This merges multiple small UTXOs per address into a single UTXO " +
                                        "by sending them back to the same address. " +
                                        "Network fees apply per batch. Useful when you have many small block rewards.",
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showConsolidateConfirm = false
                                    service.consolidateUtxos()
                                }) {
                                    Text("Consolidate")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showConsolidateConfirm = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Reindex
                    OutlinedButton(
                        onClick = { showReindexConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !reindexing,
                    ) {
                        if (reindexing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (reindexing) "Reindexing..." else "Reindex Wallet")
                    }

                    Spacer(Modifier.height(8.dp))

                    // Delete Wallet
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete Wallet")
                    }
                }
            }

            // Reindex confirmation dialog
            if (showReindexConfirm) {
                AlertDialog(
                    onDismissRequest = { showReindexConfirm = false },
                    title = { Text("Reindex Wallet?") },
                    text = {
                        Text(
                            "This will erase cached UTXOs and transaction history, then " +
                                "resync everything from the masternode. Your wallet keys " +
                                "and balance are not affected.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showReindexConfirm = false
                            service.reindexWallet()
                        }) {
                            Text("Reindex")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showReindexConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            // Delete confirmation dialog — with backup warning
            if (showDeleteConfirm) {
                DeleteWalletDialog(
                    onDismiss = { showDeleteConfirm = false },
                    onConfirm = {
                        showDeleteConfirm = false
                        service.deleteWallet()
                    },
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Backups ──
            BackupSection(service, backups)

            Spacer(Modifier.height(24.dp))

            // ── About ──
            val appVersion = remember {
                try {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    val name = info.versionName ?: "—"
                    @Suppress("DEPRECATION")
                    val code = info.versionCode
                    "$name ($code)"
                } catch (_: Exception) { "—" }
            }
            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Version")
                        Text(appVersion, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("License")
                        Text("BSL 1.1")
                    }
                }
            }
        }
    }
}

// ── Delete Wallet Dialog with backup warning ──

@Composable
private fun DeleteWalletDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var confirmText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
        },
        title = { Text("Delete Wallet?", color = MaterialTheme.colorScheme.error) },
        text = {
            Column {
                Text(
                    "⚠ Back up your recovery phrase before deleting!",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Deleting without a backup means permanent loss of funds. " +
                        "An automatic backup of the wallet file will be created, but " +
                        "you should also have your 12/24-word recovery phrase written down.",
                )
                Spacer(Modifier.height(16.dp))
                Text("Type DELETE to confirm:", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = { confirmText = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("DELETE") },
                    isError = confirmText.isNotEmpty() && confirmText != "DELETE",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmText == "DELETE",
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Delete Permanently")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ── Node Configuration Section ──

@Composable
private fun NodeConfigSection(service: WalletService) {
    var expanded by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var isEditing by remember { mutableStateOf(false) }

    Text("Node Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "time.conf",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                TextButton(onClick = {
                    if (!expanded) {
                        configText = service.getConfigText()
                    }
                    expanded = !expanded
                    isEditing = false
                }) {
                    Text(if (expanded) "Hide" else "Edit")
                }
            }

            Text(
                "Add masternode peers manually when the website API is down.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = configText,
                    onValueChange = {
                        configText = it
                        isEditing = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    maxLines = 20,
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            service.saveConfigText(configText)
                            isEditing = false
                        },
                        enabled = isEditing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { service.reconnect() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reconnect", maxLines = 1)
                    }
                }
            }
        }
    }
}

// ── Backup Management Section ──

@Composable
private fun BackupSection(service: WalletService, backups: List<java.io.File>) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showDeleteBackup by remember { mutableStateOf<java.io.File?>(null) }
    var showRestoreBackup by remember { mutableStateOf<java.io.File?>(null) }

    Text("Backups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))

    if (backups.isEmpty()) {
        Text(
            "No backups found",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        backups.forEach { file ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // Show network badge from filename
                        val netLabel = when {
                            file.name.contains("testnet") -> "Testnet"
                            file.name.contains("mainnet") -> "Mainnet"
                            else -> "Unknown"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "$netLabel · " +
                                dateFormat.format(Date(file.lastModified())) +
                                " · ${file.length() / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showRestoreBackup = file }) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = "Restore",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = { showDeleteBackup = file }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    // Restore confirmation
    showRestoreBackup?.let { file ->
        val backupNet = when {
            file.name.contains("testnet") -> "Testnet"
            file.name.contains("mainnet") -> "Mainnet"
            else -> null
        }
        val isTestnet = service.isTestnet.collectAsState().value
        val currentNet = if (isTestnet) "Testnet" else "Mainnet"
        val mismatch = backupNet != null && backupNet != currentNet

        AlertDialog(
            onDismissRequest = { showRestoreBackup = null },
            title = { Text("Restore Backup?") },
            text = {
                Column {
                    if (mismatch) {
                        Text(
                            "⚠ Network mismatch: this backup is from $backupNet " +
                                "but you are currently on $currentNet. Addresses will not match.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "This will replace your current wallet with the backup " +
                            "\"${file.name}\". Your current wallet will be backed up first.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreBackup = null
                    service.restoreBackup(file, null)
                }) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreBackup = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Delete backup confirmation
    showDeleteBackup?.let { file ->
        AlertDialog(
            onDismissRequest = { showDeleteBackup = null },
            title = { Text("Delete Backup?") },
            text = { Text("Delete \"${file.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteBackup = null
                        service.deleteBackup(file)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteBackup = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

