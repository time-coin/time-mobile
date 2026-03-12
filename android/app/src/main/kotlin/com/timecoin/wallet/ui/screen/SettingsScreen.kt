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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(service: WalletService) {
    val isTestnet by service.isTestnet.collectAsState()
    val health by service.health.collectAsState()
    val wsConnected by service.wsConnected.collectAsState()
    val contacts by service.contacts.collectAsState()
    val addresses by service.addresses.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    val context = LocalContext.current

    var showAddContact by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var contactAddress by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Network info
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

            // Network info
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
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Connected Peers")
                            Text("${it.peerCount}", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Addresses
            Text("My Addresses (${addresses.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    addresses.forEachIndexed { index, addr ->
                        Text(
                            text = "${index + 1}. $addr",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (index < addresses.size - 1) Spacer(Modifier.height(4.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Contacts
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Contacts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showAddContact = !showAddContact }) {
                    Icon(
                        if (showAddContact) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = "Add contact",
                    )
                }
            }

            if (showAddContact) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            label = { Text("Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = contactAddress,
                            onValueChange = { contactAddress = it },
                            label = { Text("TIME Address") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (contactName.isNotBlank() && contactAddress.isNotBlank()) {
                                    service.saveContact(contactName.trim(), contactAddress.trim())
                                    contactName = ""
                                    contactAddress = ""
                                    showAddContact = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Save Contact")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            val externalContacts = contacts.filter { !it.isOwned }
            if (externalContacts.isEmpty()) {
                Text(
                    "No contacts saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                externalContacts.forEach { contact ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, fontWeight = FontWeight.Medium)
                                Text(
                                    contact.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { service.deleteContact(contact.address) }) {
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

            Spacer(Modifier.height(24.dp))

            // Wallet Management
            Text("Wallet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            var showMnemonic by remember { mutableStateOf(false) }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            val mnemonic = if (showMnemonic) service.getMnemonic() else null

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    // View Recovery Phrase
                    OutlinedButton(
                        onClick = { showMnemonic = !showMnemonic },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            if (showMnemonic) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (showMnemonic) "Hide Recovery Phrase" else "View Recovery Phrase")
                    }

                    if (showMnemonic && mnemonic != null) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            ),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "Keep this phrase safe! Anyone with it can access your funds.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    mnemonic,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
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

            // Delete confirmation dialog
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete Wallet?") },
                    text = {
                        Text(
                            "This will delete your wallet and create an automatic backup. " +
                                "Make sure you have saved your recovery phrase before proceeding.",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                service.deleteWallet()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))

            // About
            Text("About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Version")
                        Text("1.0.0")
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
