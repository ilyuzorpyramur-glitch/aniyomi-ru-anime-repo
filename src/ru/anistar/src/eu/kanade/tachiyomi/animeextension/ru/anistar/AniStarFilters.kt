package eu.kanade.tachiyomi.animeextension.ru.anistar

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AniStarFilters {

    class CheckBoxVal(name: String, val value: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxGroup(name: String, values: List<CheckBoxVal>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    class GenresFilter : CheckBoxGroup("Жанры", GENRES.map { CheckBoxVal(it.first, it.second) })

    class YearsFilter : CheckBoxGroup("Годы", YEARS.map { CheckBoxVal(it, it) })

    class SortFilter : AnimeFilter.Select<String>("Сортировка", SORTING.map { it.first }.toTypedArray(), 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            SortFilter(),
            GenresFilter(),
            YearsFilter(),
        )

    data class SearchParams(
        val sort: String = SORTING[0].second,
        val genres: List<String> = emptyList(),
        val years: List<String> = emptyList(),
    )

    private inline fun <reified R> AnimeFilterList.firstOrNullAs(): R? = filterIsInstance<R>().firstOrNull()

    private fun AnimeFilter.Group<AnimeFilter.CheckBox>.selected(): List<String> = state
        .filter { it.state }
        .mapNotNull { (it as? CheckBoxVal)?.value }

    fun getSearchParameters(filters: AnimeFilterList): SearchParams {
        if (filters.isEmpty()) return SearchParams()
        val sortIndex = filters.firstOrNullAs<SortFilter>()?.state ?: 0
        return SearchParams(
            sort = SORTING.getOrElse(sortIndex) { SORTING[0] }.second,
            genres = filters.firstOrNullAs<GenresFilter>()?.selected().orEmpty(),
            years = filters.firstOrNullAs<YearsFilter>()?.selected().orEmpty(),
        )
    }

    val SORTING = listOf(
        "Популярные" to "rating",
        "Новые" to "date",
    )

    val GENRES: List<Pair<String, String>> = listOf(
        "Экшен" to "/anime/action/",
        "Комедия" to "/anime/comedy/",
        "Романтика" to "/anime/romance/",
        "Фэнтези" to "/anime/fantasy/",
        "Драма" to "/anime/drama/",
        "Сёнен" to "/anime/seinen/",
        "Меха" to "/anime/mecha/",
        "Ужасы" to "/anime/horror/",
        "Детектив" to "/anime/detective/",
        "Приключения" to "/anime/adventure/",
        "Фантастика" to "/anime/sci-fi/",
        "Повседневность" to "/anime/slice-of-life/",
        "Спорт" to "/anime/sport/",
        "Исторический" to "/anime/historical/",
        "Музыка" to "/anime/music/",
        "Психологическое" to "/anime/psychological/",
        "Школа" to "/anime/school/",
        "Гарем" to "/anime/harem/",
        "Дзёсэй" to "/anime/josei/",
        "Сёдзё" to "/anime/shoujo/",
    )

    val YEARS: List<String> = (2026 downTo 1990).map { it.toString() }
}
