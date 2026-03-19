package com.timecoin.wallet.network

import android.util.Log
import com.timecoin.wallet.crypto.NetworkType
import com.timecoin.wallet.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Masternode JSON-RPC 2.0 client — mirrors the desktop wallet's
 * masternode_client.rs. Communicates with masternodes over HTTPS.
 * Masternodes use self-signed TLS certificates by default.
 *
 * Ports: 24001 (mainnet), 24101 (testnet)
 */
class MasternodeClient(
    endpoint: String,
    private val credentials: Pair<String, String>? = null,
) {
    val rpcEndpoint: String = if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
        endpoint
    } else {
        "https://$endpoint"
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        engine {
            preconfigured = trustAllOkHttpClient
        }
    }

    private val requestId = AtomicLong(1)

    // ── RPC methods ──

    /** Get balance for a single address. */
    suspend fun getBalance(address: String): Balance {
        val result = rpcCall("getbalance", buildJsonArray { add(address) })
        val confirmed = jsonToSatoshis(result.jsonObject["available"])
        val total = jsonToSatoshis(result.jsonObject["balance"])
        return Balance(confirmed = confirmed, pending = total - confirmed, total = total)
    }

    /** Get combined balance across multiple addresses (batched to avoid oversized responses). */
    suspend fun getBalances(addresses: List<String>): Balance {
        if (addresses.size <= BATCH_SIZE) {
            val result = rpcCall("getbalances", buildJsonArray { add(buildJsonArray { addresses.forEach { add(it) } }) })
            val confirmed = jsonToSatoshis(result.jsonObject["available"])
            val total = jsonToSatoshis(result.jsonObject["balance"])
            return Balance(confirmed = confirmed, pending = total - confirmed, total = total)
        }
        // Batch: sum balances across chunks
        var confirmed = 0L
        var total = 0L
        for (chunk in addresses.chunked(BATCH_SIZE)) {
            val result = rpcCall("getbalances", buildJsonArray { add(buildJsonArray { chunk.forEach { add(it) } }) })
            confirmed += jsonToSatoshis(result.jsonObject["available"])
            total += jsonToSatoshis(result.jsonObject["balance"])
        }
        return Balance(confirmed = confirmed, pending = total - confirmed, total = total)
    }

    /** Get transaction history for a single address. */
    suspend fun getTransactions(address: String, limit: Int = 50): List<TransactionRecord> {
        val result = rpcCall("listtransactions", buildJsonArray { add(address); add(limit) })
        return parseTransactionList(result)
    }

    /** Get transaction history across multiple addresses (batched). */
    suspend fun getTransactionsMulti(addresses: List<String>, limit: Int = 50): List<TransactionRecord> {
        if (addresses.size <= BATCH_SIZE) {
            val result = rpcCall("listtransactionsmulti", buildJsonArray {
                add(buildJsonArray { addresses.forEach { add(it) } }); add(limit)
            })
            return parseTransactionList(result)
        }
        // Batch: collect and deduplicate by uniqueKey
        val all = mutableListOf<TransactionRecord>()
        for (chunk in addresses.chunked(BATCH_SIZE)) {
            val result = rpcCall("listtransactionsmulti", buildJsonArray {
                add(buildJsonArray { chunk.forEach { add(it) } }); add(limit)
            })
            all.addAll(parseTransactionList(result))
        }
        val seen = mutableSetOf<String>()
        return all.filter { seen.add(it.uniqueKey) }
    }

    /** Get UTXOs for addresses (batched). */
    suspend fun getUtxos(addresses: List<String>): List<Utxo> {
        val chunks = if (addresses.size <= BATCH_SIZE) listOf(addresses) else addresses.chunked(BATCH_SIZE)
        val all = mutableListOf<Utxo>()
        for (chunk in chunks) {
            val result = rpcCall("listunspentmulti", buildJsonArray {
                add(buildJsonArray { chunk.forEach { add(it) } })
            })
            all.addAll(result.jsonArray.mapNotNull { u ->
                val obj = u.jsonObject
                val txid = obj["txid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val vout = obj["vout"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val amount = jsonToSatoshis(obj["amount"])
                val addr = obj["address"]?.jsonPrimitive?.contentOrNull ?: ""
                val confirmations = obj["confirmations"]?.jsonPrimitive?.intOrNull ?: 0
                val spendable = obj["spendable"]?.jsonPrimitive?.booleanOrNull ?: true
                Utxo(txid, vout, amount, addr, confirmations, spendable)
            })
        }
        return all
    }

    /** Broadcast a signed transaction (hex-encoded). */
    suspend fun broadcastTransaction(txHex: String): String {
        val result = rpcCall("sendrawtransaction", buildJsonArray { add(txHex) })
        return result.jsonPrimitive.contentOrNull ?: result.toString().trim('"')
    }

    /** Validate an address on the masternode. */
    suspend fun validateAddress(address: String): Boolean {
        val result = rpcCall("validateaddress", buildJsonArray { add(address) })
        return result.jsonObject["isvalid"]?.jsonPrimitive?.booleanOrNull ?: false
    }

    /** Get blockchain info (health check). */
    suspend fun healthCheck(): HealthStatus {
        val result = rpcCall("getblockchaininfo", buildJsonArray {})
        val height = result.jsonObject["blocks"]?.jsonPrimitive?.longOrNull
            ?: result.jsonObject["height"]?.jsonPrimitive?.longOrNull ?: 0
        val network = result.jsonObject["chain"]?.jsonPrimitive?.contentOrNull ?: "unknown"

        var peerCount = 0
        var version = ""
        try {
            val ni = rpcCall("getnetworkinfo", buildJsonArray {})
            peerCount = ni.jsonObject["connections"]?.jsonPrimitive?.intOrNull ?: 0
            version = ni.jsonObject["subversion"]?.jsonPrimitive?.contentOrNull?.trim('/') ?: ""
        } catch (_: Exception) { }

        return HealthStatus(
            status = "healthy",
            version = "$network ($version)",
            blockHeight = height,
            peerCount = peerCount,
        )
    }

    /** Get current block height. */
    suspend fun getBlockHeight(): Long {
        val result = rpcCall("getblockcount", buildJsonArray {})
        return result.jsonPrimitive.longOrNull ?: 0
    }

    /** Query instant finality status. */
    suspend fun getTransactionFinality(txid: String): FinalityStatus {
        val result = rpcCall("gettransactionfinality", buildJsonArray { add(txid) })
        return FinalityStatus(
            txid = txid,
            finalized = result.jsonObject["finalized"]?.jsonPrimitive?.booleanOrNull ?: false,
            confirmations = result.jsonObject["confirmations"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    /**
     * Get peer list from the connected masternode (gossip discovery).
     * Returns a list of "IP:P2P_PORT" strings from the masternode's peer table.
     */
    suspend fun getPeerAddresses(): List<String> {
        val result = rpcCall("getpeerinfo", buildJsonArray {})
        return result.jsonArray.mapNotNull { peer ->
            peer.jsonObject["addr"]?.jsonPrimitive?.contentOrNull
        }
    }

    /**
     * Get full transaction details including per-output breakdown.
     * Used to expand "consolidate" (self-send) entries into individual outputs
     * so processTransactions can create proper send + receive + fee entries.
     */
    suspend fun getTransactionDetail(txid: String): Pair<Long, List<TxOutputInfo>> {
        val result = rpcCall("gettransaction", buildJsonArray { add(txid) })
        val obj = result.jsonObject
        val fee = jsonToSatoshisAbs(obj["fee"])
        val outputs = obj["vout"]?.jsonArray?.mapNotNull { vout ->
            val vo = vout.jsonObject
            val value = jsonToSatoshisAbs(vo["value"])
            val n = vo["n"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val addr = vo["scriptPubKey"]?.jsonObject?.get("address")
                ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TxOutputInfo(value = value, index = n, address = addr)
        } ?: emptyList()
        return Pair(fee, outputs)
    }

    /**
     * Send a payment request to another wallet via masternode relay.
     * The masternode forwards it as a `payment_request` WS event to the payer's subscribed address.
     */
    suspend fun sendPaymentRequest(
        requestId: String,
        requesterAddress: String,
        payerAddress: String,
        amountSats: Long,
        memo: String,
        requesterName: String,
    ): Boolean = try {
        rpcCall("sendpaymentrequest", buildJsonArray {
            add(buildJsonObject {
                put("id", requestId)
                put("requester_address", requesterAddress)
                put("payer_address", payerAddress)
                put("amount", amountSats)
                put("memo", memo)
                put("requester_name", requesterName)
            })
        })
        true
    } catch (e: Exception) {
        Log.w("MasternodeClient", "sendPaymentRequest failed: ${e.message}")
        false
    }

    /** Notify the masternode that the payer has viewed a payment request. Requester is notified via `payment_request_viewed`. */
    suspend fun markPaymentRequestViewed(requestId: String, payerAddress: String): Boolean = try {
        rpcCall("markpaymentrequestviewed", buildJsonArray {
            add(buildJsonObject {
                put("id", requestId)
                put("payer_address", payerAddress)
            })
        })
        true
    } catch (e: Exception) {
        Log.w("MasternodeClient", "markPaymentRequestViewed failed: ${e.message}")
        false
    }

    /** Cancel a previously sent payment request. Masternode notifies payer via `payment_request_cancelled`. */
    suspend fun cancelPaymentRequest(requestId: String, requesterAddress: String): Boolean = try {
        rpcCall("cancelpaymentrequest", buildJsonArray {
            add(buildJsonObject {
                put("id", requestId)
                put("requester_address", requesterAddress)
            })
        })
        true
    } catch (e: Exception) {
        Log.w("MasternodeClient", "cancelPaymentRequest failed: ${e.message}")
        false
    }

    /** Respond to a payment request. Masternode notifies requester via `payment_request_response`. */
    suspend fun respondToPaymentRequest(
        requestId: String,
        payerAddress: String,
        accepted: Boolean,
    ): Boolean = try {
        rpcCall("respondpaymentrequest", buildJsonArray {
            add(buildJsonObject {
                put("id", requestId)
                put("payer_address", payerAddress)
                put("accepted", accepted)
            })
        })
        true
    } catch (e: Exception) {
        Log.w("MasternodeClient", "respondToPaymentRequest failed: ${e.message}")
        false
    }

    fun close() { client.close() }

    // ── Internal ──

    private suspend fun rpcCall(method: String, params: JsonElement): JsonElement {
        val id = requestId.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id.toString())
            put("method", method)
            put("params", params)
        }

        val response: JsonObject = client.post(rpcEndpoint) {
            contentType(ContentType.Application.Json)
            if (credentials != null) {
                basicAuth(credentials.first, credentials.second)
            }
            setBody(request)
        }.body()

        val error = response["error"]
        if (error != null && error !is JsonNull) {
            val code = error.jsonObject["code"]?.jsonPrimitive?.longOrNull ?: -1
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            throw MasternodeException("RPC error $code: $message")
        }

        return response["result"] ?: throw MasternodeException("No result in JSON-RPC response")
    }

    private fun parseTransactionList(result: JsonElement): List<TransactionRecord> {
        val txArray = if (result is JsonObject && result.containsKey("transactions")) {
            result["transactions"]?.jsonArray ?: return emptyList()
        } else {
            result.jsonArray
        }
        return txArray.mapNotNull { tx ->
            val obj = tx.jsonObject
            val txid = obj["txid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val category = obj["category"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val rawAmount = jsonToSatoshisAbs(obj["amount"])
            val fee = jsonToSatoshisAbs(obj["fee"])
            val address = obj["address"]?.jsonPrimitive?.contentOrNull ?: ""
            val vout = obj["vout"]?.jsonPrimitive?.intOrNull ?: 0
            val timestamp = obj["time"]?.jsonPrimitive?.longOrNull
                ?: obj["blocktime"]?.jsonPrimitive?.longOrNull
                ?: obj["timestamp"]?.jsonPrimitive?.longOrNull ?: 0
            val inBlock = obj["blockhash"]?.jsonPrimitive?.contentOrNull != null
            val blockHash = obj["blockhash"]?.jsonPrimitive?.contentOrNull ?: ""
            val finalized = obj["finalized"]?.jsonPrimitive?.booleanOrNull ?: false
            val blockHeight = obj["blockheight"]?.jsonPrimitive?.longOrNull ?: 0
            val confirmations = obj["confirmations"]?.jsonPrimitive?.longOrNull ?: 0
            val txStatus = if (inBlock || finalized) TransactionStatus.Approved else TransactionStatus.Pending
            val rpcMemo = obj["memo"]?.jsonPrimitive?.contentOrNull ?: ""

            val isSend = category == "send"
            val isConsolidate = category == "consolidate"
            val isBlockReward = category == "generate"

            Log.d("MasternodeClient", "parseTx: txid=${txid.take(12)}.. cat=$category " +
                "rawAmt=$rawAmount fee=$fee addr=${address.take(16)}.. vout=$vout")

            // "consolidate" = self-send (all outputs go back to own addresses).
            // Pass through as a marker so refreshTransactions can expand it
            // via gettransaction into individual per-output entries.
            if (isConsolidate) {
                return@mapNotNull TransactionRecord(
                    txid = txid,
                    vout = vout,
                    isSend = true,
                    isConsolidate = true,
                    address = address,
                    amount = rawAmount,
                    fee = fee,
                    timestamp = timestamp,
                    status = txStatus,
                    blockHash = blockHash,
                    blockHeight = blockHeight,
                    confirmations = confirmations,
                )
            }

            // RPC includes fee in the send amount — subtract it so we
            // display only the actual transferred value. Matches desktop
            // wallet behavior (masternode_client.rs:311-312).
            val displayAmount = if (isSend && fee > 0) {
                maxOf(rawAmount - fee, 0)
            } else {
                rawAmount
            }

            // Skip zero-amount received entries — these are staking inputs with
            // no corresponding payout. Block rewards (generate) are kept even
            // at zero so they appear in history.
            if (displayAmount == 0L && !isSend && !isBlockReward) return@mapNotNull null

            TransactionRecord(
                txid = txid,
                vout = vout,
                isSend = isSend,
                address = address,
                amount = displayAmount,
                fee = fee,
                timestamp = timestamp,
                status = txStatus,
                blockHash = blockHash,
                blockHeight = blockHeight,
                confirmations = confirmations,
                memo = if (isBlockReward) "Block Reward" else rpcMemo,
            )
        }
    }

    companion object {
        /** Max addresses per RPC call to avoid oversized responses. */
        private const val BATCH_SIZE = 20

        /** Trust manager that accepts all certificates (for self-signed masternode certs). */
        private val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        private val trustAllSslContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }

        /** Pre-configured OkHttpClient that trusts all certs and forces HTTP/1.1. */
        val trustAllOkHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(trustAllSslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()

        /** Parse a JSON value to satoshis (1 TIME = 100_000_000 satoshis). */
        fun jsonToSatoshis(value: JsonElement?): Long {
            if (value == null || value is JsonNull) return 0
            val s = when (value) {
                is JsonPrimitive -> value.content
                else -> return 0
            }.trim().trimStart('-')
            // If original was negative, return 0
            if (value is JsonPrimitive && value.content.trim().startsWith('-')) return 0
            return parseTimeStringToSatoshis(s)
        }

        private fun jsonToSatoshisAbs(value: JsonElement?): Long {
            if (value == null || value is JsonNull) return 0
            val s = when (value) {
                is JsonPrimitive -> value.content
                else -> return 0
            }.trim().trimStart('-')
            return parseTimeStringToSatoshis(s)
        }

        private fun parseTimeStringToSatoshis(s: String): Long {
            if (s.contains('e', ignoreCase = true)) {
                return try { (s.toDouble() * 100_000_000).toLong() } catch (_: Exception) { 0 }
            }
            val parts = s.split('.', limit = 2)
            val whole = parts[0].toLongOrNull() ?: 0
            val frac = if (parts.size > 1) {
                parts[1].take(8).padEnd(8, '0').toLongOrNull() ?: 0
            } else 0
            return whole * 100_000_000 + frac
        }
    }
}

class MasternodeException(message: String) : Exception(message)
