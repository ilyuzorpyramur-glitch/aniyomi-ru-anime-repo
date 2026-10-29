package eu.kanade.tachiyomi.animeextension.ru.anistar

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AniStarFilters {

    class CheckBoxVal(name: String, val value: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxGroup(name: String, values: List<CheckBoxVal>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, values)

    class GenresFilter(includeNsfw: Boolean = false) : CheckBoxGroup("Жанры", genreValues(includeNsfw))

    class YearsFilter : CheckBoxGroup("Годы", YEARS.map { CheckBoxVal(it, it) })

    fun filterList(includeNsfw: Boolean): AnimeFilterList = AnimeFilterList(
        GenresFilter(includeNsfw),
        YearsFilter(),
    )

    data class SearchParams(
        val genres: List<String> = emptyList(),
        val years: List<String> = emptyList(),
    )

    private inline fun <reified R> AnimeFilterList.firstOrNullAs(): R? = filterIsInstance<R>().firstOrNull()

    private fun AnimeFilter.Group<AnimeFilter.CheckBox>.selected(): List<String> = state
        .filter { it.state }
        .mapNotNull { (it as? CheckBoxVal)?.value }

    fun getSearchParameters(filters: AnimeFilterList): SearchParams {
        if (filters.isEmpty()) return SearchParams()
        return SearchParams(
            genres = filters.firstOrNullAs<GenresFilter>()?.selected().orEmpty(),
            years = filters.firstOrNullAs<YearsFilter>()?.selected().orEmpty(),
        )
    }

    private fun genreValues(includeNsfw: Boolean): List<CheckBoxVal> =
        GENRES.map { CheckBoxVal(it.first, it.second) } +
            if (includeNsfw) NSFW_GENRES.map { CheckBoxVal(it.first, it.second) } else emptyList()

    val NSFW_GENRES: List<Pair<String, String>> = listOf(
        "Хентай" to "/hentai/",
        "Хентай 3D" to "/hentai/3d/",
        "Хентай без цензуры" to "/hentai/uncensored/",
    )

    val GENRES: List<Pair<String, String>> = listOf(
        "Экшен" to "/anime/action/",
        "Приключения" to "/anime/adventures/",
        "Комедия" to "/anime/comedy/",
        "Драма" to "/anime/drama/",
        "Детектив" to "/anime/detective/",
        "Повседневность" to "/anime/everyday-life/",
        "Фэнтези" to "/anime/fentezi/",
        "Фурри" to "/anime/fur/",
        "Игра" to "/anime/game/",
        "Исторический" to "/anime/historical/",
        "Ужасы" to "/anime/horror/",
        "Военное" to "/anime/military/",
        "Музыка" to "/anime/music/",
        "Пародия" to "/anime/parody/",
        "Психологическое" to "/anime/psychological/",
        "Романтика" to "/anime/romance/",
        "Школа" to "/anime/school/",
        "Сёнен" to "/anime/seinen/",
        "Триллер" to "/anime/thriller/",
        "Вампиры" to "/anime/vampires/",
        "Фантастика" to "/anime/fantasy/",
        "Космос" to "/anime/space/",
    )

    val YEARS: List<String> = (2026 downTo 1990).map { it.toString() }
}
