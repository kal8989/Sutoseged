package hu.kal8989.sutoseged

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Közvetlen kapcsolat a sütővel a helyi hálózaton, felhő nélkül.
 *
 * A sütő a 443-as porton egy websocketet szolgál ki, amit TLS-PSK véd
 * (ECDHE-PSK-CHACHA20-POLY1305, TLS 1.2, identity: HCCOM_Local_App).
 * Az Android beépített TLS-e ezt nem ismeri, ezért a kézfogást a
 * BouncyCastle alacsonyszintű TLS rétege végzi, a websocketet pedig
 * kézzel írjuk a titkosított adatfolyam fölé.
 */

// ---------------------------------------------------------------- azonosítók

object OvenUid {
    // beállítások
    const val POWER_STATE = 539           // 2 = On, 3 = Standby
    const val REMOTE_CONTROL_LEVEL = 15   // 0 = Monitoring, 1 = ManualRemoteStart, 2 = PermanentRemoteStart

    // parancsok
    const val ABORT_PROGRAM = 512
    const val PAUSE_PROGRAM = 537
    const val RESUME_PROGRAM = 546

    // egyéb beállítások
    const val CHILD_LOCK = 524
    const val LIGHT_POWER = 6112          // a sütőtér világítása
    const val CAVITY_ILLUMINATION = 4432  // 0 = On, 1 = RestrictedOn, 2 = Off
    const val ALARM_CLOCK = 6135          // konyhai időzítő, másodperc
    const val SIGNAL_DURATION = 4364      // 0 rövid, 1 közepes, 2 hosszú, 3 egyszeri

    // események
    const val EV_PROGRAM_FINISHED = 6120  // 1 = Present
    const val EV_PREHEAT_FINISHED = 6123

    // program opciók
    const val OPT_TEMPERATURE = 5120      // 30–300 °C
    const val OPT_DURATION = 548          // másodperc
    const val OPT_LEVEL = 5122            // grillfokozat 1–3
    const val OPT_FAST_PREHEAT = 5123     // gyors felfűtés

    // állapotok
    const val ST_OPERATION = 552          // 0 Inactive, 1 Ready, 3 Run, 6 Finished
    const val ST_REMOTE_START_ALLOWED = 517
    const val ST_STARTABLE = 6297
    const val ST_CURRENT_TEMP = 6084
    const val ST_SETPOINT = 5120
    const val ST_DOOR = 527               // 0 Open, 1 Closed
    const val ST_REMAINING = 544          // másodperc
    const val ST_ACTIVE_PROGRAM = 256
}

/** A HBG7341B1 fűtési módjai: magyar név -> a sütő saját programazonosítója. */
object OvenPrograms {

    private val byKeyword: List<Pair<String, Int>> = listOf(
        "3d forró levegő" to 8217,
        "3d forro levego" to 8217,
        "kímélő forró levegő" to 8218,
        "kímélő felső/alsó" to 8211,
        "kímélő felso/also" to 8211,
        "felső/alsó" to 8209,
        "felso/also" to 8209,
        "air fry" to 8277,
        "légkeveréses grill" to 8212,
        "legkevereses grill" to 8212,
        "grill, nagy" to 8224,
        "grill nagy" to 8224,
        "grill, kis" to 8225,
        "grill kis" to 8225,
        "pizza" to 8213,
        "alsó sütés" to 8227,
        "also sutes" to 8227,
        "kímélő párolás" to 8226,
        "kimelo parolas" to 8226,
        "felolvasztás" to 8233,
        "melegen tartás" to 8229,
        "edény előmelegít" to 8230,
        "pihentet" to 8263,
        "előmelegítés" to 8215,
        "elomelegites" to 8215
    )

    const val PREHEATING = 8215

    /** A kézi vezérlőlap listája: ahogy a sütő kijelzőjén is hívjuk őket. */
    val manualModes: List<Pair<String, Int>> = listOf(
        "3D forró levegő" to 8217,
        "Felső/alsó sütés" to 8209,
        "Kímélő forró levegő" to 8218,
        "Kímélő felső/alsó fűtés" to 8211,
        "Air Fry" to 8277,
        "Légkeveréses grillezés" to 8212,
        "Grill, nagy felület" to 8224,
        "Grill, kis felület" to 8225,
        "Pizzafokozat" to 8213,
        "Alsó sütés" to 8227,
        "Kímélő párolás" to 8226,
        "Felolvasztás" to 8233,
        "Melegen tartás" to 8229,
        "Edény előmelegítése" to 8230,
        "Pihentetés" to 8263,
        "Előmelegítés" to 8215
    )

    fun nameOf(uid: Int): String =
        manualModes.firstOrNull { it.second == uid }?.first ?: "ismeretlen program"

    /** A kézikönyv szerinti hőfoktartomány módonként. */
    fun tempRange(uid: Int): IntRange = when (uid) {
        8218 -> 125..250          // kímélő forró levegő
        8211 -> 150..250          // kímélő felső/alsó
        8226 -> 70..120           // kímélő párolás
        8233 -> 30..60            // felolvasztás
        8229 -> 50..100           // melegen tartás
        8230 -> 30..90            // edény előmelegítése
        8209, 8277 -> 30..300     // felső/alsó, Air Fry
        8212 -> 30..300           // légkeveréses grillezés
        else -> 30..275
    }

