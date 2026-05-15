#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n-nbsp-fr.py — Convertit les espaces simples en U+00A0 (espace insécable)
avant la ponctuation forte FR (`:`, `;`, `?`, `!`, `»`), à l'intérieur des
contenus textuels des éléments `<string>`, `<string-array>/<item>` et
`<plurals>/<item>` d'un fichier `strings.xml` Android.

Approche : parsing textuel (pas de DOM) pour préserver à l'identique :
    - les commentaires XML (`<!-- ... -->`)
    - les CDATA (`<![CDATA[...]]>`)
    - les attributs XML (`name="..."`, `formatted="..."`, etc.)
    - les guillemets simples de la déclaration XML
    - le formatage exact (espaces, sauts de ligne, indentation)

Heuristiques de protection appliquées dans chaque segment textuel cible :
    1. URLs `https?://...` — les `:` internes sont préservés.
    2. Placeholders Android `%1$s`, `%2$d`, `%s`, `%d` — préservés.
    3. CDATA `<![CDATA[...]]>` à l'intérieur d'un `<string>` — non modifié.

Modes :
    --dry-run (défaut)  liste les substitutions prévues avec contexte ±20 chars.
    --apply             écrit le fichier après substitution.

Exit codes :
    0   succès (dry-run ou apply terminé sans erreur)
    1   erreur d'usage / fichier introuvable

Usage :
    python3 scripts/i18n-nbsp-fr.py [--dry-run|--apply] <path-to-strings.xml>

Exemple :
    python3 scripts/i18n-nbsp-fr.py --dry-run app/src/main/res/values-fr/strings.xml
    python3 scripts/i18n-nbsp-fr.py --apply  app/src/main/res/values-fr/strings.xml
"""

from __future__ import annotations

import argparse
import re
import sys

NBSP = " "  # espace insécable U+00A0

# Regex de protection.
RE_URL = re.compile(r"https?://[^\s<]+")
RE_PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")
RE_CDATA = re.compile(r"<!\[CDATA\[.*?\]\]>", re.DOTALL)
# Espace ASCII (0x20) suivi de ponctuation forte FR.
RE_SPACE_BEFORE_PUNCT = re.compile(r"\x20([:;?!»])")

# Repère les contenus textuels de `<string ...>...</string>` et
# `<item ...>...</item>` (les `<item>` ne sont utilisés qu'à l'intérieur de
# `<string-array>` ou `<plurals>` dans strings.xml — on ne se prive pas du
# match large, le pire cas est un faux positif inoffensif).
RE_STRING_BODY = re.compile(
    r"(<(?:string|item)\b[^>]*>)(.*?)(</(?:string|item)>)",
    re.DOTALL,
)


def _mask_protected(text: str) -> tuple[str, list[str]]:
    """Masque URL/placeholder/CDATA pour éviter les substitutions parasites."""
    saved: list[str] = []

    def _stash(match: re.Match[str]) -> str:
        saved.append(match.group(0))
        return f"\x00{len(saved) - 1}\x00"

    masked = RE_CDATA.sub(_stash, text)
    masked = RE_URL.sub(_stash, masked)
    masked = RE_PLACEHOLDER.sub(_stash, masked)
    return masked, saved


def _unmask(masked: str, saved: list[str]) -> str:
    def _restore(match: re.Match[str]) -> str:
        return saved[int(match.group(1))]

    return re.sub(r"\x00(\d+)\x00", _restore, masked)


def _convert_body(body: str, key: str, hits: list[tuple[str, str, str]]) -> str:
    """Convertit les espaces simples en NBSP dans `body` (texte d'un <string>).

    Ajoute (key, before_ctx, after_ctx) à `hits` pour chaque substitution.
    """
    if not body:
        return body
    masked, saved = _mask_protected(body)

    def _record(match: re.Match[str]) -> str:
        start = max(0, match.start() - 20)
        end = min(len(masked), match.end() + 20)
        ctx_masked = masked[start:end]
        try:
            before_ctx = _unmask(ctx_masked, saved)
        except Exception:
            before_ctx = ctx_masked
        after_ctx = before_ctx.replace(
            f" {match.group(1)}", f"{NBSP}{match.group(1)}", 1
        )
        hits.append((key, before_ctx, after_ctx))
        return f"{NBSP}{match.group(1)}"

    converted = RE_SPACE_BEFORE_PUNCT.sub(_record, masked)
    return _unmask(converted, saved)


def _extract_name(open_tag: str) -> str:
    m = re.search(r'name="([^"]+)"', open_tag)
    return m.group(1) if m else "<unnamed>"


def process(xml_path: str, apply: bool) -> int:
    try:
        with open(xml_path, "r", encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        print(f"error: file not found: {xml_path}", file=sys.stderr)
        return 1

    hits: list[tuple[str, str, str]] = []

    def _substitute(match: re.Match[str]) -> str:
        open_tag, body, close_tag = match.group(1), match.group(2), match.group(3)
        key = _extract_name(open_tag)
        new_body = _convert_body(body, key, hits)
        return f"{open_tag}{new_body}{close_tag}"

    new_content = RE_STRING_BODY.sub(_substitute, content)

    if apply:
        with open(xml_path, "w", encoding="utf-8") as f:
            f.write(new_content)
        print(f"applied: {len(hits)} substitutions in {xml_path}")
    else:
        for key, before, after in hits:
            print(f"[{key}] {before!r} -> {after!r}")
        print(f"total substitutions planned: {len(hits)}")

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Convert simple spaces to NBSP before FR strong punctuation in Android strings.xml.",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--dry-run",
        action="store_true",
        help="(défaut) liste les substitutions sans écrire",
    )
    mode.add_argument("--apply", action="store_true", help="écrit le fichier")
    parser.add_argument("path", help="chemin vers le fichier strings.xml")
    args = parser.parse_args()
    return process(args.path, apply=args.apply)


if __name__ == "__main__":
    sys.exit(main())
