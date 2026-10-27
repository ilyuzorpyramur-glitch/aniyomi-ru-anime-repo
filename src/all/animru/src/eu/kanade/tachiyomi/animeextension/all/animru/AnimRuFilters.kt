package eu.kanade.tachiyomi.animeextension.all.animru

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimRuFilters {

    /** Сайт умеет листать только один раздел за раз, поэтому это единственный фильтр. */
    private val SECTIONS = listOf(
        "Все аниме" to "/home/",
        "Новинки" to "/novinki-anime-sdg3j34b6jd/",
        "Ожидаемые" to "/anonsy-ozhidayemyye/",
        "Аниме ТВ" to "/anime-tv/",
        "Полнометражки" to "/anime-polnometrazhki/",
        "Аниме 2026" to "/anime-2026-goda/",
        "Аниме 2025" to "/anime-2025-goda/",
        "Аниме 2024" to "/anime-2024-goda/",
        "Аниме 2023" to "/anime-2023-goda/",
        "Аниме 2022" to "/anime-2022-goda/",
        "Аниме 2021" to "/anime-2021-goda/",
        "Экшен" to "/ekshen/",
        "Боевик" to "/boyevik/",
        "Боевые искусства" to "/boyevyye-iskusstva/",
        "Приключения" to "/priklyucheniya/",
        "Фантастика" to "/fantastika/",
        "Фэнтези" to "/fentezi/",
        "Мистика" to "/mistika/",
        "Триллер" to "/triller/",
        "Ужасы" to "/uzhasy/",
        "Драма" to "/drama/",
        "Детектив" to "/detektiv/",
        "Комедия" to "/komediya/",
        "Романтика" to "/romantika/",
        "Спорт" to "/sport/",
        "Супер сила" to "/super-sila/",
        "Военный" to "/voyennyy/",
        "Исторический" to "/istoricheskiy/",
        "Повседневность" to "/povsednevnost/",
        "Сёнэн" to "/sonen/",
        "Детское" to "/detskoye/",
        "Китайское" to "/kitayskoye-anime/",
        "Японское" to "/yaponskoye-anime/",
    )

    class SectionFilter : AnimeFilter.Select<String>("Раздел", SECTIONS.map { it.first }.toTypedArray(), 0)

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(SectionFilter())

    fun getSection(filters: AnimeFilterList): String {
        val filter = filters.firstOrNull { it is SectionFilter } as? SectionFilter ?: return SECTIONS[0].second
        return SECTIONS.getOrNull(filter.state)?.second ?: SECTIONS[0].second
    }
}
