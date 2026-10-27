package eu.kanade.tachiyomi.animeextension.all.domanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object DomanimeFilters {

    private val GENRES = listOf(
        "Все" to "",
        "Экшен" to "85",
        "Боевик" to "6",
        "Биография" to "180",
        "Вестерн" to "214",
        "Детектив" to "7",
        "Драмы" to "8",
        "История" to "9",
        "Комедия" to "10",
        "Криминал" to "11",
        "Мелодрама" to "12",
        "Музыка" to "13",
        "Приключения" to "14",
        "Семейные" to "15",
        "Спорт" to "16",
        "Триллер" to "17",
        "Ужасы" to "18",
        "Фантастика" to "19",
        "Фэнтези" to "20",
    )

    private val YEARS = listOf("Все" to "") + (2026 downTo 1990).map { it.toString() to it.toString() }

    private val TYPES = listOf("Все" to "", "Фильмы" to "1", "Сериалы" to "2", "OVA" to "3", "Короткометражки" to "224")

    private val SORTS = listOf("По рейтингу" to "rating", "По дате" to "date")

    class GenreFilter : AnimeFilter.Select<String>("Жанр", GENRES.map { it.first }.toTypedArray(), 0)

    class YearFilter : AnimeFilter.Select<String>("Год", YEARS.map { it.first }.toTypedArray(), 0)

    class TypeFilter : AnimeFilter.Select<String>("Тип", TYPES.map { it.first }.toTypedArray(), 0)

    class SortFilter : AnimeFilter.Select<String>("Сортировка", SORTS.map { it.first }.toTypedArray(), 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(GenreFilter(), YearFilter(), TypeFilter(), SortFilter())

    private fun pick(filters: AnimeFilterList, type: Class<*>, values: List<Pair<String, String>>): String {
        val filter = filters.firstOrNull { type.isInstance(it) } ?: return ""
        val state = when (filter) {
            is GenreFilter -> filter.state
            is YearFilter -> filter.state
            is TypeFilter -> filter.state
            is SortFilter -> filter.state
            else -> 0
        }
        return values.getOrNull(state)?.second.orEmpty()
    }

    /** Сайт принимает фильтры путём: /f/o.cat=85/r.year=2020/sort=date/. */
    fun buildPath(filters: AnimeFilterList): String {
        val parts = mutableListOf<String>()
        pick(filters, GenreFilter::class.java, GENRES).takeIf { it.isNotEmpty() }?.let { parts += "o.cat=$it" }
        pick(filters, YearFilter::class.java, YEARS).takeIf { it.isNotEmpty() }?.let { parts += "r.year=$it" }
        pick(filters, TypeFilter::class.java, TYPES).takeIf { it.isNotEmpty() }?.let { parts += "o.cat=$it" }
        pick(filters, SortFilter::class.java, SORTS).takeIf { it.isNotEmpty() }?.let { parts += "sort=$it" }

        return if (parts.isEmpty()) "/" else "/f/" + parts.joinToString("/") + "/"
    }
}
