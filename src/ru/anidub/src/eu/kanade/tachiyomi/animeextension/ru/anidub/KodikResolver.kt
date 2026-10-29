package eu.kanade.tachiyomi.animeextension.ru.anidub

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Kodik: страница плеера содержит подписанные параметры (`urlParams`), по ним
 * POST /ftor отдаёт ссылки на потоки. Ссылки закодированы сдвигом букв на 18
 * и base64 — раскодируем тем же алгоритмом, без JS-движка.
 */
class KodikResolver(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    suspend fun videos(playerUrl: String): List<Video> {
        val page = runCatching {
            client.newCall(GET(playerUrl, kodikHeaders())).awaitSuccess().bodyString()
        }.getOrNull() ?: run {
            Log.e("AniDub", "kodik: page fetch failed")
            return emptyList()
        }

        Log.e("AniDub", "kodik page len=" + page.length)

        val params = URL_PARAMS_REGEX.find(page)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: run {
                Log.e("AniDub", "kodik: urlParams not found")
                return emptyList()
            }

        val pd = params.optString("pd")
        Log.e("AniDub", "kodik pd=" + pd)
        val parts = playerUrl.split('/')
        if (pd.isBlank() || parts.size < 6) return emptyList()

        val body = FormBody.Builder()
            .add("d", params.optString("d"))
            .add("d_sign", params.optString("d_sign"))
            .add("pd", pd)
            .add("pd_sign", params.optString("pd_sign"))
            .add("ref", params.optString("ref"))
            .add("ref_sign", params.optString("ref_sign"))
            .add("type", parts[3])
            .add("id", parts[4])
            .add("hash", parts[5].substringBefore('?'))
            .build()

        val request = Request.Builder()
            .url("https://$pd/ftor")
            .post(body)
            .headers(kodikHeaders())
            .build()

        val data = runCatching {
            JSONObject(client.newCall(request).awaitSuccess().bodyString())
        }.getOrNull() ?: run {
            Log.e("AniDub", "kodik: ftor failed")
            return emptyList()
        }

        val links = data.optJSONObject("links") ?: run {
            Log.e("AniDub", "kodik: no links in ftor")
            return emptyList()
        }
        Log.e("AniDub", "kodik links keys=" + links.keys().asSequence().toList())
        val preferred = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return listOf("720", "480", "360")
            .sortedByDescending { it == preferred }
            .flatMap { quality ->
                val src = links.optJSONArray(quality)?.optJSONObject(0)?.optString("src").orEmpty()
                if (src.isBlank()) return@flatMap emptyList()
                val url = decode(src) ?: run {
                    Log.e("AniDub", "kodik: decode failed q=$quality")
                    return@flatMap emptyList()
                }
                Log.e("AniDub", "kodik url=" + url.take(80))
                runCatching {
                    playlistUtils.extractFromHls(url, videoNameGen = { "AniDub (${quality}p Kodik)" })
                }.getOrDefault(emptyList())
            }
            .distinctBy { it.videoUrl }
    }

    /** Сдвиг букв на 18 внутри регистра + base64 → прямой адрес плейлиста. */
    private fun decode(src: String): String? = runCatching {
        val shifted = buildString(src.length) {
            src.forEach { ch ->
                if (ch in 'a'..'z' || ch in 'A'..'Z') {
                    val max = if (ch <= 'Z') 'Z'.code else 'z'.code
                    val code = ch.code + 18
                    append((if (max >= code) code else code - 26).toChar())
                } else {
                    append(ch)
                }
            }
        }
        val padded = shifted + "=".repeat((4 - shifted.length % 4) % 4)
        val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        if (decoded.startsWith("//")) "https:$decoded" else decoded
    }.getOrNull()

    private fun kodikHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://anidub.vip/")
        .set("Origin", "https://anidub.vip")
        .build()

    companion object {
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"

        private val URL_PARAMS_REGEX = Regex("""urlParams = '([^']+)'""")
    }
}
