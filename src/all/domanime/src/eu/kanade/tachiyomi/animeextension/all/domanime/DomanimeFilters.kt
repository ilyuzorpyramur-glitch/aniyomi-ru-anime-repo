package eu.kanade.tachiyomi.animeextension.all.domanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object DomanimeFilters {

    private val GENRES = listOf(
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
        "Экшен" to "85",
    )

    private val YEARS = listOf("Все" to "") + (2026 downTo 1990).map { it.toString() to it.toString() }

    // Тип на сайте — это раздел, а не параметр запроса: /filmy/ и /serialy/.
    private val TYPES = listOf("Все" to "", "Фильмы" to "/filmy", "Сериалы" to "/serialy")

    private val SORTS = listOf("По дате" to "date", "По рейтингу" to "rating", "По просмотрам" to "views")

    open class CheckBoxGroup(
        name: String,
        values: List<AnimeFilter.CheckBoxVal>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    // Сайт принимает несколько жанров через запятую: o.cat=6,10.
    class GenreFilter : CheckBoxGroup("Жанры", GENRES.map { AnimeFilter.CheckBoxVal(it.first) })

    class YearFilter : AnimeFilter.Select<String>("Год", YEARS.map { it.first }.toTypedArray(), 0)

    class TypeFilter : AnimeFilter.Select<String>("Тип", TYPES.map { it.first }.toTypedArray(), 0)

    class SortFilter : AnimeFilter.Select<String>("Сортировка", SORTS.map { it.first }.toTypedArray(), 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(GenreFilter(), YearFilter(), TypeFilter(), SortFilter())

    private inline fun <reified R> AnimeFilterList.firstAs(): R? = filterIsInstance<R>().firstOrNull()

    private fun AnimeFilterList.section(): String =
        TYPES.getOrNull(firstAs<TypeFilter>()?.state ?: 0)?.second.orEmpty()

    private fun AnimeFilterList.genres(): String = firstAs<GenreFilter>()
        ?.state
        ?.filter { it.state }
        ?.mapNotNull { box -> GENRES.firstOrNull { it.first == box.name }?.second }
        ?.joinToString(",")
        .orEmpty()

    fun buildPath(filters: AnimeFilterList): String {
        val section = filters.section()
        val parts = mutableListOf<String>()

        filters.genres().takeIf { it.isNotEmpty() }?.let { parts += "o.cat=$it" }
        YEARS.getOrNull(filters.firstAs<YearFilter>()?.state ?: 0)
            ?.second
            ?.takeIf { it.isNotEmpty() }
            ?.let { parts += "r.year=$it" }
        SORTS.getOrNull(filters.firstAs<SortFilter>()?.state ?: 0)
            ?.second
            ?.takeIf { it.isNotEmpty() }
            ?.let { parts += "sort=$it" }

        val suffix = if (parts.isEmpty()) "/f/" else "/f/" + parts.joinToString("/") + "/"
        return section + suffix
    }
}
