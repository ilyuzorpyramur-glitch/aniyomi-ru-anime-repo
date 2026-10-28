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

---

## 🤖 Как я пишу код

<p align="center">
  <img src=".github/assets/ai-pair-coding.jpg" alt="AI пишет код, я проверяю" width="100%">
</p>

Не секрет: код в этих репозиториях я делаю не вручную. Основную работу — разбор
сайтов, генерацию расширений, починку селекторов и сборку — выполняет AI,
а я ставлю задачи, проверяю результат и отвечаю за то, что уходит в релиз.

- AI пишет код и готовит APK, GitHub Actions собирает и подписывает
- я проверяю источники, тестирую расширения на устройстве и решаю, что публиковать
- ошибки, регрессии и спорные решения — на мне

Так проект живёт быстрее: больше источников, чаще обновления, меньше рутины.

## ❤️ Поддержать проект

<p align="center">
  <img src=".github/assets/donate-banner.jpg" alt="Поддержать проект" width="100%">
</p>

Расширения бесплатные и без рекламы. Если они экономят твоё время —
можно отправить донат через Tribute:

<p align="center">
  <a href="https://web.tribute.tg/d/T6v"><img src="https://img.shields.io/badge/%D0%9E%D1%82%D0%BA%D1%80%D1%8B%D1%82%D1%8C%20%D0%B2%20%D0%B1%D1%80%D0%B0%D1%83%D0%B7%D0%B5%D1%80%D0%B5-Tribute-ff4d6d?style=for-the-badge&logoColor=white" alt="Поддержать в браузере"></a>
  <a href="https://t.me/tribute/app?startapp=dT6v"><img src="https://img.shields.io/badge/Telegram-Tribute-2CA5E0?style=for-the-badge&logo=telegram&logoColor=white" alt="Поддержать в Telegram"></a>
</p>

<p align="center"><sub>Спасибо, что пользуешься и поддерживаешь ❤️</sub></p>
