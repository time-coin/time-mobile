package com.timecoin.wallet.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import java.io.File

/**
 * Peer discovery — fetches masternode endpoints from the TIME Coin website API.
 * Mirrors the desktop wallet's peer_discovery.rs.
 *
 * Discovery order:
 *   1. Manual peers from config
 *   2. API peers from time-coin.io
 *   3. Cached peers from local file (fallback)
 */
object PeerDiscovery {

    private const val MAINNET_URL = "https://time-coin.io/api/peers"
    private const val TESTNET_URL = "https://time-coin.io/api/testnet/peers"
    private const val MAINNET_PORT = 24001
    private const val TESTNET_PORT = 24101

    /**
     * Fetch peer endpoints. Returns list of "https://{ip}:{port}" strings.
     */
    suspend fun fetchPeers(isTestnet: Boolean, cacheDir: File? = null): List<String> {
        return try {
            val peers = fetchFromApi(isTestnet)
            if (cacheDir != null) saveCache(isTestnet, peers, cacheDir)
            peers
        } catch (e: Exception) {
            // Fallback to cached peers
            if (cacheDir != null) loadCache(isTestnet, cacheDir) ?: emptyList()
            else emptyList()
        }
    }

    private suspend fun fetchFromApi(isTestnet: Boolean): List<String> {
        val url = if (isTestnet) TESTNET_URL else MAINNET_URL
        val port = if (isTestnet) TESTNET_PORT else MAINNET_PORT

        val client = HttpClient(OkHttp)
        try {
            val response: String = client.get(url).body()
            val ips = Json.parseToJsonElement(response).jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }

            require(ips.isNotEmpty()) { "No peers returned from API" }

            return ips.map { ip -> "https://$ip:$port" }
        } finally {
            client.close()
        }
    }

    private fun saveCache(isTestnet: Boolean, peers: List<String>, cacheDir: File) {
        try {
            val file = File(cacheDir, cacheFilename(isTestnet))
            file.writeText(Json.encodeToString(JsonArray.serializer(), buildJsonArray {
                peers.forEach { add(it) }
            }))
        } catch (_: Exception) { }
    }

    private fun loadCache(isTestnet: Boolean, cacheDir: File): List<String>? {
        return try {
            val file = File(cacheDir, cacheFilename(isTestnet))
            if (!file.exists()) return null
            Json.parseToJsonElement(file.readText()).jsonArray
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (_: Exception) { null }
    }

    private fun cacheFilename(isTestnet: Boolean) =
        if (isTestnet) "peers-testnet.json" else "peers-mainnet.json"
}
