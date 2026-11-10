package hu.kal8989.sutoseged

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Home Connect készülékek a helyi hálózaton "_homeconnect._tcp" néven hirdetik
 * magukat (mDNS). Ebből derül ki a sütő aktuális IP-címe akkor is, ha a router
 * közben új címet adott neki.
 */
object OvenDiscovery {

    private const val SERVICE_TYPE = "_homeconnect._tcp"

    class Found(val name: String, val ip: String)

    /** A keresés eredménye: a sütő (vagy null), és hogy hogyan találtuk meg, vagy miért nem. */
    class Result(val oven: Found?, val note: String)

    /**
     * Megkeresi a sütőt: előbb a saját hirdetése alapján, és ha az nem jön, a wifi összes
     * címét végignézi a 443-as porton, majd a sütő kulcsával ellenőrzi, melyik a sütő.
     * [knownGood]: a cím, amelyen az app éppen kapcsolódva van (ezt nem kell ellenőrizni).
     * Blokkol (néhány másodpercig), csak háttérszálról hívható.
     */
    fun locate(context: Context, psk: String, knownGood: String? = null): Result {
        val wifi = LocalOven.findWifi(context)
            ?: return Result(null, "a telefon most nincs wifin.")

        val announced = find(context, 4000)
        pickOven(announced)?.let { return Result(it, "a sütő hirdetése alapján") }

        val open = scanPort(context, wifi, 443)
        if (open.isEmpty()) {
            return Result(null, "a wifin egyetlen eszköz sem fogad kapcsolatot a 443-as porton.")
        }
        if (knownGood != null && knownGood in open) {
            return Result(Found("Home Connect", knownGood), "ezen a címen van most is kapcsolat")
        }
        for (ip in open) {
            if (ovenAcceptsKey(ip, psk, wifi)) {
                return Result(Found("Home Connect", ip), "hálózati kereséssel, a sütő kulcsa alapján")
            }
        }
        return Result(
            null,
            "${open.size} eszköz fogad kapcsolatot a 443-as porton (${open.joinToString(", ")}), " +
                "de egyik sem fogadta el a sütő kulcsát."
        )
    }

    /** A wifi alhálózatának (legfeljebb 254 cím) azon címei, ahol a [port] nyitva van. */
    private fun scanPort(context: Context, wifi: Network, port: Int): List<String> {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val link = cm.getLinkProperties(wifi)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address } ?: return emptyList()
        val own = link.address.address
        val ownInt = ((own[0].toInt() and 255) shl 24) or ((own[1].toInt() and 255) shl 16) or
            ((own[2].toInt() and 255) shl 8) or (own[3].toInt() and 255)
        // Nagyobb hálózatnál is csak a telefon saját /24-es tartományát nézzük végig.
        val bits = 32 - link.prefixLength.coerceIn(24, 30)
        val net = ownInt and (-1 shl bits)
        val hosts = (1 until (1 shl bits) - 1).map { net or it }.filter { it != ownInt }

        val pool = Executors.newFixedThreadPool(48)
        try {
            val jobs = hosts.map { h ->
                pool.submit(Callable<String?> {
                    val ip = "${(h ushr 24) and 255}.${(h ushr 16) and 255}.${(h ushr 8) and 255}.${h and 255}"
                    try {
                        Socket().use { s ->
                            wifi.bindSocket(s)
                            s.connect(InetSocketAddress(ip, port), 500)
                            ip
                        }
                    } catch (e: Exception) {
                        null
                    }
                })
            }
            return jobs.mapNotNull { job -> runCatching { job.get(10, TimeUnit.SECONDS) }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Megkeresi a hálózaton hirdető készülékeket. Blokkol (legfeljebb [timeoutMs]),
     * ezért csak háttérszálról hívható.
     */
    fun find(context: Context, timeoutMs: Long = 6000): List<Found> {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()
        val services = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                services.offer(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            return emptyList()
        }

        val out = mutableListOf<Found>()
        val seen = HashSet<String>()
        var deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val info = services.poll(left, TimeUnit.MILLISECONDS) ?: break
                if (!seen.add(info.serviceName ?: "")) continue
                resolve(nsd, info)?.let { found ->
                    if (out.none { it.ip == found.ip }) out.add(found)
                    // Ha már van találat, csak rövid ideig várunk továbbiakra.
                    deadline = minOf(deadline, System.currentTimeMillis() + 1500)
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return out
    }

    /** Egy hirdetés feloldása IPv4-címre; egyszerre csak egy feloldás futhat. */
    @Suppress("DEPRECATION")
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo): Found? {
        // A sorba nem tehető null, ezért a "nincs eredmény" egy üres lista.
        val result = ArrayBlockingQueue<List<NsdServiceInfo>>(1)
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(r: NsdServiceInfo) {
                    result.offer(listOf(r))
                }
                override fun onResolveFailed(r: NsdServiceInfo, errorCode: Int) {
                    result.offer(emptyList())
                }
            })
        } catch (e: Exception) {
            return null
        }
        val r = result.poll(3000, TimeUnit.MILLISECONDS)?.firstOrNull() ?: return null
        val host = r.host as? Inet4Address ?: return null
        val ip = host.hostAddress ?: return null
        return Found(r.serviceName ?: info.serviceName ?: "", ip)
    }

    /**
     * A találatok közül a sütő: ha csak egy készülék van, az; ha több, amelyiknek
     * a nevében benne van a sütő típusa vagy az "Oven" szó. Null, ha nem egyértelmű.
     */
    fun pickOven(found: List<Found>): Found? {
        if (found.size == 1) return found[0]
        val ovens = found.filter {
            it.name.contains("HBG7341", ignoreCase = true) || it.name.contains("oven", ignoreCase = true)
        }
        return ovens.singleOrNull()
    }
}
