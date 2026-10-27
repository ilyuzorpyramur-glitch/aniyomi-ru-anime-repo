package eu.kanade.tachiyomi.animeextension.all.animru

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimRuFilters {

    private val GENRES = listOf(
        "Все" to "",
        "Экшен" to "ekshen",
        "Боевик" to "boyevik",
        "Боевые искусства" to "boyevyye-iskusstva",
        "Приключения" to "priklyucheniya",
        "Фантастика" to "fantastika",
        "Фэнтези" to "fentezi",
        "Мистика" to "mistika",
        "Триллер" to "triller",
        "Ужасы" to "uzhasy",
        "Драма" to "drama",
        "Детектив" to "detektiv",
        "Комедия" to "komediya",
        "Романтика" to "romantika",
        "Спорт" to "sport",
        "Супер сила" to "super-sila",
        "Военный" to "voyennyy",
        "Исторический" to "istoricheskiy",
        "Повседневность" to "povsednevnost",
        "Сёнэн" to "sonen",
        "Детское" to "detskoye",
        "Китайское" to "kitayskoye-anime",
        "Японское" to "yaponskoye-anime",
    )

    private val YEARS = listOf("Все" to "") + (2026 downTo 2015).map { it.toString() to "anime-$it-goda" }

    private val TYPES = listOf(
        "Все" to "",
        "ТВ сериалы" to "anime-tv",
        "Полнометражки" to "anime-polnometrazhki",
        "Ожидаемые" to "anonsy-ozhidayemyye",
        "Новинки" to "novinki-anime-sdg3j34b6jd",
    )

    class GenreFilter : AnimeFilter.Select<String>("Жанр", GENRES.map { it.first }.toTypedArray(), 0)

    class YearFilter : AnimeFilter.Select<String>("Год", YEARS.map { it.first }.toTypedArray(), 0)

    class TypeFilter : AnimeFilter.Select<String>("Тип", TYPES.map { it.first }.toTypedArray(), 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            GenreFilter(),
            YearFilter(),
            TypeFilter(),
        )

    /**
     * Сайт собирает раздел как /{год}/{тип}/{жанр}/: проверены пары год+жанр, год+тип и тип+жанр.
     * Пустые измерения просто выпадают из пути, поэтому комбинации работают без ограничений.
     */
    fun sectionUrl(filters: AnimeFilterList): String {
        val genre = GENRES.getOrNull((filters.firstOrNull { it is GenreFilter } as? GenreFilter)?.state ?: 0)?.second.orEmpty()
        val year = YEARS.getOrNull((filters.firstOrNull { it is YearFilter } as? YearFilter)?.state ?: 0)?.second.orEmpty()
        val type = TYPES.getOrNull((filters.firstOrNull { it is TypeFilter } as? TypeFilter)?.state ?: 0)?.second.orEmpty()

        val parts = listOf(year, type, genre).filter { it.isNotEmpty() }

        return if (parts.isEmpty()) "/home/" else "/" + parts.joinToString("/") + "/"
    }
}
