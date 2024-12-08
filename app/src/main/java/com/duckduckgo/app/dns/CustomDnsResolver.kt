package com.duckduckgo.app.dns

import android.content.SharedPreferences
import androidx.core.net.toUri
import com.duckduckgo.app.dns.socket_pool.SocketHelper
import com.duckduckgo.app.kahftube.PrivateDnsLevel
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.KAHF_GUARD_DEFAULT
import com.duckduckgo.common.utils.KAHF_GUARD_INTENSITY
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    sharedPreferences: SharedPreferences
) : Dns {
    private var privateDns: PrivateDnsLevel
    private var socketHelper: SocketHelper

    companion object {
        private const val MAX_RETRY = 2
        private val cache = ConcurrentHashMap<String, CachedDnsResponse>()
    }

    init {
        val currentMode = sharedPreferences.getString(KAHF_GUARD_INTENSITY, KAHF_GUARD_DEFAULT) ?: KAHF_GUARD_DEFAULT
        privateDns = PrivateDnsLevel.get(currentMode)

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
                    val queryMessage = Message.newQuery(Record.newRecord(Name.fromString(host), Type.A, DClass.IN))
                    val responseMessage = sendDoTQuery(queryMessage)
                    responseMessage
                } catch (e: Exception) {
                    null
                }
            }
        } ?: try {
            val queryMessage = Message.newQuery(Record.newRecord(Name.fromString(host), Type.A, DClass.IN))
            val responseMessage = sendDoTQuery(queryMessage)
            responseMessage
        } catch (e: Exception) {
            null
        }
    }

    suspend fun resolveDomain(domain: android.net.Uri): Pair<String, String>? {
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
                    val cnameResponseMessage = resolveCname(cnameRecord.rdataToString())
                    cnameResponseMessage?.let { pair ->
                        cache[host] = CachedDnsResponse(responseMessage, System.currentTimeMillis() + 5 * 60 * 1000)
                        Timber.d("tpLog Resolved IP: ${pair.first} ${pair.second}")
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

    private suspend fun resolveCname(domain: String): Pair<String, String>? {
        Timber.d("tpLog Resolving CNAME for $domain")
        var currentDomain = domain
        var attempts = 0

        while (attempts < 2) {
            val responseMessage = checkCacheAndResolve(currentDomain)
            val answer = responseMessage?.getSection(Section.ANSWER)

            val aRecord = answer?.find { it.type == Type.A }
            if (aRecord != null) {
                return Pair(aRecord.rdataToString(), aRecord.name.toString(true))
            }

            val cnameRecord = answer?.find { it.type == Type.CNAME }
            if (cnameRecord != null) {
                currentDomain = cnameRecord.rdataToString()
            } else {
                break
            }

            attempts++
        }

        return null
    }

    private suspend fun sendDoTQuery(query: Message, retry: Int = 0): Message? {
        if (retry > MAX_RETRY) {
            return null
        }

        val queryBytes = query.toWire()

        return withContext(dispatcher.io()) {
            val socketClient = socketHelper.socket

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
}

