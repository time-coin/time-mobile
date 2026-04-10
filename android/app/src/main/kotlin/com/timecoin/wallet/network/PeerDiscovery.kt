package com.timecoin.wallet.network

import android.util.Log
import com.timecoin.wallet.model.PeerInfo
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Peer discovery — mirrors the desktop wallet's peer_discovery.rs + service.rs.
 *
 * Discovery flow:
 *   1. Collect candidates: manual peers (time.conf) + API peers + cached peers
 *   2. Parallel probe all candidates: TCP ping, RPC health check, WS availability
 *   3. Consensus filter: discard peers >3 blocks behind the best height
 *   4. Sort: healthy → WS-capable → lowest ping
 *   5. Gossip: ask healthy peers for their known peers, probe new discoveries
 *   6. Re-sort and return ranked list
 *   7. Cache healthy peers for offline fallback
 */
object PeerDiscovery {

    private const val TAG = "PeerDiscovery"
    private const val MAINNET_URL = "https://time-coin.io/api/peers"
    private const val TESTNET_URL = "https://time-coin.io/api/testnet/peers"
    private const val MAINNET_RPC_PORT = 24001
    private const val TESTNET_RPC_PORT = 24101
    private const val TCP_TIMEOUT_MS = 1000
    private const val HEALTH_TIMEOUT_MS = 4000L
    private const val WS_TIMEOUT_MS = 3000L
    private const val MAX_BLOCK_LAG = 3
    private const val MAX_PEERS = 8

    // ── Public API ──

