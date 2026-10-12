#!/usr/bin/env python3
"""README.md et README.fr.md : la même charpente, et une navigation qui mène quelque part.

Le README anglais avait gagné trois sections (« Why », « Highlights », « Kafka ready ») que le
français n'a jamais reçues, et rien ne l'a dit : une traduction se vérifie mal, une structure
se compare. Ce script ne juge pas le texte, il compare ce qui doit coïncider d'une langue à
l'autre — les titres niveau par niveau, les blocs de code, les sections générées — et vérifie
que chaque ancre de la barre de navigation désigne un titre du même fichier.

    python3 scripts/check-readme-parity.py
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PAIR = ("README.md", "README.fr.md")
FENCE = re.compile(r"^```", re.M)
HEADING = re.compile(r"^(#{2,4}) (.+)$", re.M)
MARKER = re.compile(r"<!-- [\w-]+:(?:start|end) -->")
NAV_LINK = re.compile(r"\]\(#([^)]+)\)")


def anchor(title: str) -> str:
    """L'ancre que GitHub dérive d'un titre : minuscules, ponctuation et emoji retirés, espaces en tirets."""
    return re.sub(r"[^\w\- ]", "", title.strip().lower()).replace(" ", "-")


def outside_fences(text: str) -> str:
    return re.sub(r"^```.*?^```", "", text, flags=re.M | re.S)


def shape(text: str) -> dict[str, object]:
    prose = outside_fences(text)
    return {
        "niveaux de titre": [len(h) for h, _ in HEADING.findall(prose)],
        "blocs de code": len(FENCE.findall(text)) // 2,
        "sections générées": MARKER.findall(text),
    }


def broken_anchors(text: str) -> list[str]:
    anchors = {anchor(t) for _, t in HEADING.findall(outside_fences(text))}
    anchors |= {anchor(t) for t in re.findall(r"^# (.+)$", text, re.M)}
    return [a for a in NAV_LINK.findall(text) if a not in anchors]


def main() -> int:
    texts = {name: (ROOT / name).read_text(encoding="utf-8") for name in PAIR}
    problems = []
    shapes = {name: shape(text) for name, text in texts.items()}
    en, fr = (shapes[name] for name in PAIR)
    for key in en:
        if en[key] != fr[key]:
            problems.append(f"{key} : {PAIR[0]} {en[key]} ≠ {PAIR[1]} {fr[key]}")
    for name, text in texts.items():
        for missing in broken_anchors(text):
            problems.append(f"{name} : le lien #{missing} ne mène à aucun titre")
    for problem in problems:
        print(f"  ✗ {problem}")
    if problems:
        print("Les deux README ont divergé : reporter la section dans l'autre langue.")
        return 1
    print(f"{PAIR[0]} et {PAIR[1]} : {len(en['niveaux de titre'])} titres, "
          f"{en['blocs de code']} blocs de code, même charpente.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
