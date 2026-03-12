package com.timecoin.wallet.ui.component

import java.text.SimpleDateFormat
import java.util.*

fun formatTime(epochSeconds: Long): String {
    if (epochSeconds == 0L) return "Pending"
    val date = Date(epochSeconds * 1000)
    val now = System.currentTimeMillis()
    val diff = now - date.time

    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> "${diff / 86_400_000}d ago"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
    }
}

fun formatSatoshis(satoshis: Long, decimalPlaces: Int = 8): String {
    val whole = satoshis / 100_000_000
    val frac = satoshis % 100_000_000
    val fracStr = "%08d".format(frac)
    val trimmed = if (decimalPlaces >= 8) {
        fracStr.trimEnd('0').ifEmpty { "0" }
    } else {
        fracStr.take(decimalPlaces).ifEmpty { "0" }
    }
    val wholeStr = whole.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$wholeStr.$trimmed"
}
