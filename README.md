# Aniyomi RU Anime Repo

Репозиторий русских аниме-расширений для [Aniyomi](https://github.com/aniyomiorg/aniyomi) / [Anikku](https://github.com/komikku-app/anikku).

## Ссылка для добавления в приложение

```
https://raw.githubusercontent.com/ilyuzorpyramur-glitch/aniyomi-ru-anime-repo/main/index.min.json
```

Настройки → Браузер → Репозитории расширений → добавить ссылку, затем Браузер → Расширения → обновить список.

## Расширения

| Расширение | Источники | 18+ |
|---|---|---|
| AniLibria | anilibria.top | нет |
| LordFilm | lordfilmonline.cc (зеркала lordfilmlive.com — переключаются в настройках; сериалы пока без поддержки плеера) | нет |
| AnimeGO | animego.lat (зеркало переключается в настройках) | нет |
| Anime365 | smotret-anime.online (для видео нужен аккаунт с подпиской) | нет |
| Animakima | animakima.ru | нет |
| AniSprout | anisprout.com | нет |
| AniMedia | animemedia.org | нет |
| JustSu | just-su.org | нет |
| Jut-su.net | jut-su.net | нет |
| AnimeSSS | animesss.tv | нет |
| Animevost | animevost.org + зеркало v13.vost.pw | нет |
| YummyAnime | ru.yummyani.me | нет |
| Animelib | animelib.org/ru | да |
| DoramaLend | s2.doramalend.tv (дорамы: Корея, Китай, Япония) | нет |
| DoramyClub | doramyclub.media (дорамы и фильмы Азии, плеер Kodik) | нет |
| BLDUB | bldub.com + зеркала bldub.live (BL/GL дорамы и лакорны) | нет |
| Eporner | eporner.com (18+, официальный API) | да |
| XNXX | xnxx.com (18+) | да |
| XV-RU | xv-ru.com (18+, русская версия xvideos) | да |

## Сборка

Всё собирается только в GitHub Actions (`.github/workflows/build.yml`): push в `main` → сборка release-APK через Gradle → подпись ключом из секретов → сбор `index.json` / `index.min.json` и иконок → коммит обратно в `main`.

Секреты репозитория: `SIGNING_KEY` (keystore в base64), `KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `ALIAS`.

`repo.json` содержит `signingKeyFingerprint` этого ключа — приложение проверяет подпись APK по нему.

## Лицензия

Apache License 2.0 — см. [LICENSE](./LICENSE). Код расширений основан на [yuzono/anime-extensions](https://github.com/yuzono/anime-extensions) и [aniyomiorg/extensions-source](https://github.com/aniyomiorg/extensions-source).