    /** A fűtési mód nevéből a programazonosító, vagy null, ha nem ismerjük fel. */
    fun uidFor(futesiMod: String, fazisNev: String = ""): Int? {
        val m = futesiMod.lowercase()
        byKeyword.forEach { (needle, uid) -> if (m.contains(needle)) return uid }
        if (fazisNev.lowercase().contains("előmelegít")) return PREHEATING
        return null
    }

    fun isGrill(uid: Int) = uid == 8224 || uid == 8225

    /** "190 °C" -> 190 ; "2. grillfokozat" -> null */
    fun temperature(s: String): Int? {
        if (s.lowercase().contains("fokozat")) return null
        val n = Regex("\\d+").find(s)?.value?.toIntOrNull() ?: return null
        return if (n in 30..300) n else null
    }

    /** "2. grillfokozat" -> 2 */
    fun level(s: String): Int? {
        if (!s.lowercase().contains("fokozat")) return null
        val n = Regex("\\d").find(s)?.value?.toIntOrNull() ?: return null
        return if (n in 1..3) n else null
    }
}

// ---------------------------------------------------------------- websocket

/**
 * A legszükségesebb websocket-kliens a titkosított adatfolyam fölé.
 * A kliens felől érkező kereteket maszkolni kell, a szerver felől jövőket nem.
 */
private class MiniWebSocket(raw: InputStream, private val out: OutputStream) {

    private val inp = BufferedInputStream(raw, 8192)
    private val rnd = SecureRandom()
    private val writeLock = Any()

    /** Mikor jött utoljára bármilyen keret a sütőtől (a kapcsolatfigyelőnek). */
    @Volatile var lastRx = System.currentTimeMillis()
        private set

    fun handshake(host: String, port: Int, path: String) {
        val nonce = ByteArray(16).also { rnd.nextBytes(it) }
        val key = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        out.write(req.toByteArray(Charsets.UTF_8))
        out.flush()

        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val c = inp.read()
            if (c < 0) throw java.io.IOException("a kapcsolat megszakadt a kézfogás alatt")
            sb.append(c.toChar())
            if (sb.length > 8192) throw java.io.IOException("értelmezhetetlen válasz")
        }
        val first = sb.toString().substringBefore("\r\n")
        if (!first.contains("101")) {
            throw java.io.IOException("a sütő elutasította a kapcsolatot: $first")
        }
    }

    private fun readByte(): Int {
        val c = inp.read()
        if (c < 0) throw java.io.IOException("lezárt kapcsolat")
        return c
    }

    private fun readExact(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(b, off, n - off)
            if (r < 0) throw java.io.IOException("lezárt kapcsolat")
            off += r
        }
        return b
    }

    /** Egy teljes szöveges üzenet; null, ha a túloldal lezárta. */
    fun read(): String? {
        val acc = ByteArrayOutputStream()
        while (true) {
            val b1 = readByte()
            val b2 = readByte()
            val fin = (b1 and 0x80) != 0
            val op = b1 and 0x0F
            val masked = (b2 and 0x80) != 0
            var len = (b2 and 0x7F).toLong()
            if (len == 126L) {
                val e = readExact(2)
                len = ((e[0].toLong() and 0xFF) shl 8) or (e[1].toLong() and 0xFF)
            } else if (len == 127L) {
                val e = readExact(8)
                len = 0
                for (v in e) len = (len shl 8) or (v.toLong() and 0xFF)
            }
            if (len > 8L * 1024 * 1024) throw java.io.IOException("túl nagy keret")
            val mask = if (masked) readExact(4) else null
            val payload = if (len > 0) readExact(len.toInt()) else ByteArray(0)
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            lastRx = System.currentTimeMillis()

            if (op == 0x8) {                       // close
                runCatching { writeFrame(0x8, ByteArray(0)) }
                return null
            }
            if (op == 0x9) {                       // ping -> pong
                writeFrame(0xA, payload)
            } else if (op != 0xA) {                // a pong-ot eldobjuk
                acc.write(payload)
                if (fin) return String(acc.toByteArray(), Charsets.UTF_8)
            }
        }
    }

    fun sendText(s: String) = writeFrame(0x1, s.toByteArray(Charsets.UTF_8))

    fun ping() = writeFrame(0x9, ByteArray(0))

    fun close() {
        runCatching { writeFrame(0x8, ByteArray(0)) }
    }

    private fun writeFrame(op: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val f = ByteArrayOutputStream()
            f.write(0x80 or op)
            val n = payload.size
            when {
                n < 126 -> f.write(0x80 or n)
                n < 65536 -> {
                    f.write(0x80 or 126)
                    f.write((n shr 8) and 0xFF)
                    f.write(n and 0xFF)
                }
                else -> {
                    f.write(0x80 or 127)
                    for (i in 7 downTo 0) f.write(((n.toLong() shr (8 * i)) and 0xFF).toInt())
                }
            }
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            f.write(mask, 0, 4)
            val masked = ByteArray(n)
            for (i in 0 until n) masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            f.write(masked, 0, n)
            out.write(f.toByteArray())
            out.flush()
        }
    }
}

// ---------------------------------------------------------------- munkamenet

