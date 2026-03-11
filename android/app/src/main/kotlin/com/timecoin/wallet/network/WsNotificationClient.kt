package com.timecoin.wallet.network

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
 */
class WsNotificationClient(
    private val wsUrl: String,
    private val addresses: List<String>,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events

    private var webSocket: WebSocket? = null
    private var running = true
    private var connectJob: Job? = null

    /** Start connecting (call once). */
    fun start() {
        connectJob = scope.launch { connectLoop() }
    }

    fun stop() {
        running = false
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

            if (!running) break
            delay(backoffSecs * 1000)
            backoffSecs = (backoffSecs * 2).coerceAtMost(maxBackoff)
        }
    }

    private suspend fun connect(): Boolean {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()
        val latch = CompletableDeferred<Boolean>()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@WsNotificationClient.webSocket = webSocket
                _events.tryEmit(WsEvent.Connected(wsUrl))

                // Subscribe to our addresses
                val subscribe = buildJsonObject {
                    put("method", "subscribe")
                    put("params", buildJsonObject {
                        put("addresses", buildJsonArray { addresses.forEach { add(it) } })
                    })
                }
                webSocket.send(subscribe.toString())
                latch.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = Json.parseToJsonElement(text).jsonObject
                    val type = msg["type"]?.jsonPrimitive?.contentOrNull ?: return
                    val data = msg["data"]?.jsonObject

                    when (type) {
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
                        "tx_rejected" -> {
                            if (data != null) {
                                _events.tryEmit(WsEvent.TransactionRejected(
                                    txid = data["txid"]?.jsonPrimitive?.contentOrNull ?: "",
                                    reason = data["reason"]?.jsonPrimitive?.contentOrNull ?: "",
                                ))
                            }
                        }
                    }
                } catch (_: Exception) { }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                this@WsNotificationClient.webSocket = null
                _events.tryEmit(WsEvent.Disconnected(t.message ?: "connection failed"))
                latch.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                this@WsNotificationClient.webSocket = null
                _events.tryEmit(WsEvent.Disconnected(reason))
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
