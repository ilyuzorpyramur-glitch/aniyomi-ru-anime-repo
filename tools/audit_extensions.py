#!/usr/bin/env python3
"""Статический аудит аниме-расширений: что мешает одинаковому поведению.

Проверяет по каждому модулю: фильтры, поиск, карточку тайтла, постер,
статус, оценку, год, настройки качества и их подключение к экрану.
Печатает таблицу и список правок.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def modules():
    for lang in ("ru", "all"):
        base = ROOT / "src" / lang
        if not base.is_dir():
            continue
        for mod in sorted(base.iterdir()):
            if mod.is_dir():
                yield f"{lang}/{mod.name}", mod

def main_file(mod):
    files = [f for f in mod.rglob("*.kt") if f.is_file()]
    return max(files, key=lambda f: f.stat().st_size) if files else None

def build_files(mod):
    return list(mod.glob("build.gradle*"))

def audit(name, mod):
    main = main_file(mod)
    if main is None:
        return {"name": name, "issues": ["нет исходников"]}
    src = main.read_text(encoding="utf-8", errors="ignore")
    all_src = "\n".join(f.read_text(encoding="utf-8", errors="ignore")
                        for f in mod.rglob("*.kt"))
    issues = []
    if "getFilterList" not in src:
        issues.append("нет фильтров")
    if "searchAnimeRequest" not in src:
        issues.append("нет поиска")
    if "animeDetailsRequest" not in src and "animeDetailsParse" not in src:
        issues.append("нет разбора карточки")
    if not re.search(r"thumbnail_url\s*=", src):
        issues.append("нет постера")
    hard = re.search(r"status\s*=\s*SAnime\.(COMPLETED|ONGOING)\b(?!\s*\?)", src)
    if hard:
        issues.append("статус зашит: " + hard.group(1))
    elif "status" not in src:
        issues.append("статус не заполняется")
    if "rating" not in all_src:
        issues.append("нет оценки")
    if not re.search(r"year|releaseDate|aired", all_src):
        issues.append("нет года")
    if "quality" not in all_src.lower():
        issues.append("нет выбора качества")
    if "ConfigurableAnimeSource" not in src:
        issues.append("настройки недоступны")
    elif "setupPreferenceScreen" not in all_src:
        issues.append("экран настроек пуст")
    if "genre" not in src.lower():
        issues.append("нет жанров")
    return {"name": name, "issues": issues, "size": main.stat().st_size}

rows = [audit(n, m) for n, m in modules()]
width = max(len(r["name"]) for r in rows)
for r in rows:
    print(f"{r['name']:>{width}}  {'; '.join(r['issues']) if r.get('issues') else 'ok'}")

total = len(rows)
counts = {}
for r in rows:
    for i in r.get("issues", []):
        counts[i] = counts.get(i, 0) + 1
print(f"\nмодулей: {total}")
for k, v in sorted(counts.items(), key=lambda x: -x[1]):
    print(f"{v:3}  {k}")
if len(sys.argv) > 1 and sys.argv[1] == "--strict":
    sys.exit(1 if counts else 0)
