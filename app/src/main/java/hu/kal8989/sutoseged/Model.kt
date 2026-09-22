package hu.kal8989.sutoseged

import org.json.JSONArray
import org.json.JSONObject

data class Phase(
    val nev: String,
    val futesiMod: String,
    val homerseklet: String,
    val idoPerc: Int,
    val szint: String,
    val tartozek: String,
    val teendo: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("nev", nev)
        put("futesiMod", futesiMod)
        put("homerseklet", homerseklet)
        put("idoPerc", idoPerc)
        put("szint", szint)
        put("tartozek", tartozek)
        put("teendo", teendo)
    }

    companion object {
        fun from(o: JSONObject) = Phase(
            nev = o.optString("nev"),
            futesiMod = o.optString("futesiMod"),
            homerseklet = o.optString("homerseklet"),
            idoPerc = o.optInt("idoPerc", 0),
            szint = o.optString("szint"),
            tartozek = o.optString("tartozek"),
            teendo = o.optString("teendo")
        )
    }
}

data class Ingredient(val nev: String, val mennyiseg: String) {
    fun toJson(): JSONObject = JSONObject().put("nev", nev).put("mennyiseg", mennyiseg)

    companion object {
        fun from(o: JSONObject) = Ingredient(o.optString("nev"), o.optString("mennyiseg"))
    }
}

data class Plan(
    val cim: String,
    val osszefoglalo: String,
    val hozzavalok: List<Ingredient>,
    val receptJavitasok: List<String>,
    val elokeszites: List<String>,
    val fazisok: List<Phase>,
    val maghomerseklet: String,
    val pihentetes: String,
    val magyarazat: String,
    val figyelmeztetes: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cim", cim)
        put("osszefoglalo", osszefoglalo)
        put("hozzavalok", JSONArray(hozzavalok.map { it.toJson() }))
        put("receptJavitasok", JSONArray(receptJavitasok))
        put("elokeszites", JSONArray(elokeszites))
        put("fazisok", JSONArray(fazisok.map { it.toJson() }))
        put("maghomerseklet", maghomerseklet)
        put("pihentetes", pihentetes)
        put("magyarazat", magyarazat)
        put("figyelmeztetes", figyelmeztetes)
    }

    companion object {
        fun from(o: JSONObject): Plan {
            val prep = mutableListOf<String>()
            o.optJSONArray("elokeszites")?.let { arr ->
                for (i in 0 until arr.length()) prep.add(arr.optString(i))
            }
            val ingredients = mutableListOf<Ingredient>()
            o.optJSONArray("hozzavalok")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { ingredients.add(Ingredient.from(it)) }
                }
            }
            val fixes = mutableListOf<String>()
            o.optJSONArray("receptJavitasok")?.let { arr ->
                for (i in 0 until arr.length()) fixes.add(arr.optString(i))
            }
            val phases = mutableListOf<Phase>()
            o.optJSONArray("fazisok")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { phases.add(Phase.from(it)) }
                }
            }
            return Plan(
                cim = o.optString("cim", "Sütési terv"),
                osszefoglalo = o.optString("osszefoglalo"),
                hozzavalok = ingredients,
                receptJavitasok = fixes,
                elokeszites = prep,
                fazisok = phases,
                maghomerseklet = o.optString("maghomerseklet"),
                pihentetes = o.optString("pihentetes"),
                magyarazat = o.optString("magyarazat"),
                figyelmeztetes = o.optString("figyelmeztetes")
            )
        }
    }
}

/** Egy mentett terv a hozzá tartozó bemenettel és a saját jegyzeteiddel. */
data class SavedPlan(
    val id: Long,
    val createdAt: Long,
    val inputSummary: String,
    val plan: Plan,
    val notes: List<String>,
    val photoPath: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("inputSummary", inputSummary)
        put("plan", plan.toJson())
        put("notes", JSONArray(notes))
        put("photoPath", photoPath ?: "")
    }

    companion object {
        fun from(o: JSONObject): SavedPlan {
            val notes = mutableListOf<String>()
            o.optJSONArray("notes")?.let { arr ->
                for (i in 0 until arr.length()) notes.add(arr.optString(i))
            }
            val photo = o.optString("photoPath", "")
            return SavedPlan(
                id = o.optLong("id", System.currentTimeMillis()),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                inputSummary = o.optString("inputSummary"),
                plan = Plan.from(o.optJSONObject("plan") ?: JSONObject()),
                notes = notes,
                photoPath = photo.ifBlank { null }
            )
        }
    }
}

/** A tervezőlap bemenete. */
data class PlanRequest(
    val etel: String,
    val darabszam: String,
    val osszsulyG: String,
    val vastagsagCm: String,
    val strategia: String,
    val megjegyzes: String
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts.add(etel)
        if (darabszam.isNotBlank()) parts.add("$darabszam db")
        if (osszsulyG.isNotBlank()) parts.add("$osszsulyG g")
        if (vastagsagCm.isNotBlank()) parts.add("$vastagsagCm cm")
        parts.add(strategia)
        return parts.joinToString(" · ")
    }

    fun toUserPrompt(): String = buildString {
        appendLine("Étel / nyersanyag: $etel")
        if (darabszam.isNotBlank()) appendLine("Darabszám: $darabszam")
        if (osszsulyG.isNotBlank()) appendLine("Összsúly: $osszsulyG g")
        if (vastagsagCm.isNotBlank()) appendLine("A legvastagabb rész: $vastagsagCm cm")
        appendLine("Kívánt eredmény / stratégia: $strategia")
        if (megjegyzes.isNotBlank()) appendLine("A felhasználó receptje / hozzávalói / megjegyzése: $megjegyzes")
        appendLine()
        appendLine("Készíts ehhez fázistervet a fenti szabályok szerint, JSON-ban.")
    }
}

object Strategies {
    val all = listOf(
        "Gyors – a lényeg, hogy hamar kész legyen",
        "Pecsenye – ropogós kéreg, pirult felület",
        "Lassú sütés – puha, szétomló, kollagén lebontva",
        "Grill – erős felső hő, gyors pirítás",
        "Kímélő párolás – alacsony hő, rozé/szaftos",
        "Air Fry – ropogós, kevés zsírral",
        "Sütemény / tészta"
    )
}

object FoodPresets {
    private val items = listOf(
        "Báránycomb",
        "Bőrös sertéssült (lapocka, csülök)",
        "Burgonya, sült",
        "Csirke, darabolt (comb, felsőcomb)",
        "Csirke, egész",
        "Csirkemell filé",
        "Csirkeszárny",
        "Hal egészben – pisztráng",
        "Halfilé – fehér húsú (tőkehal, süllő)",
        "Halfilé – harcsa",
        "Halfilé – lazac",
        "Kacsacomb",
        "Kacsamell",
        "Marha hátszín",
        "Marha lábszár / párolt sült",
        "Marhafilé",
        "Pulykamell",
        "Sertéskaraj",
        "Sertésszűz",
        "Sertéstarja",
        "Sütemény / tészta",
        "Zöldség, sült"
    )

    /** Ábécé sorrend magyar szabály szerint, az "Egyéb" mindig a lista végén. */
    val all: List<String> =
        items.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("hu"))) { it }) +
            "Egyéb (írd be)"
}