private class OvenSession(
    private val ip: String,
    pskB64: String,
    private val deviceId: String,
    /** A wifi-hálózat: erre kötjük a socketet, hogy ne a mobilneten induljon el. */
    private val wifi: Network?
) {
    private val psk = decodePsk(pskB64)
    @Volatile private var socket: Socket? = null
    @Volatile private var proto: TlsClientProtocol? = null
    @Volatile private var ws: MiniWebSocket? = null

    @Volatile private var sid: Long = 0
    @Volatile private var txId: Long = 1
    @Volatile var ready = false
        private set

    /**
     * Miért zártuk le mi magunk: null = nem mi zártuk (a sütő vagy a hálózat),
     * "" = szándékos bontás (nem hiba), egyéb = a kapcsolatfigyelő üzenete.
     */
    @Volatile var closeReason: String? = null
        private set

    private val sendLock = Any()

    /** A választ váró kérések, üzenetazonosító szerint. */
    private val pending = ConcurrentHashMap<Long, ArrayBlockingQueue<JSONObject>>()

    val isOpen: Boolean get() = ws != null

    val lastRx: Long get() = ws?.lastRx ?: 0L

    fun open() {
        val s = Socket()
        // Ha a telefon a wifi mellett mobilnetet is használ (pl. mert a wifinek
        // "nincs internetje"), az Android a mobilnetre tenné a kapcsolatot,
        // ahonnan a sütő helyi címe elérhetetlen. Ezért a wifire kötjük.
        wifi?.let { runCatching { it.bindSocket(s) } }
        s.connect(InetSocketAddress(ip, PORT), 8000)
        // Olvasási időkorlát nélkül: egy félbeszakadt TLS-rekord után a kapcsolat
        // amúgy sem folytatható. A néma kapcsolatot a LocalOven figyelője bontja.
        s.soTimeout = 0
        s.tcpNoDelay = true
        socket = s

        val p = TlsClientProtocol(s.getInputStream(), s.getOutputStream())
        p.connect(
            PSKTlsClient(
                BcTlsCrypto(SecureRandom()),
                IDENTITY.toByteArray(Charsets.UTF_8),
                psk
            )
        )
        proto = p

        val w = MiniWebSocket(p.inputStream, p.outputStream)
        w.handshake(ip, PORT, PATH)
        ws = w
    }

    /**
     * A beérkező üzeneteket dolgozza fel, amíg a kapcsolat él.
     * A munkamenetet a sütő nyitja: ő küld /ei/initialValues POST-ot,
     * abból derül ki a munkamenet-azonosító és az üzenetszámláló kezdete.
     */
    fun pump(onValues: (Map<Int, Any>) -> Unit, onReady: () -> Unit) {
        while (true) {
            val text = ws?.read() ?: return
            val m = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val resource = m.optString("resource")
            val action = m.optString("action")

            // A sütő minden kérésünkre RESPONSE-szal felel, ugyanazzal az msgID-vel;
            // hiba esetén "code" mező is van benne.
            if (action == "RESPONSE") {
                pending.remove(m.optLong("msgID", -1L))?.offer(m)
            }

            if (resource == "/ei/initialValues" && action == "POST") {
                sid = m.optLong("sID")
                val data = m.optJSONArray("data")?.optJSONObject(0)
                txId = data?.optLong("edMsgID", 1L) ?: 1L
                val echo = m.optLong("msgID")
                raw(
                    "{\"sID\":$sid,\"msgID\":$echo,\"resource\":\"/ei/initialValues\"," +
                        "\"version\":2,\"action\":\"RESPONSE\",\"data\":[{\"deviceType\":\"Application\"," +
                        "\"deviceName\":\"Sutoseged\",\"deviceID\":\"$deviceId\"}]}"
                )
                send("/ci/services", 1, "GET", null)
                continue
            }

            // Csak az első alkalommal: a kapcsolatfigyelő később ugyanezzel a kéréssel
            // ellenőrzi, él-e még a sütő, és az nem indíthatja újra a munkamenetet.
            if (resource == "/ci/services" && action == "RESPONSE" && !ready) {
                send("/ei/deviceReady", 2, "NOTIFY", null)
                send("/ro/allMandatoryValues", 1, "GET", null)
                ready = true
                onReady()
                continue
            }

            if (resource.startsWith("/ro/")) {
                val arr = m.optJSONArray("data") ?: continue
                val out = HashMap<Int, Any>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (!o.has("uid")) continue
                    val v = o.opt("value") ?: continue
                    out[o.optInt("uid")] = v
                }
                if (out.isNotEmpty()) onValues(out)
            }
        }
    }

    /** Elküldi az üzenetet, válaszra nem vár. */
    fun send(resource: String, version: Int, action: String, data: String?) {
        post(resource, version, action, data, null)
    }

    /**
     * Elküldi az üzenetet, és megvárja rá a sütő válaszát.
     * Null, ha a megadott időn belül nem jött válasz.
     */
    fun request(
        resource: String,
        version: Int,
        action: String,
        data: String?,
        timeoutMs: Long = 3000
    ): JSONObject? {
        val q = ArrayBlockingQueue<JSONObject>(1)
        val id = post(resource, version, action, data, q)
        return try {
            q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pending.remove(id)
        }
    }

    /** Üzenet összeállítása kézzel, hogy a mezők sorrendje pontosan a megszokott legyen. */
    private fun post(
        resource: String,
        version: Int,
        action: String,
        data: String?,
        waiter: ArrayBlockingQueue<JSONObject>?
    ): Long =
        synchronized(sendLock) {
            val id = txId++
            if (waiter != null) pending[id] = waiter
            val sb = StringBuilder()
            sb.append("{\"sID\":").append(sid)
                .append(",\"msgID\":").append(id)
                .append(",\"resource\":\"").append(resource).append('"')
                .append(",\"version\":").append(version)
                .append(",\"action\":\"").append(action).append('"')
            if (data != null) sb.append(",\"data\":").append(data)
            sb.append('}')
            try {
                val w = ws ?: throw java.io.IOException("nincs kapcsolat")
                w.sendText(sb.toString())
            } catch (e: Exception) {
                pending.remove(id)
                throw e
            }
            id
        }

    private fun raw(json: String) {
        synchronized(sendLock) { ws?.sendText(json) }
    }

    fun ping() {
        val w = ws ?: throw java.io.IOException("nincs kapcsolat")
        w.ping()
    }

    /** Lezárja a kapcsolatot. A reason: "" = szándékos bontás, egyéb = hibaüzenet. */
    fun close(reason: String = "") {
        if (closeReason == null) closeReason = reason
        runCatching { ws?.close() }
        runCatching { proto?.close() }
        runCatching { socket?.close() }
        ws = null; proto = null; socket = null; ready = false
    }

    companion object {
        const val PORT = 443
        const val PATH = "/homeconnect"
        const val IDENTITY = "HCCOM_Local_App"

        /** A kulcs URL-biztos base64-ben van, kipótolt kitöltéssel. */
        fun decodePsk(s: String): ByteArray {
            val t = s.trim()
            val pad = (4 - t.length % 4) % 4
            return Base64.decode(t + "=".repeat(pad), Base64.URL_SAFE or Base64.NO_WRAP)
        }
    }
}

