package eu.kanade.tachiyomi.animeextension.ru.animka

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimkaFilters {

    class CheckBoxVal(name: String, val value: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class GenresFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Жанры",
            GENRES.map { (name, value) -> CheckBoxVal(name, value) },
        )

    class ExcludeGenresFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Исключить жанры",
            GENRES.map { (name, value) -> CheckBoxVal(name, value) },
        )

    class TypeFilter :
        AnimeFilter.Select<String>(
            "Тип",
            arrayOf("Все", "Сериалы", "Фильмы"),
            0,
        )

    class StatusFilter :
        AnimeFilter.Select<String>(
            "Статус",
            arrayOf("Все", "Онгоинг", "Завершено"),
            0,
        )

    class SortFilter :
        AnimeFilter.Select<String>(
            "Сортировка",
            arrayOf("По умолчанию", "По популярности", "По дате выхода", "По рейтингу", "По году", "По алфавиту"),
            1,
        )

    class YearFilter : AnimeFilter.Select<String>("Год", arrayOf("Все годы") + YEARS.toTypedArray(), 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            GenresFilter(),
            ExcludeGenresFilter(),
            TypeFilter(),
            StatusFilter(),
            SortFilter(),
            YearFilter(),
        )

    private inline fun <reified R> AnimeFilterList.firstOrNullAs(): R? = filterIsInstance<R>().firstOrNull()

    private fun AnimeFilter.Group<AnimeFilter.CheckBox>.selected(): List<String> = state
        .filter { it.state }
        .mapNotNull { (it as? CheckBoxVal)?.value }

    private val TYPES = listOf("", "anime-serial", "anime")
    private val STATUSES = listOf("", "ongoing", "released")
    private val SORTS = listOf("", "popular", "new", "rating", "year", "alpha")

    data class SearchParams(
        val genres: List<String> = emptyList(),
        val exclude: List<String> = emptyList(),
        val type: String = "",
        val status: String = "",
        val sort: String = "popular",
        val year: String = "",
    )

    fun getSearchParameters(filters: AnimeFilterList): SearchParams {
        if (filters.isEmpty()) return SearchParams()
        val typeIndex = filters.firstOrNullAs<TypeFilter>()?.state ?: 0
        val statusIndex = filters.firstOrNullAs<StatusFilter>()?.state ?: 0
        val sortIndex = filters.firstOrNullAs<SortFilter>()?.state ?: 1
        val yearIndex = filters.firstOrNullAs<YearFilter>()?.state ?: 0
        return SearchParams(
            genres = filters.firstOrNullAs<GenresFilter>()?.selected().orEmpty(),
            exclude = filters.firstOrNullAs<ExcludeGenresFilter>()?.selected().orEmpty(),
            type = TYPES.getOrElse(typeIndex) { "" },
            status = STATUSES.getOrElse(statusIndex) { "" },
            sort = SORTS.getOrElse(sortIndex) { "popular" }.ifEmpty { "popular" },
            year = if (yearIndex > 0) YEARS.getOrElse(yearIndex - 1) { "" } else "",
        )
    }

    val YEARS: List<String> = listOf(2026, 2025, 2024, 2023, 2022, 2021, 2020, 2019, 2018, 2017, 2016, 2015, 2014, 2013, 2012, 2011, 2010, 2009, 2008, 2007, 2006, 2005, 2004, 2003, 2002, 2001, 2000, 1999, 1998, 1997, 1996, 1995, 1994, 1993, 1992, 1991, 1990, 1989, 1988, 1987, 1986, 1985, 1984, 1983, 1982, 1981, 1980)

    val GENRES: List<Pair<String, String>> = listOf(
        "Взрослые персонажи" to "взрослыеперсонажи",
        "Драма" to "драма",
        "Исторический" to "исторический",
        "Комедия" to "комедия",
        "Меха" to "меха",
        "Приключения" to "приключения",
        "Романтика" to "романтика",
        "Сверхъестественное" to "сверхъестественное",
        "Сэйнэн" to "сэйнэн",
        "Сёнен" to "сёнен",
        "Тайна" to "тайна",
        "Фантастика" to "фантастика",
        "Фэнтези" to "фэнтези",
        "Школа" to "школа",
        "Экшен" to "экшен",
        "CGDCT" to "cgdct",
        "Авангард" to "авангард",
        "Антропоморфизм" to "антропоморфизм",
        "Боевые искусства" to "боевыеискусства",
        "Вампиры" to "вампиры",
        "Видеоигры" to "видеоигры",
        "Военное" to "военное",
        "Выживание" to "выживание",
        "Гарем" to "гарем",
        "Гонки" to "гонки",
        "Городское фэнтези" to "городскоефэнтези",
        "Гурман" to "гурман",
        "Гэг-юмор" to "гэг-юмор",
        "Детектив" to "детектив",
        "Детское" to "детское",
        "Дзёсей" to "дзёсей",
        "Жестокость" to "жестокость",
        "Забота о детях" to "заботаодетях",
        "Злодейка" to "злодейка",
        "Игра с высокими ставками" to "играсвысокимиставками",
        "Идолы (Жен.)" to "идолы(жен.)",
        "Идолы (Муж.)" to "идолы(муж.)",
        "Изобразительное искусство" to "изобразительноеискусство",
        "Исполнительское искусство" to "исполнительскоеискусство",
        "Исэкай" to "исэкай",
        "Иясикэй" to "иясикэй",
        "Командный спорт" to "командныйспорт",
        "Космос" to "космос",
        "Кроссдрессинг" to "кроссдрессинг",
        "Культура отаку" to "культураотаку",
        "Любовный многоугольник" to "любовныймногоугольник",
        "Магическая смена пола" to "магическаясменапола",
        "Махо-сёдзё" to "махо-сёдзё",
        "Медицина" to "медицина",
        "Мифология" to "мифология",
        "Музыка" to "музыка",
        "Образовательное" to "образовательное",
        "Организованная преступность" to "организованнаяпреступность",
        "Пародия" to "пародия",
        "Питомцы" to "питомцы",
        "Повседневность" to "повседневность",
        "Психологическое" to "психологическое",
        "Путешествие во времени" to "путешествиевовремени",
        "Работа" to "работа",
        "Реверс-гарем" to "реверс-гарем",
        "Реинкарнация" to "реинкарнация",
        "Романтический подтекст" to "романтическийподтекст",
        "Самураи" to "самураи",
        "Спорт" to "спорт",
        "Спортивные единоборства" to "спортивныеединоборства",
        "Стратегические игры" to "стратегическиеигры",
        "Супер сила" to "суперсила",
        "Сёдзё" to "сёдзё",
        "Триллер" to "триллер",
        "Удостоено наград" to "удостоенонаград",
        "Ужасы" to "ужасы",
        "Хулиганы" to "хулиганы",
        "Шоу-бизнес" to "шоу-бизнес",
    )
}
