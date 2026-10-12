#!/usr/bin/env python3
"""L'image de Kafka SQL Explorer que la stack tire : épinglée, la même partout, et à jour.

L'épinglage par défaut d'EXPLORER_IMAGE_TAG est écrit à la main dans trois fichiers
(docker-compose.yml, .env.example, docs/CONFIGURATION.md) et aucune publication de l'Explorer ne
vient le monter ici : il est resté sur 2.0.3 pendant que l'Explorer publiait le contrat que les
consignes de supervision décrivent, et rien ne l'a dit.

    python3 scripts/check-explorer-pin.py              # les trois fichiers disent la même version
    python3 scripts/check-explorer-pin.py --published  # … et Docker Hub n'en sert pas de plus récente

« Plus récente » veut dire la dernière poussée, pas le plus grand numéro : un tag se tape à la
main, et un tag erroné (`kex-agent-ai:9.9.23` pour 0.9.23) dépasserait toute vraie version.
Un registre injoignable est signalé, pas compté comme un échec : « on a demandé, c'est périmé »
et « on n'a pas pu demander » sont deux réponses différentes.
"""

from __future__ import annotations

import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
TAGS = "https://hub.docker.com/v2/repositories/compagnonsdudev/kafkaexplorer/tags?page_size=100"
SOURCES = {
    "docker-compose.yml": re.compile(r"kafkaexplorer:\$\{EXPLORER_IMAGE_TAG:-([^}]+)\}"),
    ".env.example": re.compile(r"^#?\s*EXPLORER_IMAGE_TAG=(\S+)", re.M),
    "docs/CONFIGURATION.md": re.compile(r"\|\s*`EXPLORER_IMAGE_TAG`\s*\|\s*`([^`]+)`"),
}


def version(tag: str) -> tuple[int, ...] | None:
    parts = tag.split(".")
    return tuple(int(p) for p in parts) if len(parts) == 3 and all(p.isdigit() for p in parts) else None


def newest_published() -> tuple[str | None, str | None]:
    try:
        with urllib.request.urlopen(TAGS, timeout=20) as response:
            payload = json.load(response)
    except (urllib.error.URLError, TimeoutError, ValueError, OSError) as error:
        return None, f"{type(error).__name__}: {error}"
    pushed = [(str(e.get("last_updated", "")), e["name"])
              for e in payload.get("results", []) if version(str(e.get("name", "")))]
    return (max(pushed)[1], None) if pushed else (None, "aucun tag X.Y.Z listé")


def main(argv: list[str]) -> int:
    pins: dict[str, str] = {}
    problems: list[str] = []
    for rel, pattern in SOURCES.items():
        match = pattern.search((ROOT / rel).read_text(encoding="utf-8"))
        if match is None:
            problems.append(f"{rel} : aucun épinglage d'EXPLORER_IMAGE_TAG trouvé")
        else:
            pins[rel] = match.group(1)
    if len(set(pins.values())) > 1:
        problems.append("épinglages divergents : " + ", ".join(f"{f} = {v}" for f, v in pins.items()))
    pinned = next(iter(pins.values()), None)
    if pinned and version(pinned) is None:
        problems.append(f"l'Explorer est épinglé sur « {pinned} », pas sur une version X.Y.Z")

    if "--published" in argv and pinned and not problems:
        newest, why_not = newest_published()
        if newest is None:
            print(f"::warning::Docker Hub injoignable ({why_not}) — l'épinglage {pinned} n'a pas été comparé")
        elif version(newest) > version(pinned):
            problems.append(f"la stack tire kafkaexplorer:{pinned} alors que Docker Hub sert {newest} — "
                            "monter EXPLORER_IMAGE_TAG dans les trois fichiers, après le contrat réel")
        else:
            print(f"Docker Hub sert kafkaexplorer:{newest} ; l'épinglage est à jour.")

    for problem in problems:
        print(f"  ✗ {problem}")
    if problems:
        return 1
    print(f"Kafka SQL Explorer épinglé sur {pinned}, identique dans {len(pins)} fichiers.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
