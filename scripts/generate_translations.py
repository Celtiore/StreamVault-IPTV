from __future__ import annotations

import argparse
import html
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import requests


ROOT = Path(__file__).resolve().parents[1]
BASE_FILE = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
RES_DIR = BASE_FILE.parent.parent

ANDROID_TO_GOOGLE = {
    "values-ar": "ar",
    "values-cs": "cs",
    "values-da": "da",
    "values-de": "de",
    "values-el": "el",
    "values-es": "es",
    "values-fi": "fi",
    "values-fr": "fr",
    "values-hu": "hu",
    "values-in": "id",
    "values-it": "it",
    "values-iw": "iw",
    "values-ja": "ja",
    "values-ko": "ko",
    "values-nb": "no",
    "values-nl": "nl",
    "values-pl": "pl",
    "values-pt": "pt",
    "values-ro": "ro",
    "values-ru": "ru",
    "values-sv": "sv",
    "values-tr": "tr",
    "values-uk": "uk",
    "values-vi": "vi",
    "values-zh": "zh-CN",
}

PLACEHOLDER_PATTERN = re.compile(r"%\d+\$[sd]|%\d+\$f|%\d+\$d|%\d+\$s|%\d+\$[0-9.]*f|%%|\\n")
NON_TRANSLATABLE_NAMES = {
    "app_name",
    "welcome_version",
    "settings_developer_name",
    "settings_github_url",
}
TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
CHUNK_SEPARATOR = "\n[[12345]]\n"
PLACEHOLDER_EXTRACTOR = re.compile(r"%\d+\$[a-zA-Z]|%%|\\n")
MANUAL_OVERRIDES = {
    ("values-ja", "favorites_overview_subtitle"): "%2$d 個のカスタム グループにまたがって %1$d 件の保存済み項目があります。",
    ("values-ar", "multiview_planner_empty"): "لا توجد قنوات في الفتحات بعد.\\nاضغط لفترة طويلة على قناة لإضافتها.",
    ("values-iw", "multiview_planner_empty"): "אין עדיין ערוצים בחריצים.\\nלחץ לחיצה ארוכה על ערוץ כדי להוסיף אותו.",
    ("values-iw", "continue_watching_season_episode"): "עונה %1$d פרק %2$d",
    ("values-fr", "multiview_planner_empty"): "Aucun canal dans les emplacements pour le moment.\\nAppuyez longuement sur un canal pour l'ajouter.",
    ("values-es", "multiview_planner_empty"): "Aún no hay canales en las ranuras.\\nMantén presionado un canal para agregarlo.",
    ("values-de", "multiview_planner_empty"): "Noch keine Kanäle in den Slots.\\nDrücken Sie lange auf einen Kanal, um ihn hinzuzufügen.",
    ("values-it", "multiview_planner_empty"): "Nessun canale ancora negli slot.\\nPremi a lungo su un canale per aggiungerlo.",
    ("values-pt", "continue_watching_season_episode"): "Temporada %1$d Episódio %2$d",
}


def normalize_android_text(text: str) -> str:
    return text.replace("\\'", "'").replace('\\"', '"')


def parse_base_strings() -> list[tuple[str, str]]:
    root = ET.parse(BASE_FILE).getroot()
    strings: list[tuple[str, str]] = []
    for element in root.findall("string"):
        name = element.attrib["name"]
        text = normalize_android_text("".join(element.itertext()))
        strings.append((name, text))
    return strings


def protect_placeholders(text: str) -> tuple[str, list[str]]:
    tokens: list[str] = []

    def replace(match: re.Match[str]) -> str:
        token = f"[[99{len(tokens):03d}]]"
        tokens.append(match.group(0))
        return token

    protected = PLACEHOLDER_PATTERN.sub(replace, text)
    return protected, tokens


def restore_placeholders(text: str, tokens: list[str]) -> str:
    restored = text
    for index, original in enumerate(tokens):
        digits = f"99{index:03d}"
        pattern = r"[\[\]【】]*" + r"\D*".join(digits) + r"[\[\]【】]*"
        restored = re.sub(pattern, original, restored)
    return restored


def xml_escape(text: str) -> str:
    normalized = normalize_android_text(text)
    escaped = html.escape(normalized, quote=False)
    escaped = escaped.replace("\n", "\\n")
    escaped = escaped.replace("'", "\\'")
    return escaped


