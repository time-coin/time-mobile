package com.timecoin.wallet.ui.screen

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.AppHamburgerMenu

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(service: WalletService) {
    val peers by service.peers.collectAsState()
    val connectedPeer by service.connectedPeer.collectAsState()
    val health by service.health.collectAsState()
    val wsConnected by service.wsConnected.collectAsState()
    val isTestnet by service.isTestnet.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Peers") },
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
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(16.dp),
    ) {
        Text(
            text = "Connections",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        // Network info card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Network",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                DetailRow("Type", if (isTestnet) "Testnet" else "Mainnet")
                DetailRow("Port", if (isTestnet) "24101" else "24001")
                health?.let {
                    DetailRow("Block Height", it.blockHeight.toString())
                    DetailRow("Version", it.version)
                    DetailRow("Peer Count", it.peerCount.toString())
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Connection status card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (connectedPeer != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (connectedPeer != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (connectedPeer != null) "Connected to masternode" else "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (wsConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = if (wsConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (wsConnected) "WebSocket connected" else "WebSocket disconnected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                connectedPeer?.let {
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Active peer", it)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        // Reconnect button
        Button(
            onClick = { service.reconnect() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Reconnect")
        }
        Spacer(Modifier.height(16.dp))

        // Discovered peers list
        Text(
            text = "Discovered Peers (${peers.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        if (peers.isEmpty()) {
            Text(
                text = "No peers discovered yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    peers.forEachIndexed { index, peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (peer == connectedPeer) Icons.Default.CheckCircle
                                    else Icons.Default.Circle,
                                contentDescription = null,
                                tint = if (peer == connectedPeer) Color(0xFF4CAF50)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = peer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (peer == connectedPeer)
                                    MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (peer == connectedPeer) FontWeight.Medium
                                    else FontWeight.Normal,
                            )
                        }
                        if (index < peers.lastIndex) {
                            @Suppress("DEPRECATION")
                            Divider(modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
    } // end Column
    } // end Scaffold content
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
