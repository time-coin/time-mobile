package com.timecoin.wallet.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timecoin.wallet.crypto.Address
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.model.PaymentRequest
import com.timecoin.wallet.model.PaymentRequestStatus
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.AppHamburgerMenu
import com.timecoin.wallet.ui.component.formatSatoshis
import com.timecoin.wallet.ui.component.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentRequestScreen(service: WalletService) {
    val isTestnet by service.isTestnet.collectAsState()
    val addresses by service.addresses.collectAsState()
    val contacts by service.contacts.collectAsState()
    val scannedAddress by service.scannedAddress.collectAsState()
    val loading by service.loading.collectAsState()

    // Two-phase UI: contact list → form (mirrors SendScreen)
    var showForm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Form fields
    var payerAddress by remember { mutableStateOf("") }
    var payerName by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var requesterName by remember { mutableStateOf("") }
    var showOptional by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var fromAddressIdx by remember { mutableStateOf(0) }
    var fromDropdownExpanded by remember { mutableStateOf(false) }

    val network = if (isTestnet) NetworkType.Testnet else NetworkType.Mainnet
    val ownAddresses = remember(addresses) { addresses.toSet() }
    val amountSats = (amountStr.toDoubleOrNull()?.times(100_000_000))?.toLong() ?: 0L

    // Pair each own address with its label from contacts
    val ownAddressOptions = remember(addresses, contacts) {
        val contactMap = contacts.associate { it.address to it.label }
        addresses.mapIndexed { idx, addr ->
            val label = contactMap[addr]?.takeIf { it.isNotBlank() } ?: "Address ${idx + 1}"
            Triple(idx, addr, label)
        }
    }

    // External contacts for picker
    val externalContacts = remember(contacts) { contacts.filter { !it.isOwned } }
    val filteredContacts = remember(externalContacts, searchQuery) {
        if (searchQuery.isBlank()) externalContacts
        else {
            val q = searchQuery.lowercase()
            externalContacts.filter {
                it.name.lowercase().contains(q) ||
                    it.address.lowercase().contains(q) ||
                    it.phone.contains(q)
            }
        }
    }

    fun resetForm() {
        payerAddress = ""; payerName = ""; amountStr = ""; memo = ""; requesterName = ""
        addressError = null; showOptional = false; fromAddressIdx = 0
    }

    // Consume QR scan result
    LaunchedEffect(scannedAddress) {
        scannedAddress?.let { addr ->
            payerAddress = addr
            service.clearScannedAddress()
            addressError = validatePayerAddress(addr, network, ownAddresses)
            showForm = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showForm) "Request Payment" else "Request Payment") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showForm) { showForm = false; resetForm() }
                        else service.navigateTo(Screen.PaymentRequests)
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!showForm) {
                        // Manual entry without picking a contact
                        IconButton(onClick = { showForm = true }) {
                            Icon(Icons.Default.RequestPage, contentDescription = "Enter manually")
                        }
                    }
                    AppHamburgerMenu(service)
                },
            )
        },
    ) { padding ->
        if (showForm) {
            // ── Request Form ──
            val clipboardManager = LocalClipboardManager.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))

                // Payer name chip (when pre-filled from contact)
                if (payerName.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            payerName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // "Receive To" address selector
                if (ownAddressOptions.size > 1) {
                    val selectedOption = ownAddressOptions.getOrNull(fromAddressIdx) ?: ownAddressOptions.first()
                    ExposedDropdownMenuBox(
                        expanded = fromDropdownExpanded,
                        onExpandedChange = { fromDropdownExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedOption.third,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Receive To") },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fromDropdownExpanded) },
                        )
                        ExposedDropdownMenu(
                            expanded = fromDropdownExpanded,
                            onDismissRequest = { fromDropdownExpanded = false },
                        ) {
                            ownAddressOptions.forEach { (idx, _, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        fromAddressIdx = idx
                                        fromDropdownExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Payer address
                OutlinedTextField(
                    value = payerAddress,
                    onValueChange = {
                        payerAddress = it
                        addressError = validatePayerAddress(it, network, ownAddresses)
                    },
                    label = { Text("Payer's TIME Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = addressError != null,
                    supportingText = addressError?.let { err ->
                        { Text(err, color = MaterialTheme.colorScheme.error) }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.trim()?.let {
                                    payerAddress = it
                                    addressError = validatePayerAddress(it, network, ownAddresses)
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste address")
                            }
                            IconButton(onClick = { service.navigateTo(Screen.PaymentRequestQrScanner) }) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))

                // Amount
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Amount (TIME)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(4.dp))

                // Optional fields toggle
                TextButton(onClick = { showOptional = !showOptional }) {
                    Icon(
                        if (showOptional) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (showOptional) "Hide optional fields" else "Add your name or a memo",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (showOptional) {
                    OutlinedTextField(
                        value = requesterName,
                        onValueChange = { requesterName = it },
                        label = { Text("Your Name (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = memo,
                        onValueChange = { memo = it },
                        label = { Text("Memo (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))

                val canSend = payerAddress.isNotEmpty() && addressError == null && amountSats > 0 && !loading
                Button(
                    onClick = {
                        service.sendPaymentRequest(
                            payerAddress = payerAddress,
                            amountSats = amountSats,
                            memo = memo.trim(),
                            requesterName = requesterName.trim(),
                            fromAddressIdx = fromAddressIdx,
                        )
                        resetForm()
                        showForm = false
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Send Payment Request", fontSize = 16.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        } else {
            // ── Contact Picker ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
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
                                    "Tap the icon in the top-right to enter an address manually",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        filteredContacts.forEach { contact ->
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        payerAddress = contact.address
                                        payerName = contact.name.ifBlank { "" }
                                        addressError = validatePayerAddress(contact.address, network, ownAddresses)
                                        showForm = true
                                    },
                            ) {
                                Row(
                                    Modifier.padding(12.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
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
                                            Text(
                                                contact.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        Text(
                                            contact.address.take(14) + "…" + contact.address.takeLast(6),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PaymentRequestItem(
    request: PaymentRequest,
    decimalPlaces: Int,
    onCancel: (() -> Unit)?,
    onReview: (() -> Unit)?,
    onDelete: (() -> Unit)? = null,
    onViewTransaction: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val statusColor = when (request.status) {
        PaymentRequestStatus.Pending -> Color(0xFFFFA500)
        PaymentRequestStatus.Accepted -> Color(0xFF2196F3)
        PaymentRequestStatus.Declined -> MaterialTheme.colorScheme.error
        PaymentRequestStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
        PaymentRequestStatus.Paid -> Color(0xFF00C850)
    }
    val statusLabel = when (request.status) {
        PaymentRequestStatus.Pending -> "Pending"
        PaymentRequestStatus.Accepted -> "Accepted"
        PaymentRequestStatus.Declined -> "Declined"
        PaymentRequestStatus.Cancelled -> "Cancelled"
        PaymentRequestStatus.Paid -> "Paid"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val counterparty = if (request.isOutgoing) {
                        request.payerAddress.take(12) + "…" + request.payerAddress.takeLast(6)
                    } else {
                        val name = request.requesterName.ifBlank { null }
                        name ?: (request.requesterAddress.take(12) + "…" + request.requesterAddress.takeLast(6))
                    }
                    Text(
                        text = if (request.isOutgoing) "To: $counterparty" else "From: $counterparty",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${formatSatoshis(request.amountSats, decimalPlaces)} TIME",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (request.memo.isNotBlank()) {
                        Text(
                            text = request.memo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = formatTime(request.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (request.status == PaymentRequestStatus.Pending && request.expiresAt > 0) {
                        val nowSecs = System.currentTimeMillis() / 1000
                        val remaining = request.expiresAt - nowSecs
                        val expiryText = when {
                            remaining <= 0 -> "Expired"
                            remaining < 3600 -> "Expires in ${remaining / 60}m"
                            remaining < 86400 -> "Expires in ${remaining / 3600}h"
                            else -> "Expires in ${remaining / 86400}d"
                        }
                        val expiryColor = when {
                            remaining <= 0 -> MaterialTheme.colorScheme.error
                            remaining < 86400 -> Color(0xFFFFA500)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = expiryText,
                            style = MaterialTheme.typography.labelSmall,
                            color = expiryColor,
                        )
                    }
                    // Paid txid row
                    if (request.status == PaymentRequestStatus.Paid && request.paidTxid.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "TXID",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = request.paidTxid,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("txid", request.paidTxid))
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy txid",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (onViewTransaction != null) {
                            TextButton(
                                onClick = { onViewTransaction(request.paidTxid) },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            ) {
                                Icon(
                                    Icons.Default.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("View transaction", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Card(colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.15f))) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    // Delivery/viewed indicator for outgoing pending requests
                    if (request.isOutgoing && request.status == PaymentRequestStatus.Pending) {
                        Spacer(Modifier.height(3.dp))
                        val (deliveryLabel, deliveryColor) = when {
                            request.viewed -> "Seen" to Color(0xFF2196F3)
                            request.delivered -> "Delivered" to Color(0xFF00C850)
                            else -> "Queued" to Color(0xFFFFA500)
                        }
                        val deliveryIcon = when {
                            request.viewed -> Icons.Default.Visibility
                            request.delivered -> Icons.Default.CheckCircle
                            else -> Icons.Default.HourglassEmpty
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                deliveryIcon,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = deliveryColor,
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = deliveryLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = deliveryColor,
                            )
                        }
                    }
                    if (onCancel != null) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.Cancel, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Cancel", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (onReview != null) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onReview,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text("Review", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (onDelete != null) {
                        Spacer(Modifier.height(4.dp))
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentRequestsScreen(service: WalletService) {
    val paymentRequests by service.paymentRequests.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()

    val pendingIncoming = remember(paymentRequests) {
        paymentRequests.filter { !it.isOutgoing && it.status == PaymentRequestStatus.Pending }
    }
    val pendingOutgoing = remember(paymentRequests) {
        paymentRequests.filter { it.isOutgoing && it.status == PaymentRequestStatus.Pending }
    }
    val outgoingHistory = remember(paymentRequests) {
        paymentRequests.filter { it.isOutgoing && it.status != PaymentRequestStatus.Pending }
    }
    val incomingHistory = remember(paymentRequests) {
        paymentRequests.filter { !it.isOutgoing && it.status != PaymentRequestStatus.Pending }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment Requests") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { service.navigateTo(Screen.PaymentRequest) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.RequestPage, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Request Payment", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))

        if (paymentRequests.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.RequestPage,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No payment requests yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Requests you send or receive will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        } else {
                // ── Pending outgoing (requests I sent, awaiting payment) ──
                if (pendingOutgoing.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Pending Requests",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Badge { Text("${pendingOutgoing.size}") }
                    }
                    Spacer(Modifier.height(8.dp))
                    pendingOutgoing.forEach { req ->
                        PaymentRequestItem(
                            request = req,
                            decimalPlaces = decimalPlaces,
                            onCancel = { service.cancelPaymentRequest(req.id) },
                            onReview = null,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // ── Pending incoming (needs action from me) ──
                if (pendingIncoming.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Awaiting Your Response",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(8.dp))
                        Badge { Text("${pendingIncoming.size}") }
                    }
                    Spacer(Modifier.height(8.dp))
                    pendingIncoming.forEach { req ->
                        PaymentRequestItem(
                            request = req,
                            decimalPlaces = decimalPlaces,
                            onCancel = null,
                            onReview = { service.reviewPaymentRequest(req) },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // ── Outgoing history ──
                if (outgoingHistory.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Sent (History)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    outgoingHistory.forEach { req ->
                        PaymentRequestItem(
                            request = req,
                            decimalPlaces = decimalPlaces,
                            onCancel = null,
                            onReview = null,
                            onDelete = { service.deletePaymentRequest(req.id) },
                            onViewTransaction = if (req.paidTxid.isNotBlank())
                                { txid -> service.showTransactionByTxid(txid) } else null,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                // ── Incoming history ──
                if (incomingHistory.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Received (History)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    incomingHistory.forEach { req ->
                        PaymentRequestItem(
                            request = req,
                            decimalPlaces = decimalPlaces,
                            onCancel = null,
                            onReview = null,
                            onDelete = { service.deletePaymentRequest(req.id) },
                            onViewTransaction = if (req.paidTxid.isNotBlank())
                                { txid -> service.showTransactionByTxid(txid) } else null,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun validatePayerAddress(
    addr: String,
    network: NetworkType,
    ownAddresses: Set<String>,
): String? {
    if (addr.isEmpty()) return null
    if (addr in ownAddresses) return "Cannot request payment from your own address"
    return try {
        val parsed = Address.fromString(addr)
        if (parsed.network != network)
            "This is a ${parsed.network.name.lowercase()} address — wallet is on ${network.name.lowercase()}"
        else null
    } catch (e: Exception) {
        e.message
    }
}
