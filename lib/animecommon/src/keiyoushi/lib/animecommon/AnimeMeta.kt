package keiyoushi.lib.animecommon

/**
 * Единый разбор метаданных тайтла для всех расширений репозитория.
 *
 * Сайты пишут статус, оценку и год по-разному, но словарь у них общий.
 * Держать разбор в одном месте выгодно: правило правки одно, поведение
 * одинаковое, и «Завершено» больше не появляется там, где тайтл выходит.
 */
object AnimeMeta {

    /** Статусы SAnime: 0 неизвестно, 1 выходит, 2 завершено, 3 лицензия. */
    const val UNKNOWN = 0
    const val ONGOING = 1
    const val COMPLETED = 2
    const val LICENSED = 3
    const val PUBLISHING_FINISHED = 4
    const val CANCELLED = 5
    const val ON_HIATUS = 6

    private val ONGOING_WORDS = listOf(
        "онгоинг",
        "выходит",
        "продолжается",
        "сейчас выходит",
        "ongoing",
        "currently airing",
        "airing",
        "анонс",
        "анонсирован",
        "ожидается",
    )

    private val COMPLETED_WORDS = listOf(
        "завершен",
        "завершён",
        "вышло полностью",
        "completed",
        "finished",
    )

    private val HIATUS_WORDS = listOf(
        "приостановлен",
        "заморожен",
        "перерыв",
        "hiatus",
        "on hold",
    )

    private val CANCELLED_WORDS = listOf(
        "отменен",
        "отменён",
        "cancelled",
        "canceled",
    )

    /** Статус по тексту страницы. Неизвестное — честное «неизвестно». */
    fun statusOf(raw: String?): Int {
        val text = raw?.lowercase()?.trim().orEmpty()
        if (text.isEmpty()) return UNKNOWN
        if (CANCELLED_WORDS.any { text.contains(it) }) return CANCELLED
        if (HIATUS_WORDS.any { text.contains(it) }) return ON_HIATUS
        if (COMPLETED_WORDS.any { text.contains(it) }) return COMPLETED
        if (ONGOING_WORDS.any { text.contains(it) }) return ONGOING
        return UNKNOWN
    }

    private val RATING = Regex("([0-9]{1,2}(?:[.,][0-9]{1,2})?)")
    private val OF_FIVE = Regex("из\\s*5|/\\s*5", RegexOption.IGNORE_CASE)

    /**
     * Оценка по тексту в шкале 0..10.
     *
     * «8.5», «8,5 из 10», «4.4 из 5» приводятся к одной шкале: иначе
     * одна и та же оценка выглядит по-разному на разных сайтах.
     */
    fun ratingOf(raw: String?): Float {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return -1f
        val found = RATING.find(text)?.groupValues?.getOrNull(1) ?: return -1f
        val value = found.replace(',', '.').toFloatOrNull() ?: return -1f
        val scaled = if (OF_FIVE.containsMatchIn(text) || value <= 5f) value * 2f else value
        return if (scaled in 0f..10f) scaled else -1f
    }

    private val YEAR = Regex("(19[5-9][0-9]|20[0-9]{2})")

    /** Год выхода из текста: первое правдоподобное четырёхзначное число. */
    fun yearOf(raw: String?): Int {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0
        val found = YEAR.find(text)?.value?.toIntOrNull() ?: return 0
        return if (found in 1950..2100) found else 0
    }

    /** Первый непустой адрес картинки, приведённый к абсолютному. */
    fun posterOf(baseUrl: String, vararg candidates: String?): String {
        for (raw in candidates) {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value.startsWith("data:")) continue
            if (value.startsWith("http://") || value.startsWith("https://")) return value
            if (value.startsWith("//")) return "https:$value"
            if (value.startsWith("/")) return baseUrl.trimEnd('/') + value
            return baseUrl.trimEnd('/') + "/" + value
        }
        return ""
    }

    /** Жанры из набора подписей: без пустых, повторов и обрезков. */
    fun genresOf(values: Iterable<String>): String = values
        .map { it.trim() }
        .filter { it.length in 2..40 }
        .distinct()
        .take(15)
        .joinToString(", ")
}
