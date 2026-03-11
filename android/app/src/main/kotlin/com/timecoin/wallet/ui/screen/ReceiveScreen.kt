package com.timecoin.wallet.ui.screen

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(service: WalletService) {
    val addresses by service.addresses.collectAsState()
    var selectedIndex by remember { mutableStateOf(0) }
    val clipboardManager = LocalClipboardManager.current

    val currentAddress = addresses.getOrNull(selectedIndex) ?: ""

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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // QR code
            if (currentAddress.isNotEmpty()) {
                val qrBitmap = remember(currentAddress) { generateQr(currentAddress) }
                qrBitmap?.let { bmp ->
                    Card {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "QR Code for $currentAddress",
                            modifier = Modifier.size(240.dp).padding(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // Address text
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = currentAddress,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                // Copy button
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(currentAddress))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy Address")
                }
                Spacer(Modifier.height(8.dp))

                // Address selector
                if (addresses.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        IconButton(
                            onClick = { if (selectedIndex > 0) selectedIndex-- },
                            enabled = selectedIndex > 0,
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous")
                        }
                        Text(
                            text = "Address ${selectedIndex + 1} of ${addresses.size}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        IconButton(
                            onClick = { if (selectedIndex < addresses.size - 1) selectedIndex++ },
                            enabled = selectedIndex < addresses.size - 1,
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Next")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Generate new address
                OutlinedButton(
                    onClick = {
                        service.generateAddress()
                        selectedIndex = addresses.size // will be the new one
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate New Address")
                }
            } else {
                Text(
                    text = "No addresses available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
