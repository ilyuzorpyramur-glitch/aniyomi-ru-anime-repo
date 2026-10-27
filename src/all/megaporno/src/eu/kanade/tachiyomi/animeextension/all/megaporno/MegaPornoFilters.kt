package eu.kanade.tachiyomi.animeextension.all.megaporno

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object MegaPornoFilters {

    const val DEFAULT_CATEGORY = "anime-i-hentay-video"

    /** Категории сайта в том же порядке, что и в боковом меню. */
    val CATEGORIES = listOf(
        "Азиатки" to "aziatskoe-porno-video",
        "Анальный секс" to "analniy-seks-video",
        "Аниме и Хентай" to "anime-i-hentay-video",
        "БДСМ" to "bd-sm-video",
        "Беременные" to "beremennie-video",
        "Бисексуалы" to "bisexual-video",
        "Большие сиськи" to "bolshie-siski-video",
        "Большие члены" to "bolshie-chleni-video",
        "Виар порно (VR 360°)" to "vr-porno-video",
        "Гей порно" to "gaysporn-video",
        "Групповуха" to "gruppovuha-video",
        "Домашнее порно" to "domashnee-porno-video",
        "Дрочка девушкам" to "drochka-women-video",
        "Дрочка парням" to "drochka-video",
        "Жесткое порно" to "zhestkoe-video",
        "Записи приватов" to "zapisi-privatov-video",
        "Зрелые" to "porno-so-zrelumi-video",
        "Измены (Куколд)" to "cuckold-video",
        "Инцест" to "incest-porno-video",
        "Кастинг" to "kasting-video",
        "Классика" to "classic-porno-video",
        "Куннилингус" to "cunnuslingo-video",
        "Лесбиянки" to "lesbiyanki-video",
        "Межрасовый секс" to "mezhrassovoe-porno-video",
        "Минет" to "minet-video",
        "Молодые" to "telki-18-video",
        "Мультики" to "cartoons-video",
        "На лицо (камшоты)" to "cumshots-video",
        "От первого лица (POV)" to "pov-video",
        "Порно в 3D" to "3d-porno-video",
        "Порно для женщин" to "for-women-video",
        "Порно знаменитостей" to "porno-znamenitostey-video",
        "Порно приколы" to "porno-prikoli-video",
        "Разное" to "raznoe-video",
        "Ретро порно" to "retro-porno-video",
        "Русское порно" to "rysskoe-video",
        "Скрытая камера" to "hide-cam-video",
        "Соло девушек" to "solo-devyshek-video",
        "Соло парней" to "men-solo-video",
        "Толстые (BBW)" to "tolstie-video",
        "Только негры" to "ebony-video",
        "Трансвеститы" to "transvestitu-video",
        "Фетиш" to "fetish-video",
        "Фистинг" to "fisting-video",
    )

    class CategoryFilter :
        AnimeFilter.Select<String>(
            "Категория",
            CATEGORIES.map { it.first }.toTypedArray(),
            CATEGORIES.indexOfFirst { it.second == DEFAULT_CATEGORY },
        )

    fun getCategory(filters: AnimeFilterList): String {
        val filter = filters.firstOrNull { it is CategoryFilter } as? CategoryFilter ?: return DEFAULT_CATEGORY
        return CATEGORIES.getOrNull(filter.state)?.second ?: DEFAULT_CATEGORY
    }

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(CategoryFilter())
}