    /**
     * Discover and rank peers. Returns a sorted list of [PeerInfo] —
     * best peer first (healthy, WS-capable, lowest latency).
     */
    suspend fun discoverAndRank(
        isTestnet: Boolean,
        manualEndpoints: List<String> = emptyList(),
        credentials: Pair<String, String>? = null,
        cacheDir: File? = null,
    ): List<PeerInfo> = coroutineScope {
        val rpcPort = if (isTestnet) TESTNET_RPC_PORT else MAINNET_RPC_PORT

        // 1. Collect candidate endpoints
        val candidates = mutableSetOf<String>()
        candidates.addAll(manualEndpoints)
        try {
            val apiPeers = fetchFromApi(isTestnet)
            candidates.addAll(apiPeers)
            if (cacheDir != null) saveCache(isTestnet, apiPeers, cacheDir)
        } catch (e: Exception) {
            Log.w(TAG, "API discovery failed: ${e.message}")
            if (cacheDir != null) {
                loadCache(isTestnet, cacheDir)?.let { candidates.addAll(it) }
            }
        }
        Log.d(TAG, "Collected ${candidates.size} candidate peers")

        if (candidates.isEmpty()) return@coroutineScope emptyList()

        // 2. Parallel probe all candidates
        var peerInfos = probeAll(candidates.toList(), credentials, rpcPort)
        Log.d(TAG, "Probed ${peerInfos.size} peers, ${peerInfos.count { it.isHealthy }} healthy")

        // 3. Consensus filter
        peerInfos = consensusFilter(peerInfos)

        // 4. Sort: healthy → WS → lowest ping
        peerInfos = rankPeers(peerInfos)

        // 5. Gossip discovery — ask healthy peers for their known peers
        val healthyPeers = peerInfos.filter { it.isHealthy }.take(3)
        val knownEndpoints = peerInfos.map { it.endpoint }.toSet()
        val gossipEndpoints = mutableSetOf<String>()

        for (peer in healthyPeers) {
            try {
                val client = MasternodeClient(peer.endpoint, credentials)
                val peerAddresses = withTimeout(HEALTH_TIMEOUT_MS) {
                    client.getPeerAddresses()
                }
                client.close()
                for (addr in peerAddresses) {
                    // addr is "IP:P2P_PORT" — extract IP, build RPC endpoint
                    val ip = addr.substringBefore(':')
                    val ep = "https://$ip:$rpcPort"
                    if (ep !in knownEndpoints) gossipEndpoints.add(ep)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gossip from ${peer.endpoint} failed: ${e.message}")
            }
        }

        if (gossipEndpoints.isNotEmpty()) {
            Log.d(TAG, "Gossip discovered ${gossipEndpoints.size} new peers")
            val gossipInfos = probeAll(gossipEndpoints.toList(), credentials, rpcPort)
            val filteredGossip = consensusFilter(gossipInfos, peerInfos)

            // Merge: replace slowest peers if gossip peers are faster
            val merged = (peerInfos + filteredGossip).distinctBy { it.endpoint }
            peerInfos = rankPeers(merged)
        }

        // 6. Cap at MAX_PEERS
        peerInfos = peerInfos.take(MAX_PEERS)

        // 7. Cache healthy peers
        if (cacheDir != null) {
            val healthyEndpoints = peerInfos.filter { it.isHealthy }.map { it.endpoint }
            if (healthyEndpoints.isNotEmpty()) {
                saveCache(isTestnet, healthyEndpoints, cacheDir)
            }
        }

        Log.d(TAG, "Final peer list (${peerInfos.size}): " +
            peerInfos.joinToString { "${it.endpoint} ping=${it.pingMs}ms h=${it.blockHeight}" })

        peerInfos
    }

    /**
     * Simple fetch for backward compatibility — returns endpoint strings only.
     */
    suspend fun fetchPeers(isTestnet: Boolean, cacheDir: File? = null): List<String> {
        return try {
            val peers = fetchFromApi(isTestnet)
            if (cacheDir != null) saveCache(isTestnet, peers, cacheDir)
            peers
        } catch (e: Exception) {
            if (cacheDir != null) loadCache(isTestnet, cacheDir) ?: emptyList()
            else emptyList()
        }
    }

    // ── Probing ──

    /**
     * Probe all endpoints in parallel. Each probe does:
     *   1. TCP connect to measure ping latency
     *   2. RPC health check (HTTPS → HTTP fallback)
     *   3. WebSocket availability check
     */
    private suspend fun probeAll(
        endpoints: List<String>,
        credentials: Pair<String, String>?,
        rpcPort: Int,
    ): List<PeerInfo> = coroutineScope {
        endpoints.map { endpoint ->
            async(Dispatchers.IO) { probePeer(endpoint, credentials, rpcPort) }
        }.awaitAll()
    }

    private suspend fun probePeer(
        endpoint: String,
        credentials: Pair<String, String>?,
        rpcPort: Int,
    ): PeerInfo {
        // Extract host/port for TCP ping
        val url = if (!endpoint.startsWith("http")) "https://$endpoint" else endpoint
        val host = url.removePrefix("https://").removePrefix("http://").substringBefore(':')
        val port = url.substringAfterLast(':').toIntOrNull() ?: rpcPort

        // 1. TCP ping
        val pingMs = measureTcpPing(host, port)

        // 2. RPC health check (try HTTPS first, then HTTP)
        var blockHeight: Long? = null
        var version: String? = null
        var isHealthy = false
        var isSyncing = false
        var workingUrl = url

        for (scheme in listOf("https", "http")) {
            val tryUrl = "$scheme://$host:$port"
            try {
                val client = MasternodeClient(tryUrl, credentials)
                val health = withTimeout(HEALTH_TIMEOUT_MS) { client.healthCheck() }
                client.close()
                blockHeight = health.blockHeight
                version = health.version
                isHealthy = true
                isSyncing = health.isSyncing
                workingUrl = tryUrl
                break
            } catch (e: Exception) {
                Log.v(TAG, "Health check $tryUrl failed: ${e.message}")
            }
        }

        // 3. WebSocket probe (RPC port + 1)
        val wsAvailable = if (isHealthy) {
            checkWsAvailable(host, port + 1)
        } else false

        return PeerInfo(
            endpoint = workingUrl,
            isActive = false,
            isHealthy = isHealthy,
            wsAvailable = wsAvailable,
            pingMs = pingMs,
            blockHeight = blockHeight,
            version = version,
            isSyncing = isSyncing,
        )
    }

    /** TCP connect to measure round-trip latency in milliseconds. */
    private fun measureTcpPing(host: String, port: Int): Long? {
        return try {
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            null
        }
    }

    /** Check if WebSocket port is accepting connections. */
    private suspend fun checkWsAvailable(host: String, wsPort: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(WS_TIMEOUT_MS) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(host, wsPort), TCP_TIMEOUT_MS)
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    // ── Filtering & Sorting ──

    /**
     * Consensus filter — discard peers whose block height is more than
     * [MAX_BLOCK_LAG] blocks behind the best known height.
     */
    private fun consensusFilter(
        peers: List<PeerInfo>,
        existingPeers: List<PeerInfo> = emptyList(),
    ): List<PeerInfo> {
        val allHeights = (peers + existingPeers).mapNotNull { it.blockHeight }
        if (allHeights.isEmpty()) return peers
        val bestHeight = allHeights.max()

        return peers.filter { p ->
            if (p.blockHeight == null) true // keep unhealthy peers (sorted last)
            else (bestHeight - p.blockHeight) <= MAX_BLOCK_LAG
        }.also { filtered ->
            val dropped = peers.size - filtered.size
            if (dropped > 0) Log.d(TAG, "Consensus filter dropped $dropped lagging peers (best=$bestHeight)")
        }
    }

    /** Sort peers: healthy first → not syncing → WS-capable → lowest ping. */
    private fun rankPeers(peers: List<PeerInfo>): List<PeerInfo> {
        return peers.sortedWith(
            compareByDescending<PeerInfo> { it.isHealthy }
                .thenBy { it.isSyncing }  // fully-synced peers before IBD peers
                .thenByDescending { it.wsAvailable }
                .thenBy { it.pingMs ?: Long.MAX_VALUE }
        )
    }

    // ── API & Cache ──

    private suspend fun fetchFromApi(isTestnet: Boolean): List<String> {
        val url = if (isTestnet) TESTNET_URL else MAINNET_URL
        val port = if (isTestnet) TESTNET_RPC_PORT else MAINNET_RPC_PORT

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
