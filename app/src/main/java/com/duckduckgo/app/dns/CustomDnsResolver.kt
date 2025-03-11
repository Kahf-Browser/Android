package com.duckduckgo.app.dns

import android.content.SharedPreferences
import androidx.core.net.toUri
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsParam
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.dns.socket_pool.SocketHelper
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
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class CachedDnsResponse(
    val message: Message,
    val expirationTimeMillis: Long
) {
    fun isExpired() = System.currentTimeMillis() > expirationTimeMillis
}

class CustomDnsResolver(
    private val dispatcher: DispatcherProvider,
    private val analytics: AnalyticsService,
    sharedPreferences: SharedPreferences
) : Dns {
    private var privateDns: PrivateDnsLevel
    private var socketHelper: SocketHelper
    private val resolutionTimes = mutableListOf<Long>()

    companion object {
        private const val MAX_RETRY = 2
        private val cache = ConcurrentHashMap<String, CachedDnsResponse>()
    }

    init {
        privateDns = PrivateDnsLevel.getCurrentLevel(sharedPreferences)

        runBlocking {
            socketHelper = SocketHelper.getInstance(privateDns.dnsServerIps.random(), 853, privateDns.url)
        }
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

                try {
                    val t1 = System.currentTimeMillis()
                    val queryMessage = Message.newQuery(Record.newRecord(Name.fromString(host), Type.A, DClass.IN))
                    val responseMessage = sendDoTQuery(queryMessage)
                    calculateResolutionTimeAndLogP90(t1)
                    responseMessage
                } catch (e: Exception) {
                    null
                }
            }
        } ?: try {
            val t1 = System.currentTimeMillis()
            val queryMessage = Message.newQuery(Record.newRecord(Name.fromString(host), Type.A, DClass.IN))
            val responseMessage = sendDoTQuery(queryMessage)
            calculateResolutionTimeAndLogP90(t1)
            responseMessage
        } catch (e: Exception) {
            null
        }
    }

    suspend fun resolveDomain(domain: android.net.Uri, attempt: Int = 0): Pair<String, String>? {
        if (attempt > 2) return null

        return withContext(dispatcher.io()) {
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
    }

    private suspend fun sendDoTQuery(query: Message, retry: Int = 0): Message? {
        if (retry > MAX_RETRY) {
            return null
        }

        val queryBytes = query.toWire()

        return withContext(dispatcher.io()) {
            val socketClient = try {
                withTimeout(1900) {
                    Timber.e("tpLog Trying to get a socket")
                    socketHelper.socket
                }
            } catch (e: Exception) {
                Timber.e("tpLog Error getting socket: ${e.message}")
                return@withContext null
            }

            try {
                val ans = socketClient.execute(queryBytes)
                val message = Message(ans)
                socketHelper.returnSocket(socketClient)
                message
            } catch (e: IOException) {
                Timber.e("tpLog DoT query error 1: ${e.message ?: e.toString()}")
                // socketHelper.returnSocket(socketClient) // intentionally not invalidating the socket here
                sendDoTQuery(query, retry + 1)
            } catch (e: Exception) {
                Timber.e("tpLog DoT query error 2: ${e.message ?: e.toString()}")
                socketHelper.invalidateSocket(socketClient)
                sendDoTQuery(query, retry + 1)
            }
        }
    }

    private suspend fun closeConnection() {
        try {
            withContext(dispatcher.io()) {
                socketHelper.shutDown()
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

        runBlocking {
            socketHelper = SocketHelper.getInstance(privateDns.dnsServerIps.random(), 853, privateDns.url)
            Timber.d("tpLog DoH URL set to: ${privateDnsLevel.url}")
        }
    }

    fun calculateResolutionTimeAndLogP90(t1: Long) {
        val t2 = System.currentTimeMillis()
        val inferenceTime = t2 - t1
        resolutionTimes.add(inferenceTime)

        // Log P90 DNS resolution time for every 30 images to GA
        if (resolutionTimes.size >= 30) {
            val p90 = resolutionTimes.sorted()[resolutionTimes.size * 90 / 100]
            analytics.logEvent(
                AnalyticsEvent.P90DnsResolution,
                mapOf(
                    AnalyticsParam.DnsResolutionTime to p90.toString(),
                    AnalyticsParam.DnsResolver to privateDns.url,
                ),
            )
            resolutionTimes.clear()
        }
    }
}

