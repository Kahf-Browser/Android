package com.duckduckgo.app.dns

import android.content.SharedPreferences
import androidx.core.net.toUri
import com.duckduckgo.app.kahftube.PrivateDnsLevel
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.KAHF_GUARD_DEFAULT
import com.duckduckgo.common.utils.KAHF_GUARD_INTENSITY
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import org.xbill.DNS.Cache
import org.xbill.DNS.DClass
import org.xbill.DNS.ExtendedResolver
import org.xbill.DNS.Lookup
import org.xbill.DNS.Record
import org.xbill.DNS.Resolver
import org.xbill.DNS.Type
import timber.log.Timber
import java.net.InetAddress
import java.time.Duration

class CustomDnsResolver(
    private val dispatcher: DispatcherProvider,
    sharedPreferences: SharedPreferences
) : Dns {
    private val cacheHigh = Cache()
    private val cacheMid = Cache()
    private val cacheLow = Cache()

    init {
        val currentMode = sharedPreferences.getString(KAHF_GUARD_INTENSITY, KAHF_GUARD_DEFAULT) ?: KAHF_GUARD_DEFAULT
        updateDohServerUrl(PrivateDnsLevel.get(currentMode))
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val resolvedIp: Pair<String, String>?

        runBlocking {
            resolvedIp = resolveDomain(hostname.toUri())
        }

        return if (resolvedIp != null) {
            Timber.d("tpLog resolvedIp: $hostname $resolvedIp")
            listOf(InetAddress.getByName(resolvedIp.first))
        } else {
            Timber.d("tpLog resolvedIp null")
            emptyList()
        }
    }

    fun updateDohServerUrl(privateDnsLevel: PrivateDnsLevel) {
        val cache = when (privateDnsLevel) {
            PrivateDnsLevel.High -> cacheHigh
            PrivateDnsLevel.Medium -> cacheMid
            else -> cacheLow
        }

        val dnsResolver: Resolver = ExtendedResolver(privateDnsLevel.dnsServerIps).also {
            it.setTCP(true)
            it.setPort(53)
            it.timeout = Duration.ofSeconds(1)
            it.loadBalance = true
            it.retries = 1
        }

        Lookup.refreshDefault()
        Lookup.setDefaultResolver(ExtendedResolver(arrayOf(dnsResolver)))
        Lookup.setDefaultCache(cache, DClass.ANY)

        Timber.d("tpLog DoH URL set to: ${privateDnsLevel.url}")
    }

    /**
     * @return Pair.first is the IP address, Pair.second is the domain name
     */
    fun resolveDomain(domain: android.net.Uri): Pair<String, String>? {
        try {
            val host = (domain.host ?: domain.toString()).removeSuffix(".").plus(".")
            var ip: String
            val lookup = Lookup(host, Type.A)
            val records: Array<Record>? = lookup.run()

            if (lookup.result == Lookup.SUCCESSFUL) {
                records?.forEach { record ->
                    ip = record.rdataToString()
                    Timber.d("tpLog Resolved IP: $ip ${record.name}")
                    return Pair(ip, record.name.toString(true))
                }
            } else {
                Timber.d("tpLog Failed to resolve domain: ${lookup.errorString}")
                return null
            }
        } catch (e: Exception) {
            Timber.d("tpLog Exception at resolving domain: ${e.message}")
        }

        return null
    }
}
