package eu.kanade.tachiyomi.animeextension.ru.lakornland

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object LakornLandFilters {

    class GenreFilter : AnimeFilter.Select<String>("Жанр", GENRES.map { it.first }.toTypedArray(), 0)
    class CountryFilter : AnimeFilter.Select<String>("Страна", COUNTRIES.map { it.first }.toTypedArray(), 0)
    class YearFilter : AnimeFilter.Select<String>("Год выхода", YEARS.map { it.first }.toTypedArray(), 0)
    class RatingFilter : AnimeFilter.Select<String>("Рейтинг", RATINGS.map { it.first }.toTypedArray(), 0)
    class TypeFilter : AnimeFilter.Select<String>("Тип", TYPES.map { it.first }.toTypedArray(), 0)
    class StatusFilter : AnimeFilter.Select<String>("Статус", STATUSES.map { it.first }.toTypedArray(), 0)
    class SoundFilter : AnimeFilter.Select<String>("Озвучка", SOUNDS.map { it.first }.toTypedArray(), 0)

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("Фильтры работают, когда строка поиска пуста (поиск идёт по названию)"),
            GenreFilter(),
            CountryFilter(),
            YearFilter(),
            RatingFilter(),
            TypeFilter(),
            StatusFilter(),
            SoundFilter(),
        )

    data class SearchParams(
        val genreId: Int? = null,
        val countryId: Int? = null,
        val minYear: Int? = null,
        val maxYear: Int? = null,
        val minRating: Int? = null,
        val typeId: Int? = null,
        val status: Int? = null,
        val soundId: Int? = null,
    )

    private inline fun <reified R> AnimeFilterList.firstOrNullAs(): R? = filterIsInstance<R>().firstOrNull()

    private fun AnimeFilterList.value(state: Int, values: List<Pair<String, Int?>>): Int? = values.getOrElse(state) { values[0] }.second

    fun getSearchParameters(filters: AnimeFilterList): SearchParams {
        if (filters.isEmpty()) return SearchParams()

        val year = filters.value(filters.firstOrNullAs<YearFilter>()?.state ?: 0, YEARS)

        return SearchParams(
            genreId = filters.value(filters.firstOrNullAs<GenreFilter>()?.state ?: 0, GENRES),
            countryId = filters.value(filters.firstOrNullAs<CountryFilter>()?.state ?: 0, COUNTRIES),
            minYear = year,
            maxYear = year,
            minRating = filters.value(filters.firstOrNullAs<RatingFilter>()?.state ?: 0, RATINGS),
            typeId = filters.value(filters.firstOrNullAs<TypeFilter>()?.state ?: 0, TYPES),
            status = filters.value(filters.firstOrNullAs<StatusFilter>()?.state ?: 0, STATUSES),
            soundId = filters.value(filters.firstOrNullAs<SoundFilter>()?.state ?: 0, SOUNDS),
        )
    }

    private val GENRES = listOf<Pair<String, Int?>>(
        "Любой" to null,
        "Бизнес" to 50,
        "Боевик" to 8,
        "Боевые искусства" to 51,
        "Вампиры" to 52,
        "Военные" to 68,
        "Гендерная интрига" to 53,
        "Детектив" to 1,
        "Для взрослых" to 22,
        "Драма" to 2,
        "Дружба" to 55,
        "Игра" to 46,
        "Искусство" to 40,
        "История" to 12,
        "Комедия" to 6,
        "Криминал" to 4,
        "Кулинарные" to 42,
        "Медицина" to 56,
        "Мелодрама" to 3,
        "Мистика" to 10,
        "Музыка" to 15,
        "Научная фантастика" to 32,
        "Нуар" to 57,
        "По комиксам" to 28,
        "Повседневность" to 20,
        "Политика" to 58,
        "Постапокалиптика" to 30,
        "Приключения" to 11,
        "Психология" to 31,
        "Реалити-шоу" to 44,
        "Романтика" to 18,
        "Саспенс" to 59,
        "Сверхъестественное" to 67,
        "Ситком" to 62,
        "Спорт" to 13,
        "Сёдзё-ай" to 60,
        "Сёнэн-ай" to 61,
        "Тайны и загадки" to 37,
        "Танцевальные" to 45,
        "Трагедия" to 64,
        "Триллер" to 5,
        "Ужасы" to 7,
        "Уся" to 65,
        "Фантастика" to 9,
        "Фэнтези" to 16,
        "Человек" to 29,
        "Школа" to 19,
        "Экстремальные" to 36,
        "Юридический" to 66,
    )

    private val COUNTRIES = listOf<Pair<String, Int?>>(
        "Любая" to null,
        "Вьетнам" to 5,
        "Гонконг" to 6,
        "Китай" to 3,
        "США" to 12,
        "Сингапур" to 9,
        "Таиланд" to 13,
        "Тайвань" to 10,
        "Филиппины" to 11,
        "Южная Корея" to 2,
        "Япония" to 1,
    )

    private val YEARS = listOf<Pair<String, Int?>>(
        "Любой" to null,
        "2027" to 2027,
        "2026" to 2026,
        "2025" to 2025,
        "2024" to 2024,
        "2023" to 2023,
        "2022" to 2022,
        "2021" to 2021,
        "2020" to 2020,
        "2019" to 2019,
        "2018" to 2018,
        "2017" to 2017,
        "2016" to 2016,
        "2015" to 2015,
        "2014" to 2014,
        "2012" to 2012,
        "2010" to 2010,
        "2009" to 2009,
        "2008" to 2008,
        "2006" to 2006,
        "2004" to 2004,
        "2001" to 2001,
    )

    private val RATINGS = listOf<Pair<String, Int?>>(
        "Любой" to null,
        "от 5" to 5,
        "от 6" to 6,
        "от 7" to 7,
        "от 8" to 8,
        "от 9" to 9,
    )

    private val TYPES = listOf<Pair<String, Int?>>(
        "Любой" to null,
        "Сериал" to 1,
        "Фильм" to 2,
    )

    private val STATUSES = listOf<Pair<String, Int?>>(
        "Любой" to null,
        "Выходит" to 0,
        "Завершен" to 1,
        "В ожидании" to 3,
    )

    private val SOUNDS = listOf<Pair<String, Int?>>(
        "Любая" to null,
        "AniDUB" to 79,
        "AniLeagueTV" to 131,
        "AniMaunt" to 5,
        "Asian Miracle Group (AMG)" to 13,
        "BLDUB" to 66,
        "BLStudio" to 107,
        "ColdFilm" to 67,
        "d4Voices" to 12,
        "DAVOICES" to 38,
        "DreamWings" to 58,
        "DubLikTV" to 2,
        "FrDub" to 21,
        "FRONDA" to 165,
        "GL DUB" to 117,
        "HDrezka" to 71,
        "Henry’s Voice" to 106,
        "HighHopes" to 15,
        "KTM" to 108,
        "KTM & MikaSalidle" to 112,
        "KZ_GMM" to 113,
        "LE-Production" to 68,
        "Ledikion" to 19,
        "Light Breeze" to 3,
        "LostFilm" to 73,
        "LyrikaDub" to 124,
        "MikaSalidle" to 76,
        "Moon Wine" to 84,
        "Pus'ki Production" to 7,
        "Red tail" to 25,
        "Rin" to 28,
        "RuDub" to 72,
        "SlothSound" to 8,
        "SoftBox" to 14,
        "Sound Film" to 42,
        "STEPonee" to 50,
        "Strekoza Voice" to 103,
        "SunShine" to 169,
        "UAFLIX (UA)" to 137,
        "Ultradox" to 37,
        "Unicorn" to 6,
        "VOICE PROJECT STUDIO" to 64,
        "W³: voices (Дораманутая)" to 54,
        "XDUB DORAMA" to 52,
        "Автосабы" to 1,
        "Автосабы (KG)" to 141,
        "Автосабы (RU / KG)" to 164,
        "Автосабы (RU / UA / KG)" to 148,
        "Автосабы (RU / UA)" to 144,
        "Автосабы (UA / KG)" to 146,
        "Автосабы (UA)" to 140,
        "Оригинал" to 20,
        "ПВА ШОУ" to 10,
        "Превью (автосабы RU / UA / KG)" to 166,
        "Превью (оригинал)" to 145,
        "Превью (субтитры EN)" to 155,
        "Превью 2" to 82,
        "Синема УС" to 101,
        "Странные Миры" to 17,
        "Субтитры" to 150,
        "Субтитры (RU / UA)" to 158,
        "Субтитры (UA)" to 139,
        "УЗЫ ГИМЕНЕЯ" to 9,
        "Украинские субтитры" to 11,
        "Храм Дорам" to 22,
        "Чорний верес (UA)" to 154,
        "Юлия Просенюк (Julia Prosenuk)" to 35,
    )
}
