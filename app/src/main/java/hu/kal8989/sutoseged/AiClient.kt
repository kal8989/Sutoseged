package hu.kal8989.sutoseged

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

enum class Provider(val label: String) {
    GEMINI("Gemini"),
    CLAUDE("Claude")
}

data class AiConfig(
    val provider: Provider,
    val apiKey: String,
    val model: String
)

object AiClient {

    suspend fun plan(cfg: AiConfig, userPrompt: String): Result<Plan> =
        withContext(Dispatchers.IO) {
            try {
                if (cfg.apiKey.isBlank()) {
                    throw IllegalStateException(
                        "Nincs megadva ${cfg.provider.label} API-kulcs. Nyisd meg a Beállításokat."
                    )
                }
                val raw = when (cfg.provider) {
                    Provider.GEMINI -> callGemini(cfg, userPrompt)
                    Provider.CLAUDE -> callClaude(cfg, userPrompt)
                }.getOrThrow()
                Result.success(Plan.from(JSONObject(stripFences(raw))))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ------------------------------------------------------------ Gemini

    private fun callGemini(cfg: AiConfig, userPrompt: String): Result<String> {
        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                )
            )
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", OvenKnowledge.SYSTEM_PROMPT))
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.4)
                    .put("responseMimeType", "application/json")
            )
        }

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:generateContent"
        )
        return post(url, body, mapOf("x-goog-api-key" to cfg.apiKey)) { text ->
            val parts = JSONObject(text)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                }
            }
            sb.toString()
        }
    }

    // ------------------------------------------------------------ Claude

    private fun callClaude(cfg: AiConfig, userPrompt: String): Result<String> {
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("max_tokens", 4000)
            put("temperature", 0.4)
            put("system", OvenKnowledge.SYSTEM_PROMPT)
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userPrompt)
                )
            )
        }

        val url = URL("https://api.anthropic.com/v1/messages")
        val headers = mapOf(
            "x-api-key" to cfg.apiKey,
            "anthropic-version" to "2023-06-01"
        )
        return post(url, body, headers) { text ->
            val content = JSONObject(text).optJSONArray("content")
            val sb = StringBuilder()
            if (content != null) {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i)
                    if (block?.optString("type") == "text") {
                        sb.append(block.optString("text"))
                    }
                }
            }
            sb.toString()
        }
    }

    // ------------------------------------------------------------ közös

    private fun post(
        url: URL,
        body: JSONObject,
        headers: Map<String, String>,
        extract: (String) -> String
    ): Result<String> {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""

            if (code !in 200..299) {
                val msg = runCatching {
                    val err = JSONObject(text).optJSONObject("error")
                    err?.optString("message")?.takeIf { it.isNotBlank() }
                }.getOrNull() ?: text.take(300)
                return Result.failure(RuntimeException("Hiba ($code): $msg"))
            }

            val out = extract(text)
            if (out.isBlank()) {
                Result.failure(RuntimeException("A modell üres választ adott."))
            } else {
                Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    /** Ha a modell ```json kerítést tesz a válasz köré, azt leszedjük. */
    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.removeSuffix("```").trim()
        }
        val first = t.indexOf('{')
        val last = t.lastIndexOf('}')
        return if (first >= 0 && last > first) t.substring(first, last + 1) else t
    }
}
