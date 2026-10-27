<h1 align="center">🎬 Aniyomi · Русские источники аниме</h1>

<p align="center">
  <a href="https://github.com/ilyuzorpyramur-glitch/aniyomi-ru-anime-repo/actions"><img src="https://github.com/ilyuzorpyramur-glitch/aniyomi-ru-anime-repo/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <img src="https://img.shields.io/badge/language-Русский-8ca9ff" alt="RU">
  <img src="https://img.shields.io/badge/приложение-Aniyomi%20%2F%20Tadami-ff8ca9" alt="Aniyomi">
</p>

> Репозиторий расширений аниме для **Aniyomi** и форков (Tadami, TachiyomiSY-совместимые плееры).
> Русские озвучки и онлайн-кинотеатры в одном индексе.

---

## ✨ Особенности

- 🇷🇺 **Русские источники аниме** — AniLibria, AniMedia, AnimeGO, AniLiberty, Kodik-сети и другие
- 🎥 **Работа с плеерными API** — плейлисты, качества, озвучки через `lib:playlistutils`
- ⚙️ **Настройки в каждом расширении** — качество по умолчанию, предпочитаемые озвучки
- 🔐 **Собственный ключ подписи** — стабильные обновления
- 🤖 **Полная автоматизация** — push в `main` → сборка → подпись → публикация индекса

## 📲 Установка

**Шаг 1.** Добавьте репозиторий в Aniyomi / Tadami:

> Настройки → Расширения → Репозитории → ➕ Добавить

```
https://raw.githubusercontent.com/ilyuzorpyramur-glitch/aniyomi-ru-anime-repo/main/index.min.json
```

**Шаг 2.** Откройте **Обзор → Расширения** и установите нужные источники.

<details>
<summary>Совместимость с приложениями</summary>

| Приложение | Работает |
|---|:---:|
| Aniyomi | ✅ |
| Tadami (форк Aniyomi) | ✅ |
| Mihon | ❌ (это репозиторий аниме) |

Для манги используйте [mihon-ru-manga-repo](https://github.com/ilyuzorpyramur-glitch/mihon-ru-manga-repo),
для ранобэ — [lnreader-ru-plugins](https://github.com/ilyuzorpyramur-glitch/lnreader-ru-plugins).
</details>

## 📖 Каталог источников

AniLibria · AniMedia · AniMakima · Animedia · AnimeGO · Animesss · AnimeVost · AniSprout · BLDub · DoramaLend · DoramyClub · Justsu · JutsuNet · Kinogo · LakornMania · LordFilm · TVigle · YummyAnime · Mega Porno · Animeru · Kawaki · ДомАниме

*Полный актуальный список — в [`index.json`](index.json).*

## 🔧 Сборка (для разработчиков)

Сборка идёт в GitHub Actions при пуше в `main`:

1. Gradle `assembleRelease` по всем модулям (`src/ru/<name>`)
2. Подпись секретным ключом
3. Генерация `index.json` / `index.min.json` (имя пакета, версия, id источника, baseUrl)
4. Коммит `apk/`, `icon/`, `index*.json` обратно

Локально:

```bash
./gradlew :src:ru:anilibria:assembleRelease
```

### Формат модуля расширения

```
src/ru/<имя>/
├── build.gradle           # extName, extClass, extVersionCode, isNsfw
├── res/mipmap-*/          # иконка
└── src/eu/kanade/tachiyomi/animeextension/ru/<имя>/
    ├── <Класс>.kt         # логика парсинга каталога
    ├── <Класс>Filters.kt  # фильтры поиска
    └── Dto.kt             # модели ответов API
```

## 🛠 Генератор расширений

Новые источники создаются автоматическим генератором
**[extension-forge](https://github.com/ilyuzorpyramur-glitch/extension-forge)**:
шаблоны расширений с иконками, настройками качества и автоинкрементом версии,
плюс ежемесячный сторож, который проверяет расширения на работоспособность.

## 📜 Лицензия

[Apache License 2.0](LICENSE). Исходный код расширений принадлежит проекту
[Aniyomi](https://github.com/aniyomiorg/aniyomi-extensions) и его контрибьюторам.

---
<p align="center">Часть экосистемы <a href="https://github.com/ilyuzorpyramur-glitch/extension-forge">extension-forge</a> · аниме · манга · ранобэ</p>
