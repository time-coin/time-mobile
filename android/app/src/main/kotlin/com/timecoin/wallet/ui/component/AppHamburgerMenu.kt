package com.timecoin.wallet.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.timecoin.wallet.model.PaymentRequestStatus
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHamburgerMenu(service: WalletService) {
    val paymentRequests by service.paymentRequests.collectAsState()
    val pendingIncomingCount = remember(paymentRequests) {
        paymentRequests.count { !it.isOutgoing && it.status == PaymentRequestStatus.Pending }
    }
    var menuExpanded by remember { mutableStateOf(false) }

    BadgedBox(badge = { if (pendingIncomingCount > 0) Badge() }) {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Default.Menu, contentDescription = "Menu")
        }
    }

    DropdownMenu(
        expanded = menuExpanded,
        onDismissRequest = { menuExpanded = false },
    ) {
        DropdownMenuItem(
            text = { Text("Home") },
            onClick = { menuExpanded = false; service.navigateTo(Screen.Overview) },
            leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("Send") },
            onClick = { menuExpanded = false; service.navigateTo(Screen.Send) },
            leadingIcon = { Icon(Icons.Default.Send, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("Receive") },
            onClick = { menuExpanded = false; service.navigateTo(Screen.Receive) },
            leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
        )
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Payment Requests")
                    if (pendingIncomingCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Badge { Text("$pendingIncomingCount") }
                    }
                }
            },
            onClick = { menuExpanded = false; service.navigateTo(Screen.PaymentRequests) },
            leadingIcon = { Icon(Icons.Default.RequestPage, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("Transactions") },
            onClick = { menuExpanded = false; service.navigateTo(Screen.Transactions) },
            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("Connections") },
            onClick = { menuExpanded = false; service.navigateTo(Screen.Connections) },
            leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
        )
        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = { menuExpanded = false; service.navigateTo(Screen.Settings) },
            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
        )
        @Suppress("DEPRECATION")
        Divider()
        DropdownMenuItem(
            text = { Text("Exit", color = MaterialTheme.colorScheme.error) },
            onClick = { menuExpanded = false; service.logout() },
            leadingIcon = {
                Icon(
                    Icons.Default.ExitToApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
        )
    }
}
