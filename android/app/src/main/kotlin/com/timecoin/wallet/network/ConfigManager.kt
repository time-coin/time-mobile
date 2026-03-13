package com.timecoin.wallet.network

import android.util.Log
import java.io.File

/**
 * Manages the `time.conf` configuration file — Bitcoin-style key=value format
 * matching the desktop wallet's config_new.rs.
 *
 * Supported keys:
 *   addnode=IP:PORT   (repeatable — manual masternode peers)
 *   rpcuser=...       (RPC username)
 *   rpcpassword=...   (RPC password)
 *   testnet=0|1       (network selection)
 */
object ConfigManager {
    private const val TAG = "ConfigManager"
    private const val CONFIG_FILENAME = "time.conf"
    private const val MAINNET_PORT = 24001
    private const val TESTNET_PORT = 24101

    data class Config(
        val peers: List<String> = emptyList(),
        val rpcUser: String? = null,
        val rpcPassword: String? = null,
        val testnet: Boolean = false,
    )

    /** Load configuration from time.conf in the given directory. */
    fun load(dir: File): Config {
        val file = File(dir, CONFIG_FILENAME)
        if (!file.exists()) return Config()
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse $CONFIG_FILENAME: ${e.message}")
            Config()
        }
    }

    /** Save configuration to time.conf. */
    fun save(dir: File, config: Config) {
        val file = File(dir, CONFIG_FILENAME)
        file.writeText(serialize(config))
        Log.d(TAG, "Saved $CONFIG_FILENAME with ${config.peers.size} peers")
    }

    /** Get the raw text content of time.conf (for editing in the UI). */
    fun readRaw(dir: File): String {
        val file = File(dir, CONFIG_FILENAME)
        return if (file.exists()) file.readText() else defaultConfig()
    }

    /** Write raw text content to time.conf. */
    fun writeRaw(dir: File, content: String) {
        File(dir, CONFIG_FILENAME).writeText(content)
    }

    /** Build endpoint URLs from the addnode entries, adding scheme and default port. */
    fun manualEndpoints(config: Config): List<String> {
        val port = if (config.testnet) TESTNET_PORT else MAINNET_PORT
        return config.peers.map { peer ->
            when {
                peer.startsWith("http://") || peer.startsWith("https://") -> peer
                peer.contains(':') -> "http://$peer"
                else -> "http://$peer:$port"
            }
        }
    }

    /** RPC credentials from config, or null if not set. */
    fun rpcCredentials(config: Config): Pair<String, String>? {
        val user = config.rpcUser ?: return null
        val pass = config.rpcPassword ?: return null
        return user to pass
    }

    // ── Parsing ──

    internal fun parse(contents: String): Config {
        val peers = mutableListOf<String>()
        var rpcUser: String? = null
        var rpcPassword: String? = null
        var testnet = false

        for (raw in contents.lines()) {
            // Strip comments
            val line = raw.indexOf('#').let { if (it >= 0) raw.substring(0, it) else raw }.trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()

            when (key) {
                "addnode" -> if (value.isNotEmpty()) peers.add(value)
                "rpcuser" -> if (value.isNotEmpty()) rpcUser = value
                "rpcpassword" -> if (value.isNotEmpty()) rpcPassword = value
                "testnet" -> testnet = value == "1"
            }
        }
        return Config(peers, rpcUser, rpcPassword, testnet)
    }

    internal fun serialize(config: Config): String = buildString {
        appendLine("# TIME Coin Wallet Configuration")
        appendLine("# Edit this file to add masternode peers manually.")
        appendLine()
        appendLine("testnet=${if (config.testnet) "1" else "0"}")
        appendLine()
        appendLine("# Masternode peers (one per line)")
        if (config.peers.isEmpty()) {
            appendLine("# addnode=64.91.241.10:24001")
        } else {
            for (peer in config.peers) {
                appendLine("addnode=$peer")
            }
        }
        if (config.rpcUser != null) {
            appendLine()
            appendLine("rpcuser=${config.rpcUser}")
        }
        if (config.rpcPassword != null) {
            appendLine("rpcpassword=${config.rpcPassword}")
        }
    }

    private fun defaultConfig(): String = serialize(Config())
}
