#!/usr/bin/env python3
"""AGENTS.md reste-t-il une carte, et chacun de ses liens mène-t-il quelque part ?

Le fichier d'instructions des agents de code avait grossi jusqu'à 594 lignes, dont 98 pièges :
chaque session le relisait en entier, et un nouveau piège s'ajoutait toujours à la fin, loin des
autres pièges de son domaine. AGENTS.md est désormais une carte courte qui renvoie vers la doc ;
les pièges vivent dans docs/PIEGES.md, classés par domaine. Ce script vérifie que cela tient :

- AGENTS.md ne dépasse pas MAX_LINES lignes, et CLAUDE.md l'importe (`@AGENTS.md`) sans
  redevenir un second manuel ;
- chaque lien relatif d'AGENTS.md et de docs/PIEGES.md mène à un fichier existant, et chaque ancre
  `#…` à un titre de ce fichier (même calcul que GitHub, accents compris) ;
- le sommaire de docs/PIEGES.md annonce exactement le nombre d'entrées de chaque section, et deux
  entrées n'y portent pas le même titre.

    python3 scripts/check-agents-md.py

Code de sortie : 0 = tout tient, 1 = au moins un problème (la liste est imprimée).
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAX_LINES = 150
CLAUDE_MAX_LINES = 20


def slug(title: str) -> str:
    """L'ancre que GitHub donne à un titre : minuscules, ponctuation retirée, accents gardés."""
    return re.sub(r"[^\w\- ]", "", title.strip().lower()).replace(" ", "-")


def anchors(text: str) -> set[str]:
    return {slug(match) for match in re.findall(r"^#{1,6}\s+(.+?)\s*$", text, re.M)}


def links(text: str) -> list[str]:
    # Les blocs de code ne contiennent pas de liens : une commande citée n'est pas une cible.
    prose = re.sub(r"```.*?```", "", text, flags=re.S)
    return re.findall(r"\]\(([^)\s]+)\)", prose)


def broken_links(path: pathlib.Path, root: pathlib.Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    problems = []
    for target in links(text):
        if re.match(r"^[a-z]+:", target):
            continue
        file_part, _, anchor = target.partition("#")
        destination = path if not file_part else (path.parent / file_part).resolve()
        name = path.relative_to(root)
        if not destination.exists():
            problems.append(f"{name} : lien vers {target}, fichier introuvable")
        elif anchor and destination.is_file() and anchor not in anchors(destination.read_text(encoding="utf-8")):
            problems.append(f"{name} : lien vers {target}, ancre introuvable")
    return problems


def pitfall_sections(text: str) -> dict[str, list[str]]:
    """Titres en gras des entrées, par section de niveau 2 (hors sommaire)."""
    sections: dict[str, list[str]] = {}
    current = None
    for line in text.splitlines():
        heading = re.match(r"^## (.+?)\s*$", line)
        if heading:
            current = heading.group(1)
            if current != "Sommaire":
                sections[current] = []
            else:
                current = None
        elif current and line.startswith("- **"):
            sections[current].append(re.match(r"- \*\*(.+?)(?:\*\*|$)", line).group(1))
    return sections


def summary_problems(text: str) -> list[str]:
    problems = []
    sections = pitfall_sections(text)
    announced = dict(re.findall(r"^- \[([^\]]+)\]\(#[^)]+\) — (\d+)\s*$", text, re.M))
    for title, entries in sections.items():
        if title not in announced:
            problems.append(f"docs/PIEGES.md : la section « {title} » manque au sommaire")
        elif int(announced[title]) != len(entries):
            problems.append(f"docs/PIEGES.md : « {title} » annonce {announced[title]} entrées, en compte {len(entries)}")
    for title in announced:
        if title not in sections:
            problems.append(f"docs/PIEGES.md : le sommaire annonce « {title} », section absente")
    seen: dict[str, str] = {}
    for title, entries in sections.items():
        for entry in entries:
            if entry in seen:
                problems.append(f"docs/PIEGES.md : entrée en double — « {entry} » ({seen[entry]}, {title})")
            seen[entry] = title
    return problems


def problems(root: pathlib.Path = ROOT) -> list[str]:
    agents = root / "AGENTS.md"
    claude = root / "CLAUDE.md"
    pitfalls = root / "docs" / "PIEGES.md"
    found = []
    for path in (agents, claude, pitfalls):
        if not path.exists():
            found.append(f"{path.relative_to(root)} introuvable")
    if found:
        return found
    agents_lines = agents.read_text(encoding="utf-8").count("\n")
    if agents_lines > MAX_LINES:
        found.append(f"AGENTS.md fait {agents_lines} lignes (plafond {MAX_LINES}) : c'est une carte, le détail va dans docs/")
    claude_text = claude.read_text(encoding="utf-8")
    if not re.search(r"^@AGENTS\.md\s*$", claude_text, re.M):
        found.append("CLAUDE.md n'importe pas AGENTS.md (ligne « @AGENTS.md »)")
    if claude_text.count("\n") > CLAUDE_MAX_LINES:
        found.append(f"CLAUDE.md dépasse {CLAUDE_MAX_LINES} lignes : il redevient un second manuel")
    found += broken_links(agents, root) + broken_links(pitfalls, root)
    found += summary_problems(pitfalls.read_text(encoding="utf-8"))
    return found


def main() -> int:
    found = problems()
    if found:
        print(f"La carte des agents ne tient plus ({len(found)}) :")
        for problem in found:
            print(f"  - {problem}")
        return 1
    print("AGENTS.md : carte courte, liens et ancres valides ; docs/PIEGES.md : sommaire exact.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
