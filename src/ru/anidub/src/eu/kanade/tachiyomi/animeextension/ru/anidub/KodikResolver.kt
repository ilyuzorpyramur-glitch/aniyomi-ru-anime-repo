package eu.kanade.tachiyomi.animeextension.ru.anidub

import android.content.SharedPreferences
import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import app.cash.quickjs.QuickJs
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
 * POST /ftor отдаёт ссылки на потоки. Сами ссылки закодированы скриптом плеера,
 * поэтому декодируем его же кодом через QuickJs.
 */
class KodikResolver(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videos(playerUrl: String): List<Video> {
        val page = runCatching {
            client.newCall(GET(playerUrl, kodikHeaders())).awaitSuccess().bodyString()
        }.getOrNull() ?: return emptyList()

        val params = URL_PARAMS_REGEX.find(page)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyList()

        val pd = params.optString("pd")
        if (pd.isBlank()) return emptyList()

        val parts = playerUrl.split('/')
        if (parts.size < 6) return emptyList()

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
        }.getOrNull() ?: return emptyList()

        val links = data.optJSONObject("links") ?: return emptyList()
        val scriptUrl = SCRIPT_REGEX.find(page)?.groupValues?.get(1) ?: return emptyList()
        val js = runCatching {
            client.newCall(GET(scriptUrl, kodikHeaders())).awaitSuccess().bodyString()
        }.getOrNull() ?: return emptyList()

        val encoder = extractEncoder(js) ?: return emptyList()
        val preferred = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        val qualities = listOf("720", "480", "360").sortedByDescending { it == preferred }
        return qualities.flatMap { quality ->
            val src = links.optJSONArray(quality)?.optJSONObject(0)?.optString("src").orEmpty()
            if (src.isBlank()) return@flatMap emptyList()
            val decoded = decode(src, encoder) ?: return@flatMap emptyList()
            playlistUtils.extractFromHls(decoded, videoNameGen = { "AniDub ($quality p Kodik)" })
        }.distinctBy { it.videoUrl }
    }

    private fun decode(src: String, encoder: String): String? = runCatching {
        val quickJs = QuickJs.create()
        val base64 = quickJs.use { it.evaluate("t='$src'; $encoder") }.toString()
        val url = Base64.decode(base64, Base64.DEFAULT).toString(Charsets.UTF_8)
        if (url.startsWith("//")) "https:$url" else url
    }.getOrNull()

    /** Вытаскиваем из скрипта плеера функцию-кодировщик целиком, по балансу скобок. */
    private fun extractEncoder(js: String): String? {
        val atob = ATOB_REGEX.find(js) ?: return null
        val deque = ArrayDeque<Char>()
        var out = ""
        deque.addFirst('(')
        for (i in atob.range.last until js.length) {
            val char = js[i]
            when (char) {
                '(', '{' -> deque.addFirst(char)
                ')', '}' -> if (deque.isNotEmpty()) deque.removeFirst()
            }
            if (deque.isNotEmpty()) out += char else break
        }
        return out.ifBlank { null }
    }

    private fun kodikHeaders(): Headers = headers.newBuilder()
        .set("Referer", "https://anidub.vip/")
        .set("Origin", "https://anidub.vip")
        .build()

    companion object {
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"

        private val URL_PARAMS_REGEX = Regex("""urlParams = '([^']+)'""")
        private val SCRIPT_REGEX = Regex("""src="([^"]*player_single[^"]*)"""")
        private val ATOB_REGEX = Regex("""atob\(""")
    }
}
