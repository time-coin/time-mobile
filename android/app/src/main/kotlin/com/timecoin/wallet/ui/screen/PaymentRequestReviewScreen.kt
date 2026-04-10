package com.timecoin.wallet.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
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
import com.timecoin.wallet.model.FeeSchedule
import com.timecoin.wallet.model.PaymentRequestStatus
import com.timecoin.wallet.service.Screen
import com.timecoin.wallet.service.WalletService
import com.timecoin.wallet.ui.component.AppHamburgerMenu
import com.timecoin.wallet.ui.component.formatSatoshis

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentRequestReviewScreen(service: WalletService) {
    val request by service.selectedPaymentRequest.collectAsState()
    val balance by service.balance.collectAsState()
    val decimalPlaces by service.decimalPlaces.collectAsState()
    val loading by service.loading.collectAsState()

    val req = request ?: run {
        // No request to review — go back
        LaunchedEffect(Unit) { service.navigateTo(Screen.Overview) }
        return
    }
    val context = LocalContext.current

    val fee = remember(req.amountSats) { FeeSchedule().calculateFee(req.amountSats) }
    val total = req.amountSats + fee
    val hasFunds = balance.confirmed >= total
    val alreadyActioned = req.status != PaymentRequestStatus.Pending

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment Request") },
                navigationIcon = {
                    IconButton(onClick = { service.navigateTo(Screen.PaymentRequests) }) {
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
                .padding(16.dp),
        ) {

            // Request details
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Incoming Payment Request",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (req.requesterName.isNotBlank()) {
                        DetailRow("From", req.requesterName)
                    }
                    DetailRow(
                        "Address",
                        req.requesterAddress.take(14) + "…" + req.requesterAddress.takeLast(8),
                    )
                    if (req.memo.isNotBlank()) {
                        DetailRow("Memo", req.memo)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Amount breakdown
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    AmountRow("Requested", "${formatSatoshis(req.amountSats, decimalPlaces)} TIME", bold = true)
                    AmountRow("Network Fee", "${formatSatoshis(fee, decimalPlaces)} TIME")
                    @Suppress("DEPRECATION")
                    Divider(Modifier.padding(vertical = 6.dp))
                    AmountRow("Total", "${formatSatoshis(total, decimalPlaces)} TIME", bold = true)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your available balance: ${formatSatoshis(balance.confirmed, decimalPlaces)} TIME",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasFunds)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (!hasFunds && !alreadyActioned) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Insufficient funds to fulfill this request.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (alreadyActioned) {
                Spacer(Modifier.height(12.dp))
                val statusColor = when (req.status) {
                    PaymentRequestStatus.Paid -> Color(0xFF00C850)
                    PaymentRequestStatus.Declined -> MaterialTheme.colorScheme.error
                    PaymentRequestStatus.Cancelled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = statusColor.copy(alpha = 0.12f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val label = when (req.status) {
                        PaymentRequestStatus.Paid -> "You paid this request."
                        PaymentRequestStatus.Declined -> "You declined this request."
                        PaymentRequestStatus.Cancelled -> "This request was cancelled by the sender."
                        PaymentRequestStatus.Accepted -> "Accepted — payment in progress."
                        else -> ""
                    }
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            label,
                            color = statusColor,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (req.status == PaymentRequestStatus.Paid && req.paidTxid.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Transaction ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor.copy(alpha = 0.7f),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = req.paidTxid,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = statusColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("txid", req.paidTxid))
                                        Toast.makeText(context, "Transaction ID copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy txid",
                                        modifier = Modifier.size(14.dp),
                                        tint = statusColor.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            TextButton(
                                onClick = { service.showTransactionByTxid(req.paidTxid) },
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
            }

            Spacer(Modifier.weight(1f))

            if (!alreadyActioned) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { service.declinePaymentRequest(req.id) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Decline")
                    }
                    Button(
                        onClick = { service.acceptPaymentRequest(req.id) },
                        modifier = Modifier.weight(1f).height(56.dp),
                        enabled = hasFunds && !loading,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Accept & Pay")
                        }
                    }
                }
            } else {
                Button(
                    onClick = { service.navigateTo(Screen.Overview) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("Back to Home")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AmountRow(label: String, value: String, bold: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = if (bold) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            style = if (bold) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
