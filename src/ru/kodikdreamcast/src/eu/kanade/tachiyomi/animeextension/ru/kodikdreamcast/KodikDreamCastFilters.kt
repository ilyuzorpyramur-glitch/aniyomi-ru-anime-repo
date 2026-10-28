package eu.kanade.tachiyomi.animeextension.ru.kodikdreamcast

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KodikDreamCastFilters {

    val SORTS = listOf(
        "Свежие" to "updated_at",
        "Рейтинг Кинопоиска" to "kinopoisk_rating",
        "Рейтинг IMDb" to "imdb_rating",
        "Просмотры" to "views",
        "По алфавиту" to "title",
    )

    val FALLBACK_GENRES = listOf(
        "аниме",
        "боевик",
        "фэнтези",
        "комедия",
        "драма",
        "детектив",
        "приключения",
        "фантастика",
        "романтика",
        "школа",
        "сёнен",
        "сёдзё",
    )

    class SortFilter : AnimeFilter.Select<String>("Сортировка", SORTS.map { it.first }.toTypedArray(), 0)

    class OrderFilter : AnimeFilter.Select<String>("Порядок", arrayOf("По убыванию", "По возрастанию"), 0)

    class GenreFilter(genres: List<String>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>("Жанры", genres.map { AnimeFilter.CheckBox(it) })

    class YearFilter(years: List<Int>) :
        AnimeFilter.Select<String>("Год", (listOf("Любой") + years.map { it.toString() }).toTypedArray(), 0)

    class CountryFilter(countries: List<String>) :
        AnimeFilter.Select<String>("Страна", (listOf("Любая") + countries).toTypedArray(), 0)

    data class SearchParams(
        val sort: String = SORTS[0].second,
        val order: String = "desc",
        val genres: List<String> = emptyList(),
        val year: Int = 0,
        val country: String = "",
    )

    fun getFilterList(
        genres: List<String>,
        years: List<Int>,
        countries: List<String>,
    ): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        OrderFilter(),
        GenreFilter(genres.ifEmpty { FALLBACK_GENRES }),
        YearFilter(years),
        CountryFilter(countries),
    )

    inline fun <reified R> AnimeFilterList.firstOrNullAs(): R? = filterIsInstance<R>().firstOrNull()

    fun getSearchParameters(filters: AnimeFilterList): SearchParams {
        if (filters.isEmpty()) return SearchParams()

        val sort = filters.firstOrNullAs<SortFilter>()?.let { SORTS.getOrNull(it.state)?.second } ?: SORTS[0].second
        val order = if ((filters.firstOrNullAs<OrderFilter>()?.state ?: 0) == 0) "desc" else "asc"
        val genres = filters.firstOrNullAs<GenreFilter>()
            ?.state
            .orEmpty()
            .filter { it.state }
            .map { it.name }
        val year = filters.firstOrNullAs<YearFilter>()
            ?.let { it.values.getOrNull(it.state) }
            ?.toIntOrNull()
            ?: 0
        val country = filters.firstOrNullAs<CountryFilter>()
            ?.let { it.values.getOrNull(it.state) }
            ?.takeUnless { it == "Любая" }
            .orEmpty()

        return SearchParams(sort = sort, order = order, genres = genres, year = year, country = country)
    }
}