def placeholders(text: str) -> list[str]:
    return sorted(PLACEHOLDER_EXTRACTOR.findall(text))


def google_translate(text: str, target_lang: str) -> str:
    response = requests.get(
        TRANSLATE_URL,
        params={
            "client": "gtx",
            "sl": "en",
            "tl": target_lang,
            "dt": "t",
            "q": text,
        },
        timeout=60,
    )
    response.raise_for_status()
    data = response.json()
    return "".join(part[0] for part in data[0])


def translate_texts(texts: list[str], target_lang: str) -> list[str]:
    translated: list[str] = []
    chunk: list[str] = []
    chunk_limit = 3500

    def flush_chunk() -> None:
        if not chunk:
            return
        for attempt in range(3):
            try:
                joined = CHUNK_SEPARATOR.join(chunk)
                batch = google_translate(joined, target_lang)
                parts = batch.split(CHUNK_SEPARATOR)
                if len(parts) != len(chunk):
                    raise RuntimeError(
                        f"Chunk split mismatch for {target_lang}: expected {len(chunk)}, got {len(parts)}"
                    )
                translated.extend(parts)
                break
            except Exception:
                if attempt == 2:
                    raise
                time.sleep(2)
        chunk.clear()

    current_length = 0
    for text in texts:
        added_length = len(text) + (len(CHUNK_SEPARATOR) if chunk else 0)
        if chunk and current_length + added_length > chunk_limit:
            flush_chunk()
            current_length = 0
        chunk.append(text)
        current_length += added_length
    flush_chunk()
    return translated


def build_locale_file(android_dir: str, google_lang: str, base_strings: list[tuple[str, str]]) -> None:
    protected_inputs: list[str] = []
    token_lists: list[list[str]] = []
    names: list[str] = []
    passthrough: dict[str, str] = {}

    for name, text in base_strings:
        names.append(name)
        if (android_dir, name) in MANUAL_OVERRIDES:
            passthrough[name] = MANUAL_OVERRIDES[(android_dir, name)]
            continue
        if name in NON_TRANSLATABLE_NAMES or text.startswith("http"):
            passthrough[name] = text
            continue
        protected, tokens = protect_placeholders(text)
        protected_inputs.append(protected)
        token_lists.append(tokens)

    translated_values = translate_texts(protected_inputs, google_lang)
    translated_iter = iter(zip(translated_values, token_lists))

    output_lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, text in base_strings:
        if name in passthrough:
            value = passthrough[name]
        else:
            translated_text, tokens = next(translated_iter)
            value = restore_placeholders(translated_text, tokens)
            if placeholders(value) != placeholders(text):
                retry_source, retry_tokens = protect_placeholders(text)
                retry_value = restore_placeholders(google_translate(retry_source, google_lang), retry_tokens)
                if placeholders(retry_value) == placeholders(text):
                    value = retry_value
                else:
                    value = text
        output_lines.append(f'    <string name="{name}">{xml_escape(value)}</string>')
    output_lines.append("</resources>")
    output = "\n".join(output_lines) + "\n"

    target_dir = RES_DIR / android_dir
    target_dir.mkdir(parents=True, exist_ok=True)
    (target_dir / "strings.xml").write_text(output, encoding="utf-8")


def locale_key_count(android_dir: str) -> int:
    file_path = RES_DIR / android_dir / "strings.xml"
    if not file_path.exists():
        return 0
    root = ET.parse(file_path).getroot()
    return len(root.findall("string"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("locales", nargs="*", help="Android values-* folders to generate")
    parser.add_argument("--force", action="store_true", help="Regenerate even if key count matches base")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    base_strings = parse_base_strings()
    locale_items = ANDROID_TO_GOOGLE.items()
    if args.locales:
        requested = set(args.locales)
        locale_items = [(android_dir, google_lang) for android_dir, google_lang in locale_items if android_dir in requested]

    for android_dir, google_lang in locale_items:
        if not args.force and locale_key_count(android_dir) == len(base_strings):
            print(f"Skipping {android_dir} ({google_lang})")
            continue
        print(f"Generating {android_dir} ({google_lang})", flush=True)
        build_locale_file(android_dir, google_lang, base_strings)
        print(f"Finished {android_dir}", flush=True)


if __name__ == "__main__":
    main()
