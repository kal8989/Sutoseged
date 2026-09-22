package hu.kal8989.sutoseged

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val apkUrl: String?,
    val releaseUrl: String
) {
    val isNewer: Boolean
        get() = compareVersions(latestVersion, currentVersion) > 0
}

object Updater {

    /** A repó, ahonnan a frissítés jön. Ha átnevezed, itt kell átírni. */
    const val REPO = "kal8989/Sutoseged"

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    suspend fun check(context: Context): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$REPO/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Sutoseged")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            conn.disconnect()

            if (code == 404) {
                return@withContext Result.failure(
                    RuntimeException("Még nincs közzétett kiadás a repóban.")
                )
            }
            if (code !in 200..299) {
                return@withContext Result.failure(
                    RuntimeException("GitHub hiba ($code)")
                )
            }

            val json = JSONObject(text)
            val tag = json.optString("tag_name").removePrefix("v")
            val htmlUrl = json.optString("html_url")
            var apk: String? = null
            json.optJSONArray("assets")?.let { assets ->
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }

            Result.success(
                UpdateInfo(
                    latestVersion = tag.ifBlank { "?" },
                    currentVersion = currentVersion(context),
                    apkUrl = apk,
                    releaseUrl = htmlUrl
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** 1.0.12 vs 1.0.9 típusú összehasonlítás, számonként. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}
