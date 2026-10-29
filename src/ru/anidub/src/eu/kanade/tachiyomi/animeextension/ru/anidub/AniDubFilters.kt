package eu.kanade.tachiyomi.animeextension.ru.anidub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AniDubFilters {

    class CheckBoxVal(name: String, val value: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxGroup(name: String, values: List<CheckBoxVal>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    class GenresFilter : CheckBoxGroup("Жанры", GENRES.map { CheckBoxVal(it.first, it.second) })

    class YearsFilter : CheckBoxGroup("Годы", YEARS.map { CheckBoxVal(it, it) })

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(GenresFilter(), YearsFilter())

    private inline fun <reified R> AnimeFilterList.firstOrNullAs(): R? = filterIsInstance<R>().firstOrNull()

    private fun AnimeFilter.Group<AnimeFilter.CheckBox>.selected(): List<String> = state
        .filter { it.state }
        .mapNotNull { (it as? CheckBoxVal)?.value }

    data class SearchParams(
        val genres: List<String> = emptyList(),
        val years: List<String> = emptyList(),
    )

    fun getSearchParameters(filters: AnimeFilterList): SearchParams {
        if (filters.isEmpty()) return SearchParams()
        return SearchParams(
            genres = filters.firstOrNullAs<GenresFilter>()?.selected().orEmpty(),
            years = filters.firstOrNullAs<YearsFilter>()?.selected().orEmpty(),
        )
    }

    val YEARS: List<String> = (2026 downTo 1995).map { it.toString() }

    val GENRES: List<Pair<String, String>> = listOf(
        "Приключения" to "приключения",
        "Боевые искусства" to "боевые искусства",
        "Сёнэн" to "сёнэн",
        "Комедия" to "комедия",
        "Фэнтези" to "фэнтези",
        "Романтика" to "романтика",
        "Драма" to "драма",
        "Экшен" to "экшен",
        "Фантастика" to "фантастика",
        "Ужасы" to "ужасы",
        "Меха" to "меха",
        "Школа" to "школа",
        "Повседневность" to "повседневность",
        "Спорт" to "спорт",
        "Детектив" to "детектив",
        "Исторический" to "исторический",
    )
}
