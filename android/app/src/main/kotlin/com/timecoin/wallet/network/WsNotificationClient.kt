package com.timecoin.wallet.network

import android.util.Log
import com.timecoin.wallet.model.TransactionStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for real-time transaction notifications from masternodes.
 * Mirrors the desktop wallet's ws_client.rs — auto-reconnects with exponential backoff.
 *
 * Subscribe format (per address): {"method":"subscribe","params":{"address":"TIME0..."}}
 * Heartbeat: sends {"method":"ping","params":{}} every 25 seconds.
 * Protocol: tries wss:// first, falls back to ws://.
 */
class WsNotificationClient(
    private val wsUrl: String,
    private val addresses: List<String>,
    private val scope: CoroutineScope,
) {
    private val TAG = "WsNotification"
    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events

    private var webSocket: WebSocket? = null
    private var running = true
    private var connectJob: Job? = null
    private var pingJob: Job? = null

    /** Start connecting (call once). */
    fun start() {
        connectJob = scope.launch { connectLoop() }
    }

    fun stop() {
        running = false
        pingJob?.cancel()
        connectJob?.cancel()
        webSocket?.close(1000, "shutdown")
    }

    private suspend fun connectLoop() {
        var backoffSecs = 1L
        val maxBackoff = 60L

        while (running) {
            try {
                val connected = connect()
                if (connected) {
                    backoffSecs = 1 // reset on success
                    // Wait until disconnected
                    while (running && webSocket != null) {
                        delay(1000)
                    }
                }
            } catch (_: Exception) { }

            pingJob?.cancel()
            if (!running) break
            delay(backoffSecs * 1000)
            backoffSecs = (backoffSecs * 2).coerceAtMost(maxBackoff)
        }
    }

    private suspend fun connect(): Boolean {
        // Try wss:// first, then fall back to ws://
        val wssUrl = if (wsUrl.startsWith("ws://")) wsUrl.replaceFirst("ws://", "wss://") else wsUrl
        val wsPlainUrl = if (wsUrl.startsWith("wss://")) wsUrl.replaceFirst("wss://", "ws://") else wsUrl

        Log.d(TAG, "Attempting WS connection: trying wss first at $wssUrl")
        val wssResult = tryConnect(wssUrl)
        if (wssResult) return true

        Log.d(TAG, "wss:// failed, falling back to ws:// at $wsPlainUrl")
        return tryConnect(wsPlainUrl)
    }

    private suspend fun tryConnect(url: String): Boolean {
        // Reuse the trust-all OkHttpClient from MasternodeClient (self-signed masternode certs)
        val client = MasternodeClient.trustAllOkHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS) // WS needs unlimited read timeout
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        val latch = CompletableDeferred<Boolean>()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $url")
                this@WsNotificationClient.webSocket = webSocket
                _events.tryEmit(WsEvent.Connected(url))

                // Subscribe to each address individually (matches desktop format)
                for (addr in addresses) {
                    val subscribe = buildJsonObject {
                        put("method", "subscribe")
                        put("params", buildJsonObject {
                            put("address", addr)
                        })
                    }
                    webSocket.send(subscribe.toString())
                }
                Log.d(TAG, "Subscribed to ${addresses.size} addresses")

                // Start heartbeat ping every 25 seconds
                pingJob?.cancel()
                pingJob = scope.launch {
                    while (isActive) {
                        delay(25_000)
                        val ping = buildJsonObject {
                            put("method", "ping")
                            put("params", buildJsonObject { })
                        }
                        webSocket.send(ping.toString())
                    }
                }

                latch.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS message: $text")
                try {
                    val msg = Json.parseToJsonElement(text).jsonObject

                    // Check for capacity error
                    val error = msg["error"]?.jsonPrimitive?.contentOrNull
                    if (error == "capacity") {
                        Log.w(TAG, "Server at capacity")
                        _events.tryEmit(WsEvent.Disconnected("Server at capacity"))
                        webSocket.close(1000, "capacity")
                        return
                    }

                    val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: return
                    val data = msg["data"]?.jsonObject

                    when (type) {
                        "subscribed" -> {
                            val addr = data?.get("address")?.jsonPrimitive?.contentOrNull
                            Log.d(TAG, "Subscription confirmed for $addr")
                        }
                        "pong" -> { /* heartbeat response, ignore */ }
                        "tx_notification" -> {
                            if (data != null) {
                                val notif = TxNotification(
                                    txid = data["txid"]?.jsonPrimitive?.contentOrNull ?: "",
                                    address = data["address"]?.jsonPrimitive?.contentOrNull ?: "",
                                    amount = MasternodeClient.jsonToSatoshis(data["amount"]),
                                    outputIndex = data["output_index"]?.jsonPrimitive?.intOrNull ?: 0,
                                    timestamp = data["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                                )
                                _events.tryEmit(WsEvent.TransactionReceived(notif))
                            }
                        }
                        "utxo_finalized" -> {
                            if (data != null) {
                                _events.tryEmit(WsEvent.UtxoFinalized(
                                    txid = data["txid"]?.jsonPrimitive?.contentOrNull ?: "",
                                    outputIndex = data["output_index"]?.jsonPrimitive?.intOrNull ?: 0,
                                ))
                            }
                        }
                        "tx_rejected", "tx_declined" -> {
                            if (data != null) {
                                _events.tryEmit(WsEvent.TransactionRejected(
                                    txid = data["txid"]?.jsonPrimitive?.contentOrNull ?: "",
                                    reason = data["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                                ))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing WS message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure at $url: ${t.message}, response: ${response?.code}")
                this@WsNotificationClient.webSocket = null
                _events.tryEmit(WsEvent.Disconnected(t.message ?: "connection failed"))
                if (!latch.isCompleted) latch.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
                this@WsNotificationClient.webSocket = null
                _events.tryEmit(WsEvent.Disconnected(reason))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code reason=$reason")
                webSocket.close(code, reason)
            }
        })

        return latch.await()
    }
}

sealed class WsEvent {
    data class Connected(val url: String) : WsEvent()
    data class Disconnected(val reason: String) : WsEvent()
    data class TransactionReceived(val notification: TxNotification) : WsEvent()
    data class UtxoFinalized(val txid: String, val outputIndex: Int) : WsEvent()
    data class TransactionRejected(val txid: String, val reason: String) : WsEvent()
}

data class TxNotification(
    val txid: String,
    val address: String,
    val amount: Long,
    val outputIndex: Int,
    val timestamp: Long,
)
