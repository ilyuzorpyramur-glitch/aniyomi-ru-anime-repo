package eu.kanade.tachiyomi.animeextension.all.kawaki

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object KawakiFilters {

    // Названия жанров сайт принимает как есть, в русском написании (?genre=Боевые искусства).
    private val GENRES = listOf(
        "Все",
        "CGDCT",
        "Авангард",
        "Антропоморфизм",
        "Безумие",
        "Боевые искусства",
        "Вампиры",
        "Взрослые персонажи",
        "Видеоигры",
        "Военное",
        "Выживание",
        "Гарем",
        "Гонки",
        "Городское фэнтези",
        "Гурман",
        "Гэг-юмор",
        "Демоны",
        "Детектив",
        "Детское",
        "Дзёсей",
        "Драма",
        "Жестокость",
        "Забота о детях",
        "Злодейка",
        "Игра с высокими ставками",
        "Игры",
        "Идолы (Жен.)",
        "Идолы (Муж.)",
        "Изобразительное искусство",
        "Исполнительское искусство",
        "Исторический",
        "Комедия",
        "Космос",
        "Культура",
        "Магия",
        "Меха",
        "Мистика",
        "Музыка",
        "Научная фантастика",
        "Повседневность",
        "Приключения",
        "Психологическое",
        "Романтика",
        "Сверхъестественное",
        "Спорт",
        "Супер сила",
        "Триллер",
        "Ужасы",
        "Фэнтези",
        "Школа",
        "Экшен",
        "Этти",
    )

    private val YEARS = listOf("Все") + (2026 downTo 1990).map { it.toString() }

    private val STATUSES = listOf("Все", "Онгоинг" to "ONGOING", "Завершён" to "FINISHED")

    class GenreFilter : AnimeFilter.Select<String>("Жанр", GENRES.toTypedArray(), 0)

    class YearFilter : AnimeFilter.Select<String>("Год", YEARS.toTypedArray(), 0)

    class StatusFilter : AnimeFilter.Select<String>("Статус", STATUSES.map { it.first }.toTypedArray(), 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            GenreFilter(),
            YearFilter(),
            StatusFilter(),
        )

    fun genre(filters: AnimeFilterList): String {
        val index = (filters.firstOrNull { it is GenreFilter } as? GenreFilter)?.state ?: return ""
        return GENRES.getOrNull(index).orEmpty().takeIf { it != "Все" }.orEmpty()
    }

    fun year(filters: AnimeFilterList): String {
        val index = (filters.firstOrNull { it is YearFilter } as? YearFilter)?.state ?: return ""
        return YEARS.getOrNull(index).orEmpty().takeIf { it != "Все" }.orEmpty()
    }

    fun status(filters: AnimeFilterList): String {
        val index = (filters.firstOrNull { it is StatusFilter } as? StatusFilter)?.state ?: return ""
        return STATUSES.getOrNull(index)?.second.orEmpty()
    }
}
