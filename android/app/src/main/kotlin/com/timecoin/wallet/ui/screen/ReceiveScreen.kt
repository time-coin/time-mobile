package com.timecoin.wallet.ui.screen

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.formatSatoshis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(service: WalletService) {
    val addresses by service.addresses.collectAsState()
    val utxos by service.utxos.collectAsState()
    val contacts by service.contacts.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    var selectedIndex by remember { mutableStateOf(0) }
    val clipboardManager = LocalClipboardManager.current

    // Editable label state
    var editingIndex by remember { mutableStateOf(-1) }
    var editLabelText by remember { mutableStateOf("") }

    val currentAddress = addresses.getOrNull(selectedIndex) ?: ""

    // Build a map of address -> label from contacts
    val labelMap = remember(contacts) {
        contacts.filter { it.isOwned }.associate { it.address to it.label }
    }

    val accentBlue = androidx.compose.ui.graphics.Color(0xFF2196F3)
    val balanceGreen = androidx.compose.ui.graphics.Color(0xFF00C850)

    // Per-address balance map from UTXOs
    val addressBalances = remember(utxos) {
        utxos.filter { it.spendable }
            .groupBy { it.address }
            .mapValues { (_, addrUtxos) -> addrUtxos.sumOf { it.amount } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive TIME") },
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
                .padding(horizontal = 16.dp),
        ) {
            // ── Fixed top section: QR code, address, copy button ──
            Column(
                modifier = Modifier.padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (currentAddress.isNotEmpty()) {
                    val qrBitmap = remember(currentAddress) { generateQr(currentAddress) }
                    qrBitmap?.let { bmp ->
                        Card {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "QR Code for $currentAddress",
                                modifier = Modifier.size(200.dp).padding(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = currentAddress,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = { clipboardManager.setText(AnnotatedString(currentAddress)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy Address")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Scrollable address list ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Your Addresses (${addresses.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(
                    onClick = {
                        service.generateAddress()
                        selectedIndex = addresses.size
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (addresses.isEmpty()) {
                    Text(
                        text = "No addresses available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    addresses.forEachIndexed { index, address ->
                        val isSelected = index == selectedIndex
                        val label = labelMap[address]?.ifEmpty { null } ?: "Address ${index + 1}"
                        val isEditing = editingIndex == index

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (!isEditing) Modifier.clickable { selectedIndex = index }
                                    else Modifier,
                                ),
                        ) {
                            // Blue accent bar for selected item
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (isSelected) accentBlue
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                    ),
                            )

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(
                                        if (isSelected) accentBlue.copy(alpha = 0.08f)
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (editingIndex == index) {
                                        OutlinedTextField(
                                            value = editLabelText,
                                            onValueChange = { editLabelText = it },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.labelMedium,
                                            trailingIcon = {
                                                Row {
                                                    IconButton(
                                                        onClick = {
                                                            service.updateAddressLabel(address, editLabelText)
                                                            editingIndex = -1
                                                        },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(18.dp))
                                                    }
                                                    IconButton(
                                                        onClick = { editingIndex = -1 },
                                                        modifier = Modifier.size(32.dp),
                                                    ) {
                                                        Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            },
                                        )
                                    } else {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) accentBlue else MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = address.take(16) + "..." + address.takeLast(8),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                if (editingIndex != index) {
                                    // Per-address balance on the right
                                    val addrBal = addressBalances[address] ?: 0L
                                    Text(
                                        text = if (addrBal > 0) formatSatoshis(addrBal, decimalPlaces)
                                               else "—",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (addrBal > 0) FontWeight.Bold else FontWeight.Normal,
                                        color = if (addrBal > 0) balanceGreen
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = {
                                            editLabelText = labelMap[address] ?: ""
                                            editingIndex = index
                                        },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit Label",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

private fun generateQr(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
