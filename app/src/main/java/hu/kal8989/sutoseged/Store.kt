package hu.kal8989.sutoseged

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Store(context: Context) {

    /** Az alkalmazás környezete (pl. a wifi-hálózat kereséséhez). */
    val appContext: Context = context.applicationContext

    private val prefs = context.getSharedPreferences("sutoseged", Context.MODE_PRIVATE)
    private val plansFile = File(context.filesDir, "plans.json")
    val photoDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    var provider: Provider
        get() = runCatching {
            Provider.valueOf(prefs.getString("provider", Provider.GEMINI.name)!!)
        }.getOrDefault(Provider.GEMINI)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    var geminiKey: String
        get() = prefs.getString("geminiKey", prefs.getString("apiKey", "") ?: "") ?: ""
        set(v) = prefs.edit().putString("geminiKey", v).apply()

    var geminiModel: String
        get() = prefs.getString("geminiModel", DEFAULT_GEMINI) ?: DEFAULT_GEMINI
        set(v) = prefs.edit().putString("geminiModel", v).apply()

    var claudeKey: String
        get() = prefs.getString("claudeKey", "") ?: ""
        set(v) = prefs.edit().putString("claudeKey", v).apply()

    var claudeModel: String
        get() = prefs.getString("claudeModel", DEFAULT_CLAUDE) ?: DEFAULT_CLAUDE
        set(v) = prefs.edit().putString("claudeModel", v).apply()

    fun aiConfig(): AiConfig = when (provider) {
        Provider.GEMINI -> AiConfig(Provider.GEMINI, geminiKey, geminiModel)
        Provider.CLAUDE -> AiConfig(Provider.CLAUDE, claudeKey, claudeModel)
    }

    // ------------------------------------------------------------ sütő a helyi hálózaton

    var ovenIp: String
        get() = prefs.getString("ovenIp", "") ?: ""
        set(v) = prefs.edit().putString("ovenIp", v).apply()

    var ovenPsk: String
        get() = prefs.getString("ovenPsk", "") ?: ""
        set(v) = prefs.edit().putString("ovenPsk", v).apply()

    /** A sütő eszközlistájában ezen a néven jelenik meg az app; telepítésenként állandó. */
    val ovenDeviceId: String
        get() {
            prefs.getString("ovenDeviceId", null)?.let { return it }
            val id = java.util.Random().nextInt().toLong().and(0xFFFFFFFFL).toString(16).padStart(8, '0')
            prefs.edit().putString("ovenDeviceId", id).apply()
            return id
        }

    val ovenConfigured: Boolean
        get() = ovenIp.isNotBlank() && ovenPsk.isNotBlank()

    /** Villogjon-e a sütőtér világítása, amikor a program véget ér. */
    var blinkAtEnd: Boolean
        get() = prefs.getBoolean("blinkAtEnd", true)
        set(v) = prefs.edit().putBoolean("blinkAtEnd", v).apply()

    // ------------------------------------------------------------ űrlap és utolsó terv
    // Minden a telefon tárhelyére kerül, hogy az app kilövése után is megmaradjon.

    fun getForm(key: String, default: String): String =
        prefs.getString("form_$key", default) ?: default

    fun putForm(key: String, value: String) {
        prefs.edit().putString("form_$key", value).apply()
    }

    /** idle / running / done / error */
    var planStatus: String
        get() = prefs.getString("planStatus", "idle") ?: "idle"
        set(v) = prefs.edit().putString("planStatus", v).commit().let { }

    var planError: String
        get() = prefs.getString("planError", "") ?: ""
        set(v) = prefs.edit().putString("planError", v).commit().let { }

    private val lastPlanFile = File(context.filesDir, "last_plan.json")

    fun saveLastPlan(plan: Plan?) {
        if (plan == null) lastPlanFile.delete()
        else lastPlanFile.writeText(plan.toJson().toString())
    }

    fun loadLastPlan(): Plan? = try {
        if (lastPlanFile.exists()) Plan.from(org.json.JSONObject(lastPlanFile.readText())) else null
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------ futó időzítők

    fun loadTimers(): Map<String, Long> = try {
        val o = org.json.JSONObject(prefs.getString("timers", "{}") ?: "{}")
        val out = mutableMapOf<String, Long>()
        o.keys().forEach { k -> out[k] = o.optLong(k) }
        out
    } catch (e: Exception) {
        emptyMap()
    }

    fun saveTimers(timers: Map<String, Long>) {
        val o = org.json.JSONObject()
        timers.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString("timers", o.toString()).apply()
    }

    // ------------------------------------------------------------ tervek

    fun loadPlans(): List<SavedPlan> {
        if (!plansFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(plansFile.readText())
            val out = mutableListOf<SavedPlan>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(SavedPlan.from(it)) }
            }
            out.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePlans(plans: List<SavedPlan>) {
        val arr = JSONArray()
        plans.forEach { arr.put(it.toJson()) }
        plansFile.writeText(arr.toString())
    }

    fun addPlan(inputSummary: String, plan: Plan): SavedPlan {
        val saved = SavedPlan(
            id = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            inputSummary = inputSummary,
            plan = plan,
            notes = emptyList(),
            photos = emptyList()
        )
        savePlans(listOf(saved) + loadPlans())
        return saved
    }

    fun updatePlan(updated: SavedPlan) {
        savePlans(loadPlans().map { if (it.id == updated.id) updated else it })
    }

    fun deletePlan(id: Long) {
        val plans = loadPlans()
        plans.firstOrNull { it.id == id }?.photos?.forEach { runCatching { File(it).delete() } }
        savePlans(plans.filter { it.id != id })
    }

    // ------------------------------------------------------------ megosztás másik telefonnal

    /**
     * A beállítások egyetlen szöveges kódban: AI-szolgáltató, kulcsok, modellek, a sütő
     * címe és kulcsa; kérésre a mentett tervek is (fényképek nélkül).
     */
    fun exportCode(withPlans: Boolean): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("provider", provider.name)
        o.put("geminiKey", geminiKey)
        o.put("geminiModel", geminiModel)
        o.put("claudeKey", claudeKey)
        o.put("claudeModel", claudeModel)
        o.put("ovenIp", ovenIp)
        o.put("ovenPsk", ovenPsk)
        if (withPlans) {
            o.put("plans", JSONArray(loadPlans().map { it.copy(photos = emptyList()).toJson() }))
        }
        val b64 = Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return EXPORT_PREFIX + b64
    }

    /** Betölti a másik telefonról kapott kódot; visszaadja, mi került be. Hibás kódnál kivételt dob. */
    fun importCode(text: String): String {
        val start = text.indexOf(EXPORT_PREFIX)
        require(start >= 0) { "Ez nem Sütősegéd-kód. A kódnak így kell kezdődnie: $EXPORT_PREFIX" }
        val b64 = text.substring(start + EXPORT_PREFIX.length)
            .takeWhile { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it.isWhitespace() }
            .filter { !it.isWhitespace() }
        val o = JSONObject(String(Base64.decode(b64, Base64.NO_WRAP), Charsets.UTF_8))

        o.optString("provider").takeIf { it.isNotBlank() }?.let { p ->
            runCatching { provider = Provider.valueOf(p) }
        }
        o.optString("geminiKey").takeIf { it.isNotBlank() }?.let { geminiKey = it }
        o.optString("geminiModel").takeIf { it.isNotBlank() }?.let { geminiModel = it }
        o.optString("claudeKey").takeIf { it.isNotBlank() }?.let { claudeKey = it }
        o.optString("claudeModel").takeIf { it.isNotBlank() }?.let { claudeModel = it }
        o.optString("ovenIp").takeIf { it.isNotBlank() }?.let { ovenIp = it }
        o.optString("ovenPsk").takeIf { it.isNotBlank() }?.let { ovenPsk = it }

        var added = 0
        o.optJSONArray("plans")?.let { arr ->
            val existing = loadPlans()
            val ids = existing.map { it.id }.toSet()
            val incoming = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { j -> runCatching { SavedPlan.from(j) }.getOrNull() }
            }.filter { it.id !in ids }
            if (incoming.isNotEmpty()) {
                savePlans((incoming + existing).sortedByDescending { it.createdAt })
            }
            added = incoming.size
        }
        return "Betöltve: AI-beállítások és a sütő adatai" +
            if (added > 0) ", valamint $added mentett terv." else "."
    }

    fun newPhotoFile(): File = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")

    companion object {
        const val EXPORT_PREFIX = "SUTOSEGED1:"
        const val DEFAULT_GEMINI = "gemini-3.6-flash"
        const val DEFAULT_CLAUDE = "claude-sonnet-4-6"

        val GEMINI_MODELS = listOf(
            "gemini-3.6-flash",
            "gemini-3.6-pro",
            "gemini-2.0-flash"
        )
        val CLAUDE_MODELS = listOf(
            "claude-sonnet-4-6",
            "claude-opus-4-1",
            "claude-haiku-4-5"
        )
    }
}
