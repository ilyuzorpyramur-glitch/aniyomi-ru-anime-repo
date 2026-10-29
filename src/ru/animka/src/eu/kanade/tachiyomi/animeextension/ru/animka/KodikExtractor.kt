package eu.kanade.tachiyomi.animeextension.ru.animka

import android.content.SharedPreferences
import android.net.Uri
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Kodik: страница плеера подписана (`urlParams`), переводы и серии отдаются в `<select>`,
 * ссылки на потоки приходят из POST /ftor и закодированы сдвигом букв на 18 + base64.
 */
class KodikExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
    private val preferences: SharedPreferences,
) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private data class Translation(
        val id: String,
        val title: String,
        val mediaType: String,
        val mediaId: String,
        val mediaHash: String,
        val kind: String,
    )

    suspend fun videos(playerUrl: String, season: Int, episode: Int): List<Video> {
        val page = fetch(playerUrl)
        val params = URL_PARAMS_REGEX.find(page)?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (params == null) {
            Log.e(TAG, "urlParams не найдены")
            return emptyList()
        }

        val doc = Jsoup.parse(page, playerUrl)
        val translations = parseTranslations(doc)
        Log.e(
            TAG,
            "kodik page=" + page.length + " translations=" + translations.size +
                " selects=" + doc.select("select").size + " options=" + doc.select("option").size +
                " hasMediaId=" + page.contains("data-media-id") +
                " sample=" + doc.select("select").firstOrNull()?.select("option")?.firstOrNull()?.outerHtml()?.take(120),
        )
        if (translations.isEmpty()) {
            Log.e(TAG, "озвучки не найдены")
            return emptyList()
        }

        val episodeOption = doc.select("select option[data-id][data-hash]")
            .firstOrNull { it.attr("value") == episode.toString() && it.hasAttr("data-hash") }

        val ignoreSubs = preferences.getBoolean(PREF_IGNORE_SUBS, true)
        Log.e(TAG, "kodik episodes options=" + doc.select("select option[data-id][data-hash]").size)
        Log.e(TAG, "kodik keep=" + translations.joinToString { it.title + "/" + it.mediaType + "/" + it.id })
        val wanted = translations
            .filter { !ignoreSubs || !it.kind.contains("sub", ignoreCase = true) }
            .filter { matchesPreference(it.title) }
            .ifEmpty { translations }

        val qualities = preferredQualities()
        val videos = mutableListOf<Video>()

        for (translation in wanted) {
            val isSerial = translation.mediaType != "video" && episodeOption != null
            val id = if (isSerial) episodeOption!!.attr("data-id") else translation.mediaId
            val hash = if (isSerial) episodeOption!!.attr("data-hash") else translation.mediaHash
            if (id.isBlank() || hash.isBlank()) continue

            val links = requestLinks(params, translation, id, hash, isSerial)
            Log.e(TAG, "kodik ftor " + translation.title + " id=" + id + " type=" + if (isSerial) "seria" else "video" + " -> " + (links?.keys()?.asSequence()?.toList() ?: "null"))
            if (links == null) continue
            val sorted = qualities.filter { links.has(it) }.ifEmpty { listOfNotNull(links.keys().asSequence().maxOrNull()) }
            for (quality in sorted) {
                val src = links.optJSONArray(quality)?.optJSONObject(0)?.optString("src").orEmpty()
                if (src.isBlank()) continue
                val url = decode(src) ?: continue
                val label = "${translation.title} ${quality}p"
                val extracted = runCatching {
                    playlistUtils.extractFromHls(url, videoNameGen = { label })
                }.getOrDefault(emptyList())
                if (extracted.isNotEmpty()) {
                    videos += extracted
                } else {
                    videos += Video(url, label, url, headers = kodikHeaders())
                }
            }
        }

        return videos.distinctBy { it.videoUrl }
    }

    private suspend fun fetch(url: String): String = runCatching {
        client.newCall(GET(url, kodikHeaders())).awaitSuccess().bodyString()
    }.getOrDefault("")

    private fun parseTranslations(doc: Document): List<Translation> = doc.select("select option[data-media-id]").mapNotNull { option ->
        val id = option.attr("data-id").ifBlank { option.attr("value") }
        val mediaId = option.attr("data-media-id")
        if (id.isBlank() || mediaId.isBlank()) return@mapNotNull null
        Translation(
            id = id,
            title = option.attr("data-title").ifBlank { "Озвучка $id" },
            mediaType = option.attr("data-media-type"),
            mediaId = mediaId,
            mediaHash = option.attr("data-media-hash"),
            kind = option.attr("data-translation-type"),
        )
    }

    private suspend fun requestLinks(
        params: JSONObject,
        translation: Translation,
        id: String,
        hash: String,
        isSerial: Boolean,
    ): JSONObject? {
        val pd = params.optString("pd").ifBlank { return null }
        val body = FormBody.Builder()
            .add("d", params.optString("d"))
            .add("d_sign", Uri.decode(params.optString("d_sign")))
            .add("pd", pd)
            .add("pd_sign", Uri.decode(params.optString("pd_sign")))
            .add("ref", Uri.decode(params.optString("ref")))
            .add("ref_sign", Uri.decode(params.optString("ref_sign")))
            .add("type", if (isSerial) "seria" else "video")
            .add("id", id)
            .add("hash", hash)
            .add("translation_id", translation.id)
            .build()

        val request = Request.Builder()
            .url("https://$pd/ftor")
            .post(body)
            .headers(kodikHeaders())
            .build()

        val data = runCatching {
            JSONObject(client.newCall(request).awaitSuccess().bodyString())
        }.getOrNull() ?: return null
        return data.optJSONObject("links")
    }

    private fun preferredQualities(): List<String> {
        val available = listOf("1080", "720", "480", "360", "240")
        val useMax = preferences.getBoolean(PREF_USE_MAX_QUALITY, true)
        if (useMax) return available
        val chosen = preferences.getStringSet(PREF_QUALITY, emptySet()).orEmpty()
        val sorted = available.filter { it in chosen }
        return sorted + available.filter { it !in chosen }
    }

    private fun matchesPreference(title: String): Boolean {
        val raw = preferences.getString(PREF_DUB_TEAM, "").orEmpty()
        if (raw.isBlank()) return true
        val keywords = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return true
        val lower = title.lowercase()
        return keywords.any { lower.contains(it) }
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
        .set("Referer", "$baseUrl/")
        .set("Origin", "https://kodikplayer.com")
        .build()

    companion object {
        private const val TAG = "Animka"
        const val PREF_USE_MAX_QUALITY = "pref_use_max_quality"
        const val PREF_QUALITY = "pref_quality"
        const val PREF_IGNORE_SUBS = "pref_ignore_subs"
        const val PREF_DUB_TEAM = "pref_dub_team"
        private val URL_PARAMS_REGEX = Regex("""urlParams\s*=\s*'([^']+)'""")
    }
}
