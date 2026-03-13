package com.timecoin.wallet.ui.screen

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.db.ContactEntity
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.formatSatoshis

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

    // Which view is shown: contact list or send form
    var showSendForm by remember { mutableStateOf(false) }
    var showAddContact by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Send form fields
    var recipientName by remember { mutableStateOf("") }
    var toAddress by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    var recipientEmail by remember { mutableStateOf("") }
    var recipientPhone by remember { mutableStateOf("") }
    var showOptionalFields by remember { mutableStateOf(false) }

    // Add contact form fields
    var newContactName by remember { mutableStateOf("") }
    var newContactAddress by remember { mutableStateOf("") }
    var newContactEmail by remember { mutableStateOf("") }
    var newContactPhone by remember { mutableStateOf("") }
    var newContactAddressError by remember { mutableStateOf<String?>(null) }

    // Consume scanned address from QR scanner
    val scannedAddress by service.scannedAddress.collectAsState()
    val network = if (isTestnet) NetworkType.Testnet else NetworkType.Mainnet
    val amountSats = (amountStr.toDoubleOrNull()?.times(100_000_000))?.toLong() ?: 0L

    fun validateAddress(addr: String): String? {
        if (addr.isEmpty()) return null
        return try {
            val parsed = Address.fromString(addr)
            if (parsed.network != network) {
                "This is a ${parsed.network.name.lowercase()} address — wallet is on ${network.name.lowercase()}"
            } else null
        } catch (e: Exception) {
            e.message
        }
    }

    fun fillFromContact(contact: ContactEntity) {
        recipientName = contact.name
        toAddress = contact.address
        recipientEmail = contact.email
        recipientPhone = contact.phone
        addressError = validateAddress(contact.address)
        showOptionalFields = contact.email.isNotBlank() || contact.phone.isNotBlank()
        showSendForm = true
    }

    fun resetAddContactForm() {
        newContactName = ""
        newContactAddress = ""
        newContactEmail = ""
        newContactPhone = ""
        newContactAddressError = null
        showAddContact = false
    }

    LaunchedEffect(scannedAddress) {
        scannedAddress?.let { addr ->
            toAddress = addr
            service.clearScannedAddress()
            addressError = validateAddress(addr)
            showSendForm = true
        }
    }

    val externalContacts = contacts.filter { !it.isOwned }
    val filteredContacts = remember(externalContacts, searchQuery) {
        if (searchQuery.isBlank()) externalContacts
        else {
            val q = searchQuery.lowercase()
            externalContacts.filter {
                it.name.lowercase().contains(q) ||
                    it.address.lowercase().contains(q) ||
                    it.email.lowercase().contains(q) ||
                    it.phone.contains(q)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showSendForm) "Send TIME" else "Send") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showSendForm) showSendForm = false
                        else service.navigateTo(Screen.Overview)
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!showSendForm) {
                        // New send (manual entry)
                        IconButton(onClick = { showSendForm = true }) {
                            Icon(Icons.Default.Send, contentDescription = "New Send")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (showSendForm) {
            // ── Send Form ──
            SendFormContent(
                modifier = Modifier.padding(padding),
                recipientName = recipientName,
                onRecipientNameChange = { recipientName = it },
                toAddress = toAddress,
                onToAddressChange = { toAddress = it; addressError = validateAddress(it) },
                addressError = addressError,
                amountStr = amountStr,
                onAmountChange = { amountStr = it },
                amountSats = amountSats,
                balance = balance.confirmed,
                recipientEmail = recipientEmail,
                onRecipientEmailChange = { recipientEmail = it },
                recipientPhone = recipientPhone,
                onRecipientPhoneChange = { recipientPhone = it },
                showOptionalFields = showOptionalFields,
                onToggleOptional = { showOptionalFields = !showOptionalFields },
                loading = loading,
                onScanQr = onScanQr,
                onSend = {
                    val isOwned = contacts.any { it.address == toAddress && it.isOwned }
                    if (!isOwned) {
                        service.saveContact(
                            name = recipientName.trim(),
                            address = toAddress.trim(),
                            email = recipientEmail.trim(),
                            phone = recipientPhone.trim(),
                        )
                    }
                    service.sendTransaction(toAddress, amountSats)
                },
            )
        } else {
            // ── Contact List with Search ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                // Search bar
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search contacts…") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = MaterialTheme.shapes.medium,
                )

                Spacer(Modifier.height(12.dp))

                // Add contact button / form
                if (showAddContact) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("New Contact", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newContactName,
                                onValueChange = { newContactName = it },
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newContactAddress,
                                onValueChange = {
                                    newContactAddress = it
                                    newContactAddressError = if (it.isBlank()) null
                                    else validateAddress(it)
                                },
                                label = { Text("TIME Address") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                isError = newContactAddressError != null,
                                supportingText = newContactAddressError?.let { err ->
                                    { Text(err, color = MaterialTheme.colorScheme.error) }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newContactEmail,
                                onValueChange = { newContactEmail = it },
                                label = { Text("Email") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = newContactPhone,
                                onValueChange = { newContactPhone = it },
                                label = { Text("Phone") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { resetAddContactForm() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Cancel") }
                                Button(
                                    onClick = {
                                        service.saveContact(
                                            name = newContactName.trim(),
                                            address = newContactAddress.trim(),
                                            email = newContactEmail.trim(),
                                            phone = newContactPhone.trim(),
                                        )
                                        resetAddContactForm()
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = newContactAddress.isNotBlank() && newContactAddressError == null,
                                ) { Text("Save") }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                } else {
                    OutlinedButton(
                        onClick = { showAddContact = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Contact")
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Contact list
                if (filteredContacts.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (searchQuery.isNotBlank()) "No matching contacts"
                                else "No contacts yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchQuery.isBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Add a contact or tap the send icon to enter an address manually",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        filteredContacts.forEach { contact ->
                            ContactRow(
                                contact = contact,
                                onTap = { fillFromContact(contact) },
                                onDelete = { service.deleteContact(contact.address) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: ContactEntity,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onTap() },
    ) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar circle
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        (contact.name.firstOrNull() ?: contact.address.first()).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (contact.name.isNotBlank()) {
                    Text(contact.name, fontWeight = FontWeight.Medium)
                }
                Text(
                    contact.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val details = buildList {
                    if (contact.email.isNotBlank()) add(contact.email)
                    if (contact.phone.isNotBlank()) add(contact.phone)
                }
                if (details.isNotEmpty()) {
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SendFormContent(
    modifier: Modifier = Modifier,
    recipientName: String,
    onRecipientNameChange: (String) -> Unit,
    toAddress: String,
    onToAddressChange: (String) -> Unit,
    addressError: String?,
    amountStr: String,
    onAmountChange: (String) -> Unit,
    amountSats: Long,
    balance: Long,
    recipientEmail: String,
    onRecipientEmailChange: (String) -> Unit,
    recipientPhone: String,
    onRecipientPhoneChange: (String) -> Unit,
    showOptionalFields: Boolean,
    onToggleOptional: () -> Unit,
    loading: Boolean,
    onScanQr: () -> Unit,
    onSend: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Recipient name
        OutlinedTextField(
            value = recipientName,
            onValueChange = onRecipientNameChange,
            label = { Text("Recipient Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp))
            },
        )
        Spacer(Modifier.height(8.dp))

        // Recipient address
        OutlinedTextField(
            value = toAddress,
            onValueChange = onToAddressChange,
            label = { Text("TIME Address") },
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
        Spacer(Modifier.height(8.dp))

        // Amount
        OutlinedTextField(
            value = amountStr,
            onValueChange = onAmountChange,
            label = { Text("Amount (TIME)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = {
                Text("Available: ${formatSatoshis(balance)} TIME")
            },
        )
        Spacer(Modifier.height(4.dp))

        // Optional fields toggle
        TextButton(onClick = onToggleOptional) {
            Icon(
                if (showOptionalFields) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (showOptionalFields) "Hide optional fields" else "Add email or phone",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (showOptionalFields) {
            OutlinedTextField(
                value = recipientEmail,
                onValueChange = onRecipientEmailChange,
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                leadingIcon = {
                    Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(20.dp))
                },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = recipientPhone,
                onValueChange = onRecipientPhoneChange,
                label = { Text("Phone") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                leadingIcon = {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(20.dp))
                },
            )
            Spacer(Modifier.height(8.dp))
        }

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
                            "${formatSatoshis(amountSats)} TIME",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Fee:", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${formatSatoshis(fee)} TIME (${(feeRate * 100)}%)",
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
                            "${formatSatoshis(amountSats + fee)} TIME",
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
            amountSats <= balance &&
            !loading

        Button(
            onClick = onSend,
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
