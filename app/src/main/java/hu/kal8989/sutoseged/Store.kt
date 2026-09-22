package hu.kal8989.sutoseged

import android.content.Context
import org.json.JSONArray
import java.io.File

class Store(context: Context) {

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
            photoPath = null
        )
        savePlans(listOf(saved) + loadPlans())
        return saved
    }

    fun updatePlan(updated: SavedPlan) {
        savePlans(loadPlans().map { if (it.id == updated.id) updated else it })
    }

    fun deletePlan(id: Long) {
        val plans = loadPlans()
        plans.firstOrNull { it.id == id }?.photoPath?.let { runCatching { File(it).delete() } }
        savePlans(plans.filter { it.id != id })
    }

    fun newPhotoFile(): File = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")

    companion object {
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
