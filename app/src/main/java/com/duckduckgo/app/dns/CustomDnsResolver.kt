package com.duckduckgo.app.dns

import android.content.SharedPreferences
import android.os.Build
import androidx.core.net.toUri
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsParam
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.safegaze.enums.PrivateDnsLevel
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.KAHF_GUARD_BLOCKED_IP
import com.duckduckgo.common.utils.KAHF_GUARD_BLOCKED_URL
import com.duckduckgo.common.utils.KAHF_GUARD_BLOCKED_WITH_DOT
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

class CustomDnsResolver(
    private val dispatcher: DispatcherProvider,
    private val analytics: AnalyticsService,
    sharedPreferences: SharedPreferences
) : Dns {
    private var privateDns: PrivateDnsLevel
    private lateinit var dnsSocket: SSLSocket
    private var responseTimeStat = Pair(0L, 0)
    private var totalResolutionTimeStat = Pair(0L, 0)
    private var cacheMissOccurred = false

    companion object {
        private const val MAX_RETRY = 2
        private val cache = ConcurrentHashMap<String, CachedDnsResponse>()
    }

    init {
        privateDns = PrivateDnsLevel.getCurrentLevel(sharedPreferences)
    }

    private fun setupDotSocket(): SSLSocket {
        val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = socketFactory.createSocket(privateDns.dnsServerIps.random(), 853) as SSLSocket
        sslSocket.keepAlive = true
        sslSocket.soTimeout = 2000
        sslSocket.tcpNoDelay = true

        // Set the TLS host header
        sslSocket.sslParameters = sslSocket.sslParameters.apply {
            serverNames = listOf(javax.net.ssl.SNIHostName(privateDns.url))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                applicationProtocols = arrayOf("http/1.1")
            } else {
                try {
                    val method = this::class.java.getMethod("setApplicationProtocols", Array<String>::class.java)
                    method.invoke(this, arrayOf("http/1.1"))
                } catch (e: Exception) {
                    Timber.e("tpLog Error setting application protocols: ${e.message}")
                }
            }
        }

        sslSocket.startHandshake()

        return sslSocket
    }

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            runBlocking {
                val resolvedIp = resolveDomain(hostname.toUri())
                resolvedIp?.let {
                    listOf(InetAddress.getByName(it.first))
                } ?: emptyList()
            }
        } catch (e: Exception) {
            Timber.e("tpLog Lookup error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun checkCacheAndResolve(host: String): Message? {
        return cache[host]?.let { cachedResponse ->
            if (!cachedResponse.isExpired()) {
                Timber.d("tpLog Cache hit for $host")
                cachedResponse.message
            } else {
                Timber.d("tpLog Cache expired for $host")
                cache.remove(host)
                cacheMissOccurred = true

                try {
                    val queryMessage = Message.newQuery(Record.newRecord(Name.fromString(host), Type.A, DClass.IN))
                    val responseMessage = sendDoTQuery(queryMessage)
                    responseMessage
                } catch (e: Exception) {
                    null
                }
            }
        } ?: run {
            cacheMissOccurred = true
            try {
                val queryMessage = Message.newQuery(Record.newRecord(Name.fromString(host), Type.A, DClass.IN))
                val responseMessage = sendDoTQuery(queryMessage)
                responseMessage
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun resolveDomain(domain: android.net.Uri, attempt: Int = 0): Pair<String, String>? {
        if (attempt > 2) return null

        // Reset cache miss flag and start timing only on initial call
        if (attempt == 0) {
            cacheMissOccurred = false
        }

        // Measure total resolution time for initial calls only
        val startTime = if (attempt == 0) System.currentTimeMillis() else 0

        val result = withContext(dispatcher.io()) {
            val host = (domain.host ?: domain.toString()).removeSuffix(".") + "."

            try {
                val responseMessage = checkCacheAndResolve(host)
                val answers = responseMessage?.getSection(Section.ANSWER)

                val aRecord = answers?.find { it.type == Type.A }
                if (aRecord != null) {
                    cache[host] = CachedDnsResponse(responseMessage, System.currentTimeMillis() + 5 * 60 * 1000)
                    val resolvedIp = Pair(aRecord.rdataToString(), aRecord.name.toString(true))
                    Timber.d("tpLog Resolved IP: ${resolvedIp.first} ${resolvedIp.second}")
                    return@withContext resolvedIp
                }

                val cnameRecord = answers?.find { it.type == Type.CNAME }
                if (cnameRecord != null) {
                    if (cnameRecord.rdataToString() == KAHF_GUARD_BLOCKED_WITH_DOT) {
                        Timber.d("tpLog CNAME: $KAHF_GUARD_BLOCKED_URL")
                        return@withContext Pair(KAHF_GUARD_BLOCKED_URL, KAHF_GUARD_BLOCKED_IP)
                    }

                    // Make recursive call with incremented attempt counter
                    val cnameResponseMessage = resolveDomain(cnameRecord.rdataToString().toUri(), attempt + 1)
                    cnameResponseMessage?.let { pair ->
                        cache[host] = CachedDnsResponse(responseMessage, System.currentTimeMillis() + 5 * 60 * 1000)
                        Timber.d("tpLog Resolved CNAME: ${pair.first} ${pair.second}")
                        return@withContext pair
                    }
                }

                return@withContext null
            } catch (e: Exception) {
                Timber.e("tpLog Error resolving domain: ${e.message}")
                null
            }
        }

        // Calculate and log total time for initial calls only when all recursion completes
        if (attempt == 0) {
            val totalTime = System.currentTimeMillis() - startTime
            if (cacheMissOccurred) {
                logTotalResolutionTime(totalTime)
            }
        }

        return result
    }

    private suspend fun sendDoTQuery(query: Message, retry: Int = 0): Message? {
        if (retry > MAX_RETRY) {
            return null
        }

        val queryBytes = query.toWire()

        return withContext(dispatcher.io()) {
            try {
                withTimeout(1500) {
                    dnsSocket = setupDotSocket()
                }
            } catch (e: Exception) {
                Timber.e("tpLog Error getting socket: ${e.message}")
                return@withContext null
            }

            try {
                var dotResponse: Message
                val queryDurationMs = measureTimeMillis {
                    // Write length-prefixed DNS query without closing the stream
                    DataOutputStream(dnsSocket.outputStream).let {
                        it.writeShort(queryBytes.size)
                        it.write(queryBytes)
                        it.flush()
                    }

                    DataInputStream(dnsSocket.inputStream).let {
                        val responseBytes = ByteArray(it.readUnsignedShort())
                        it.readFully(responseBytes)
                        dotResponse = Message(responseBytes)
                    }
                }

                Timber.d("tpLog DoT query time: $queryDurationMs ms")
                logResponseTime(queryDurationMs)
                dnsSocket.close()

                dotResponse
            } catch (e: Exception) {
                Timber.e("tpLog DoT query error: ${e.message ?: e.toString()}")
                sendDoTQuery(query, retry + 1)
            }
        }
    }

    private suspend fun closeConnection() {
        try {
            withContext(dispatcher.io()) {
                dnsSocket.close()
                Timber.d("tpLog Socket connection closed successfully.")
            }
        } catch (e: Exception) {
            Timber.e("tpLog Error closing socket connection: ${e.message}")
        }
    }

    fun updateDohServerUrl(privateDnsLevel: PrivateDnsLevel) {
        runBlocking {
            closeConnection()
        }

        cache.clear()
        privateDns = privateDnsLevel

        Timber.d("tpLog DoH URL set to: ${privateDnsLevel.url}")
    }

    private fun logResponseTime(queryDurationMs: Long) {
        responseTimeStat = Pair(responseTimeStat.first + queryDurationMs, responseTimeStat.second + 1)

        if (responseTimeStat.second >= 50) {
            val avgDuration = responseTimeStat.first / responseTimeStat.second
            analytics.logEvent(
                AnalyticsEvent.AvgKahfGuardResponseTime,
                mapOf(
                    AnalyticsParam.AvgKahfGuardTimeMs to avgDuration.toString(),
                    AnalyticsParam.DnsResolver to privateDns.url,
                )
            )
            responseTimeStat = Pair(0L, 0)
        }
    }

    private fun logTotalResolutionTime(totalTimeMs: Long) {
        totalResolutionTimeStat = Pair(totalResolutionTimeStat.first + totalTimeMs, totalResolutionTimeStat.second + 1)

        if (totalResolutionTimeStat.second >= 10) {
            val avgDuration = totalResolutionTimeStat.first / totalResolutionTimeStat.second
            analytics.logEvent(
                AnalyticsEvent.AvgDnsResolutionTime,
                mapOf(
                    AnalyticsParam.AvgResolutionTimeMs to avgDuration.toString(),
                    AnalyticsParam.DnsResolver to privateDns.url,
                )
            )
            totalResolutionTimeStat = Pair(0L, 0)
        }
    }
}

