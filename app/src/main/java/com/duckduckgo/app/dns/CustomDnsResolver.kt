package com.duckduckgo.app.dns

import android.content.SharedPreferences
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.core.net.toUri
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
    private var socket: SSLSocket

    companion object {
        private const val SO_TIMEOUT = 2000 // 2 seconds
        private const val MAX_RETRY = 2
        private val cache = ConcurrentHashMap<String, CachedDnsResponse>()
    }

    init {
        val currentMode = sharedPreferences.getString(KAHF_GUARD_INTENSITY, KAHF_GUARD_DEFAULT) ?: KAHF_GUARD_DEFAULT
        privateDns = PrivateDnsLevel.get(currentMode)
        socket = initSocket()
    }

    private fun initSocket(): SSLSocket {
        val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = socketFactory.createSocket(privateDns.dnsServerIps.first(), 853) as SSLSocket
        sslSocket.keepAlive = true
        sslSocket.soTimeout = SO_TIMEOUT

        // Set the TLS host header
        sslSocket.sslParameters = sslSocket.sslParameters.apply {
            serverNames = listOf(javax.net.ssl.SNIHostName(privateDns.url))

            if (Build.VERSION.SDK_INT >= VERSION_CODES.Q) {
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

    private fun isSocketUsable(): Boolean {
        val retVal = try {
            socket.soTimeout = 100

            if (socket.inputStream.read() == -1) {
                Timber.i("tpLog Socket is closed by the server.")
                false
            } else {
                true
            }
        } catch (e: SocketTimeoutException) {
            Timber.i("tpLog Socket is usable (no data received within timeout).")
            true
        } catch (e: IOException) {
            Timber.i("tpLog Socket is not usable.")
            false
        }

        // Reset the timeout to the default value
        if (retVal) {
            socket.soTimeout = SO_TIMEOUT
        }
        return retVal
    }

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            runBlocking {
                val resolvedIp = resolveDomain(hostname.toUri())
                resolvedIp?.let {
                    listOf(InetAddress.getByName(it.first))
                } ?: emptyList()
            }
        } catch (e: IOException) {
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

                responseMessage?.getSection(Section.ANSWER)
                    ?.find { it.type == Type.A || it.type == Type.CNAME }
                    ?.let { record ->
                        if (record.type == Type.A) {
                            Pair(record.rdataToString(), record.name.toString(true))
                        } else {
                            val cnameResponseMessage = checkCacheAndResolve(record.rdataToString())
                            cnameResponseMessage?.getSection(Section.ANSWER)
                                ?.find { it.type == Type.A }
                                ?.let { cnameRecord ->
                                    Pair(cnameRecord.rdataToString(), cnameRecord.name.toString(true))
                                }
                        }
                    }?.also { resolvedIp ->
                        // Save to cache with TTL
                        cache[host] = CachedDnsResponse(responseMessage, System.currentTimeMillis() + 5 * 60 * 1000)
                        Timber.d("tpLog Resolved IP: ${resolvedIp.first} ${resolvedIp.second}")
                    }
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
            try {
                if (!isSocketUsable()) {
                    socket = initSocket()
                }

                // Write length-prefixed DNS query without closing the stream
                DataOutputStream(socket.outputStream).let {
                    it.writeShort(queryBytes.size)
                    it.write(queryBytes)
                    it.flush()
                }

                DataInputStream(socket.inputStream).let {
                    val responseBytes = ByteArray(it.readUnsignedShort())
                    it.readFully(responseBytes)
                    Message(responseBytes)
                }
            } catch (e: IOException) {
                Timber.e("tpLog DoT query error: ${e.message ?: e.toString()}")
                sendDoTQuery(query, retry + 1)
            }
        }
    }

    private fun closeConnection() {
        try {
            socket.outputStream.close()
            socket.inputStream.close()
            socket.close()
            Timber.d("tpLog Socket connection closed successfully.")
        } catch (e: IOException) {
            Timber.e("tpLog Error closing socket connection: ${e.message}")
        }
    }

    fun updateDohServerUrl(privateDnsLevel: PrivateDnsLevel) {
        closeConnection()
        cache.clear()

        privateDns = privateDnsLevel
        socket = initSocket()

        Timber.d("tpLog DoH URL set to: ${privateDnsLevel.url}")
    }
}