// ---------------------------------------------------------------- kapcsolat

object LocalOven {

    var status by mutableStateOf("Nincs kapcsolat")
        private set
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)

    /** A sütő élő értékei azonosító szerint. */
    val values = mutableStateMapOf<Int, Any>()

    /** Előtérben van-e az app – csak ilyenkor kapcsolódunk újra magunktól. */
    @Volatile var foreground = false

    @Volatile private var session: OvenSession? = null
    private val main = Handler(Looper.getMainLooper())

    // A felület állapota a Compose-é, a döntésekhez viszont szálbiztos mezők kellenek:
    // a kapcsolatot háttérszálról is építjük, a kijelzést pedig a fő szálon frissítjük.
    @Volatile private var active = false
    @Volatile private var failure: String? = null
    @Volatile private var retries = 0
    @Volatile private var ip = ""

    // a sütés végének felismerése
    @Volatile private var lastOp: Int? = null
    @Volatile private var sawRunning = false
    @Volatile private var finishHandled = false

    // a hátralévő idő helyi továbbszámolása
    @Volatile private var remBaseSec = -1
    @Volatile private var remBaseAt = 0L
    @Volatile private var remFrozen = false

    private const val PING_EVERY_MS = 20_000L
    private const val SILENCE_LIMIT_MS = 20_000L

    private fun ui(block: () -> Unit) = main.post(block)

    // ------------------------------------------------------------ olvasható állapot

    fun operationText(): String = when ((values[OvenUid.ST_OPERATION] as? Number)?.toInt()) {
        0 -> "kikapcsolva"
        1 -> "készenlétben"
        2 -> "késleltetett indítás"
        3 -> "fut"
        4 -> "szünet"
        5 -> "beavatkozás kell"
        6 -> "kész"
        7 -> "hiba"
        8 -> "leállítás alatt"
        else -> "ismeretlen"
    }

    fun currentTemp(): Int? = (values[OvenUid.ST_CURRENT_TEMP] as? Number)?.toInt()

    /**
     * A sütő csak akkor küld hátralévő időt, amikor az változik, leállításkor
     * pedig nem nullázza – ezért csak futó (vagy szüneteltetett) programnál
     * mutatjuk, és a két jelentés között itt számoljuk tovább. Szünet alatt áll.
     */
    fun remainingSec(): Int? {
        val op = (values[OvenUid.ST_OPERATION] as? Number)?.toInt()
        if (op != 2 && op != 3 && op != 4) return null
        val base = remBaseSec
        if (base < 0) return null
        if (remFrozen) return base
        val passed = ((System.currentTimeMillis() - remBaseAt) / 1000).toInt()
        return (base - passed).coerceAtLeast(0)
    }

    fun remainingMin(): Int? = remainingSec()?.let { if (it > 0) (it + 59) / 60 else null }

    /** A beállított teljes időtartam másodpercben. */
    fun totalSec(): Int? = (values[OvenUid.OPT_DURATION] as? Number)?.toInt()?.takeIf { it > 0 }

    /** 0..1 közötti előrehaladás, vagy null, ha nincs futó program. */
    fun progress(): Float? {
        val total = totalSec() ?: return null
        val rem = remainingSec() ?: return null
        return ((total - rem).toFloat() / total).coerceIn(0f, 1f)
    }

    /** Egy óra fölött óra:perc:másodperc, különben perc:másodperc. */
    fun formatClock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    fun remoteStartAllowed(): Boolean = values[OvenUid.ST_REMOTE_START_ALLOWED] == true

    fun powerOn(): Boolean = (values[OvenUid.POWER_STATE] as? Number)?.toInt() == 2

    fun setpoint(): Int? = (values[OvenUid.OPT_TEMPERATURE] as? Number)?.toInt()

    /** A grillfokozat, ha a sütő jelenti. */
    fun level(): Int? = (values[OvenUid.OPT_LEVEL] as? Number)?.toInt()

    fun doorOpen(): Boolean = (values[OvenUid.ST_DOOR] as? Number)?.toInt() == 0

    fun lightOn(): Boolean = values[OvenUid.LIGHT_POWER] == true

    fun childLockOn(): Boolean = values[OvenUid.CHILD_LOCK] == true

    fun running(): Boolean = (values[OvenUid.ST_OPERATION] as? Number)?.toInt() == 3

    fun paused(): Boolean = (values[OvenUid.ST_OPERATION] as? Number)?.toInt() == 4

    fun activeProgram(): Int? =
        (values[OvenUid.ST_ACTIVE_PROGRAM] as? Number)?.toInt()?.takeIf { it != 0 }

    /** Elnézi, ha a kulcs helyett a teljes JSON-sor kerül beillesztésre. */
    fun cleanPsk(input: String): String {
        val m = Regex("\"key\"\\s*:\\s*\"([^\"]+)\"").find(input)
        return (m?.groupValues?.get(1) ?: input).trim()
    }

    fun summary(): String {
        if (!connected) return status
        val parts = mutableListOf<String>()
        currentTemp()?.let { parts.add("$it °C") }
        parts.add(operationText())
        remainingMin()?.let { parts.add("még $it perc") }
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------ kapcsolat

    @Synchronized
    fun connect(store: Store) {
        if (active) return
        val ip = store.ovenIp.trim()
        val psk = store.ovenPsk.trim()
        this.ip = ip
        if (ip.isEmpty() || psk.isEmpty()) {
            failure = "Hiányzik a sütő címe vagy kulcsa."
            ui { lastError = failure }
            return
        }
        active = true
        failure = null
        // Új kapcsolat, tiszta lap: a régi értékek alapján nem dönthetünk.
        lastOp = null
        sawRunning = false
        finishHandled = false
        remBaseSec = -1
        remFrozen = false
        ui { busy = true; status = "Kapcsolódás…"; lastError = null; values.clear() }

        val deviceId = store.ovenDeviceId
        val wifi = findWifi(store.appContext)
        if (wifi == null) {
            active = false
            failure = "A telefon most nincs wifin (mobilnetet használ). Kapcsold be a wifit, " +
                "és csatlakozz ugyanarra a hálózatra, amelyiken a sütő van."
            ui { busy = false; status = "Nincs kapcsolat"; lastError = failure }
            return
        }
        thread(name = "oven-link", isDaemon = true) {
            val s = OvenSession(ip, psk, deviceId, wifi)
            session = s
            var wasReady = false
            try {
                s.open()
                startWatchdog(s)
                ui { status = "Munkamenet nyitása…" }
                s.pump(
                    onValues = { map -> onOvenValues(store, map) },
                    onReady = {
                        wasReady = true
                        retries = 0
                        ui { connected = true; busy = false; status = "Kapcsolódva" }
                    }
                )
                val why = s.closeReason
                if (session === s && why != "") {
                    val text = why ?: "A sütő lezárta a kapcsolatot."
                    failure = text
                    ui { status = "Nincs kapcsolat"; lastError = text }
                }
            } catch (e: Exception) {
                val why = s.closeReason
                // Szándékos bontásnál ("") nincs mit jelenteni.
                if (session === s && why != "") {
                    val msg = if (why.isNullOrEmpty()) humanError(e) else why
                    failure = msg
                    ui { lastError = msg; status = "Nincs kapcsolat" }
                }
            } finally {
                runCatching { s.close() }
                // Ha közben bontottak és újra kapcsolódtak, a régi szál nem nyúlhat az újhoz.
                if (session === s) {
                    session = null
                    active = false
                    remBaseSec = -1
                    ui { connected = false; busy = false; values.clear() }
                    // Működő kapcsolat szakadt meg, és az app előtérben van: újrapróbálja.
                    if (wasReady && foreground && retries < 3) {
                        retries++
                        main.postDelayed({ connect(store) }, 3000)
                    }
                }
            }
        }
    }

    /**
     * Kapcsolatfigyelő: ha egy ideje semmi sem jött a sütőtől, megkérdezi, él-e még.
     * Ha nem felel, bontja a kapcsolatot – különben egy elalvás vagy wifi-szakadás
     * után az app "Kapcsolódva" állapotban, befagyott értékekkel maradna.
     */
    private fun startWatchdog(s: OvenSession) {
        thread(name = "oven-watchdog", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(PING_EVERY_MS)
                } catch (e: InterruptedException) {
                    return@thread
                }
                if (session !== s || !s.isOpen) return@thread
                try {
                    s.ping()
                } catch (e: Exception) {
                    s.close("A kapcsolat megszakadt.")
                    return@thread
                }
                if (s.ready && System.currentTimeMillis() - s.lastRx > SILENCE_LIMIT_MS) {
                    val answer = try {
                        s.request("/ci/services", 1, "GET", null, 6000)
                    } catch (e: Exception) {
                        null
                    }
                    if (answer == null && session === s) {
                        s.close("A sütő nem válaszol – a kapcsolat megszakadt.")
                        return@thread
                    }
                }
            }
        }
    }

    fun disconnect() {
        val s = session
        session = null
        active = false
        failure = null
        remBaseSec = -1
        // A lezárás hálózati írással jár, az a fő szálon tilos.
        if (s != null) thread(name = "oven-close", isDaemon = true) { runCatching { s.close("") } }
        ui {
            connected = false
            busy = false
            status = "Nincs kapcsolat"
            lastError = null
            values.clear()
        }
    }

    /** Minden beérkező értékcsomag ide fut be (az olvasó szálon). */
    private fun onOvenValues(store: Store, map: Map<Int, Any>) {
        val op = (map[OvenUid.ST_OPERATION] as? Number)?.toInt()
        val prev = lastOp
        if (op != null) lastOp = op
        trackRemaining(map, op, prev)
        checkFinish(store, map, op)
        ui { values.putAll(map) }
    }

    private fun trackRemaining(map: Map<Int, Any>, op: Int?, prev: Int?) {
        val now = System.currentTimeMillis()
        if (op != null && op != prev) {
            when (op) {
                4 -> {                                   // szünet: megáll a számolás
                    if (remBaseSec >= 0 && !remFrozen) {
                        remBaseSec = (remBaseSec - ((now - remBaseAt) / 1000).toInt()).coerceAtLeast(0)
                    }
                    remFrozen = true
                    remBaseAt = now
                }
                2, 3 -> {                                // folytatás: innen számol tovább
                    if (remFrozen) {
                        remFrozen = false
                        remBaseAt = now
                    }
                }
                else -> {                                // vége, leállítva, készenlét
                    remBaseSec = -1
                    remFrozen = false
                }
            }
        }
        (map[OvenUid.ST_REMAINING] as? Number)?.toInt()?.let {
            if (op == null || op == 2 || op == 3 || op == 4) {
                remBaseSec = it
                remBaseAt = now
            }
        }
    }

    /**
     * A sütés végét két jel is mutatja: az üzemállapot "kész" lesz, vagy
     * megjön a ProgramFinished esemény. Csak akkor számít, ha ebben a
     * kapcsolatban láttuk is futni a programot – így a kapcsolódáskor talált
     * "kész" állapot nem villogtat.
     */
    private fun checkFinish(store: Store, map: Map<Int, Any>, op: Int?) {
        val ev = (map[OvenUid.EV_PROGRAM_FINISHED] as? Number)?.toInt()
        if (op == 3) {
            sawRunning = true
            finishHandled = false
        } else if (op == 0 || op == 1) {
            sawRunning = false
        }
        val finished = op == 6 || ev == 1
        if (finished && sawRunning && !finishHandled) {
            finishHandled = true
            sawRunning = false
            if (store.blinkAtEnd) {
                thread(name = "oven-blink", isDaemon = true) { blink(store, 5) }
            }
        }
    }

    /** A telefon wifi-hálózata, vagy null, ha most nincs wifin. */
    private fun findWifi(context: Context): Network? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (e: Exception) {
        null
    }

    private fun humanError(e: Exception): String {
        val t = e.message ?: e.javaClass.simpleName
        return when {
            t.contains("failed to connect", true) ->
                "A sütő nem válaszol ezen a címen ($ip). Lehet, hogy a router új IP-címet adott neki, " +
                    "vagy ki van kapcsolva a sütő wifije."

            t.contains("ECONNREFUSED", true) || t.contains("refused", true) ->
                "A sütő nem fogadja a kapcsolatot ezen a címen."
            t.contains("EHOSTUNREACH", true) || t.contains("unreachable", true) ->
                "A sütő nem érhető el. Ugyanazon a wifin vagy?"
            t.contains("timed out", true) || t.contains("timeout", true) ->
                "Időtúllépés. Ellenőrizd a sütő IP-címét, és hogy be van-e kapcsolva a hálózata."
            t.contains("handshake", true) || t.contains("alert", true) || t.contains("bad_record", true) ->
                "A titkosított kapcsolat nem jött létre. Valószínűleg hibás a kulcs."
            else -> t
        }
    }

    /** A sütő hibakódjai emberi nyelven. */
    private fun codeText(code: Int): String = when (code) {
        403 -> "a sütő nem engedi (kód 403) – nézd meg a távoli indítást és a gyerekzárat"
        404 -> "a sütő nem ismeri ezt a beállítást (kód 404)"
        409 -> "a sütő jelenlegi állapotában ez nem lehetséges (kód 409)"
        else -> "a sütő elutasította (kód $code)"
    }

    /**
     * Kiértékeli a sütő válaszát: hibát dob, ha hibakóddal felelt;
     * true, ha visszaigazolta; false, ha nem jött válasz.
     */
    private fun accepted(resp: JSONObject?): Boolean {
        if (resp == null) return false
        if (resp.has("code")) throw java.io.IOException(codeText(resp.optInt("code")))
        return true
    }

    // ------------------------------------------------------------ parancsok

    /**
     * Ha nincs kapcsolat, előbb felépíti. Megvárja azt is, hogy a sütő
     * elküldje az állapotait, különben vakon döntenénk a bekapcsolásról.
     */
    private fun ensure(store: Store): OvenSession {
        if (session?.ready != true) {
            connect(store)
            val deadline = System.currentTimeMillis() + 15_000
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                if (session?.ready == true) { ok = true; break }
                failure?.let { throw java.io.IOException(it) }
                Thread.sleep(150)
            }
            if (!ok) throw java.io.IOException("Nem sikerült kapcsolódni a sütőhöz.")
        }
        // az első állapotcsomag megvárása (legfeljebb 5 másodperc)
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && !values.containsKey(OvenUid.POWER_STATE)) {
            Thread.sleep(100)
        }
        return session ?: throw java.io.IOException("A kapcsolat megszakadt.")
    }

    private fun run(store: Store, what: String, block: (OvenSession) -> String): Result<String> =
        try {
            Result.success(block(ensure(store)))
        } catch (e: Exception) {
            Result.failure(RuntimeException("$what: ${e.message ?: "nem sikerült"}"))
        }

    fun setValue(store: Store, uid: Int, value: String, what: String): Result<String> =
        run(store, what) { s ->
            val ok = accepted(s.request("/ro/values", 1, "POST", "[{\"uid\":$uid,\"value\":$value}]"))
            if (ok) what else "$what (a sütő nem igazolta vissza)"
        }

    fun switchOn(store: Store) =
        setValue(store, OvenUid.POWER_STATE, "2", "A sütő bekapcsolva")

    fun switchOff(store: Store) =
        setValue(store, OvenUid.POWER_STATE, "3", "A sütő készenlétbe kapcsolva")

    fun enableRemoteStart(store: Store) =
        setValue(store, OvenUid.REMOTE_CONTROL_LEVEL, "2", "Távoli indítás engedélyezve")

    fun abort(store: Store): Result<String> {
        sawRunning = false                     // a kézi leállítás nem "sütés vége"
        return setValue(store, OvenUid.ABORT_PROGRAM, "true", "A program leállítva")
    }

    fun pause(store: Store) =
        setValue(store, OvenUid.PAUSE_PROGRAM, "true", "Szüneteltetve")

    fun resume(store: Store) =
        setValue(store, OvenUid.RESUME_PROGRAM, "true", "Folytatva")

    fun setTemp(store: Store, c: Int) =
        setValue(store, OvenUid.OPT_TEMPERATURE, "$c", "Hőfok: $c °C")

    fun setLevel(store: Store, n: Int) =
        setValue(store, OvenUid.OPT_LEVEL, "$n", "Grillfokozat: $n")

    fun setDurationMin(store: Store, min: Int) =
        setValue(store, OvenUid.OPT_DURATION, "${min * 60}", "Idő: $min perc")

    fun setFastPreheat(store: Store, on: Boolean) = setValue(
        store, OvenUid.OPT_FAST_PREHEAT, if (on) "true" else "false",
        if (on) "Gyors felfűtés bekapcsolva" else "Gyors felfűtés kikapcsolva"
    )

    fun setLight(store: Store, on: Boolean) = setValue(
        store, OvenUid.LIGHT_POWER, if (on) "true" else "false",
        if (on) "Világítás be" else "Világítás ki"
    )

    fun setChildLock(store: Store, on: Boolean) = setValue(
        store, OvenUid.CHILD_LOCK, if (on) "true" else "false",
        if (on) "Gyerekzár be" else "Gyerekzár ki"
    )

    fun setAlarmMin(store: Store, min: Int) =
        setValue(store, OvenUid.ALARM_CLOCK, "${min * 60}", "Időzítő: $min perc")

    /**
     * A sütőtér világítását villogtatja. A sütő alapból csak sütés közben
     * világít, ezért előbb tartós világításra kapcsoljuk, utána visszaállítjuk.
     */
    fun blink(store: Store, times: Int = 5): Result<String> = run(store, "Villogtatás") { s ->
        val wasOn = lightOn()
        runCatching {
            s.send("/ro/values", 1, "POST", "[{\"uid\":${OvenUid.CAVITY_ILLUMINATION},\"value\":0}]")
        }
        repeat(times) {
            s.send("/ro/values", 1, "POST", "[{\"uid\":${OvenUid.LIGHT_POWER},\"value\":true}]")
            Thread.sleep(450)
            s.send("/ro/values", 1, "POST", "[{\"uid\":${OvenUid.LIGHT_POWER},\"value\":false}]")
            Thread.sleep(450)
        }
        if (wasOn) {
            s.send("/ro/values", 1, "POST", "[{\"uid\":${OvenUid.LIGHT_POWER},\"value\":true}]")
        }
        "Villogtatás kész"
    }

    /**
     * Minden indítás előtt lefut: bekapcsol, és ha fut vagy szünetel valami,
     * azt leállítja – így az új indítás mindig felülírja, ami éppen megy.
     */
    private fun prepareStart(s: OvenSession) {
        if (!powerOn()) {
            accepted(s.request("/ro/values", 1, "POST", "[{\"uid\":${OvenUid.POWER_STATE},\"value\":2}]"))
            Thread.sleep(1200)
            val until = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < until && !powerOn()) Thread.sleep(150)
        }
        val op = (values[OvenUid.ST_OPERATION] as? Number)?.toInt()
        if (op == 2 || op == 3 || op == 4 || op == 5) {
            sawRunning = false
            runCatching {
                s.request("/ro/values", 1, "POST", "[{\"uid\":${OvenUid.ABORT_PROGRAM},\"value\":true}]")
            }
            // A sütő pár tized másodperc alatt ér készenlétbe; addig nem fogad új programot.
            val until = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < until) {
                val now = (values[OvenUid.ST_OPERATION] as? Number)?.toInt()
                if (now != 2 && now != 3 && now != 4 && now != 5 && now != 8) break
                Thread.sleep(200)
            }
            remBaseSec = -1
            remFrozen = false
        }
        if (!remoteStartAllowed()) {
            throw java.io.IOException(
                "a sütőn nincs engedélyezve a távoli indítás – nyomd meg a Beállításokban az engedélyező gombot"
            )
        }
    }

    /** A program indítása; hibakódnál érthető üzenettel áll meg. */
    private fun startProgram(s: OvenSession, data: String, fastPreheat: Boolean): Boolean =
        try {
            accepted(s.request("/ro/activeProgram", 1, "POST", data, 5000))
        } catch (e: java.io.IOException) {
            if (fastPreheat) {
                throw java.io.IOException("${e.message} – lehet, hogy ez a mód nem fogadja a gyors felfűtést")
            }
            throw e
        }

    /** Kézi indítás a vezérlőlapról. */
    fun startManual(
        store: Store,
        programUid: Int,
        temp: Int?,
        level: Int?,
        minutes: Int?,
        fastPreheat: Boolean
    ): Result<String> = run(store, "Indítás") { s ->
        prepareStart(s)

        val range = OvenPrograms.tempRange(programUid)
        val opts = mutableListOf<String>()
        if (OvenPrograms.isGrill(programUid)) {
            level?.let { opts.add("{\"uid\":${OvenUid.OPT_LEVEL},\"value\":$it}") }
        } else {
            temp?.let {
                opts.add("{\"uid\":${OvenUid.OPT_TEMPERATURE},\"value\":${it.coerceIn(range.first, range.last)}}")
            }
        }
        minutes?.takeIf { it > 0 }?.let {
            opts.add("{\"uid\":${OvenUid.OPT_DURATION},\"value\":${it * 60}}")
        }
        if (fastPreheat) {
            opts.add("{\"uid\":${OvenUid.OPT_FAST_PREHEAT},\"value\":true}")
        }

        val data = buildString {
            append("[{\"program\":").append(programUid)
            if (opts.isNotEmpty()) append(",\"options\":[").append(opts.joinToString(",")).append(']')
            append("}]")
        }
        val ok = startProgram(s, data, fastPreheat)
        "Elindítva: ${OvenPrograms.nameOf(programUid)}" + if (ok) "" else " (a sütő nem igazolta vissza)"
    }

    /** Egy tervfázis kiküldése: bekapcsolás, majd a program indítása a beállításaival. */
    fun sendPhase(store: Store, phase: Phase): Result<String> = run(store, "Küldés") { s ->
        val prog = OvenPrograms.uidFor(phase.futesiMod, phase.nev)
            ?: throw java.io.IOException("a \"${phase.futesiMod}\" módot nem ismerem fel")

        prepareStart(s)

        val range = OvenPrograms.tempRange(prog)
        val opts = mutableListOf<String>()
        var tempNote = ""
        if (OvenPrograms.isGrill(prog)) {
            OvenPrograms.level(phase.homerseklet)?.let {
                opts.add("{\"uid\":${OvenUid.OPT_LEVEL},\"value\":$it}")
            }
        } else {
            OvenPrograms.temperature(phase.homerseklet)?.let { t ->
                // A terv néha a mód tartományán kívüli hőfokot ad; azt a sütő elutasítaná.
                val c = t.coerceIn(range.first, range.last)
                if (c != t) tempNote = " (a $t °C helyett $c °C, ennyit enged ez a mód)"
                opts.add("{\"uid\":${OvenUid.OPT_TEMPERATURE},\"value\":$c}")
            }
        }
        if (phase.idoPerc > 0) {
            opts.add("{\"uid\":${OvenUid.OPT_DURATION},\"value\":${phase.idoPerc * 60}}")
        }
        val fast = prog == OvenPrograms.PREHEATING
        if (fast) {
            opts.add("{\"uid\":${OvenUid.OPT_FAST_PREHEAT},\"value\":true}")
        }

        val data = buildString {
            append("[{\"program\":").append(prog)
            if (opts.isNotEmpty()) append(",\"options\":[").append(opts.joinToString(",")).append(']')
            append("}]")
        }
        val ok = startProgram(s, data, fast)
        "Elindítva: ${phase.futesiMod}, ${phase.homerseklet}$tempNote" +
            if (ok) "" else " (a sütő nem igazolta vissza)"
    }
}
