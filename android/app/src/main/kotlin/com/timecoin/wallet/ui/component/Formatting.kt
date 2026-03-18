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
    if (decimalPlaces >= 8) {
        val whole = satoshis / 100_000_000
        val frac = satoshis % 100_000_000
        val fracStr = "%08d".format(frac)
        val trimmed = fracStr.trimEnd('0').ifEmpty { "0" }
        val wholeStr = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
        return "$wholeStr.${trimmed.chunked(3).joinToString(" ")}"
    }

    // Round to the specified decimal places using integer arithmetic
    val roundUnit = POW10[8 - decimalPlaces]
    val rounded = (satoshis + roundUnit / 2) / roundUnit * roundUnit
    val whole = rounded / 100_000_000
    val frac = rounded % 100_000_000
    val fracStr = "%08d".format(frac).take(decimalPlaces)
    val wholeStr = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "$wholeStr.${fracStr.chunked(3).joinToString(" ")}"
}

private val POW10 = longArrayOf(
    1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000
)
