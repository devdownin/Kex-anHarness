#!/usr/bin/env python3
"""Les valeurs que cite le guide pédagogique sont-elles encore celles de la configuration ?

Le guide Word (docs/guide) cite des seuils et des délais : plancher de confiance, expiration
d'une décision, débit, compaction… Ils viennent de docs/guide/source/values.json. Ce script
vérifie deux choses :

1. chaque `value` de values.json est bien celle de application.yml — ou, à défaut, du
   `@DefaultValue` du record `@ConfigurationProperties` qui porte la propriété ;
2. le .docx versionné porte bien chaque `text` : un values.json corrigé sans régénérer le
   document laisserait le guide dire l'ancienne valeur.

    python3 scripts/check-guide-values.py

Code de sortie : 0 = guide à jour, 1 = au moins une valeur a dérivé (la liste est imprimée).
"""

from __future__ import annotations

import json
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
GUIDE = ROOT / "docs" / "guide"
VALUES = GUIDE / "source" / "values.json"
DOCX = GUIDE / "Kex-Agent-Guide-pedagogique.docx"
YAML = ROOT / "src" / "main" / "resources" / "application.yml"
JAVA = ROOT / "src" / "main" / "java"


def yaml_values(text: str) -> dict[str, str]:
    """Valeurs scalaires d'application.yml, par chemin pointé. `${VARIABLE:défaut}` rend le défaut :
    c'est lui que le guide décrit. Une lecture par indentation suffit à ce fichier, et évite une
    dépendance que les exécuteurs de CI n'ont pas tous."""
    values: dict[str, str] = {}
    stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        line = raw.split(" #", 1)[0].rstrip()
        stripped = line.lstrip()
        if not stripped or stripped.startswith(("#", "- ", "---")):
            continue
        match = re.match(r"^([A-Za-z0-9_.\-]+):(?:\s+(.*))?$", stripped)
        if not match:
            continue
        indent = len(line) - len(stripped)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, match.group(1)))
        value = (match.group(2) or "").strip().strip("\"'")
        if value:
            placeholder = re.fullmatch(r"\$\{[A-Z0-9_]+:(.*)\}", value)
            values[".".join(key for _, key in stack)] = placeholder.group(1) if placeholder else value
    return values


def java_default(prop: str, java_root: pathlib.Path) -> str | None:
    """`@DefaultValue` du composant qui porte la propriété, dans le record
    `@ConfigurationProperties` dont le préfixe la précède."""
    prefix, _, leaf = prop.rpartition(".")
    camel = re.sub(r"-([a-z])", lambda m: m.group(1).upper(), leaf)
    for path in java_root.rglob("*.java"):
        source = path.read_text(encoding="utf-8")
        if f'@ConfigurationProperties("{prefix}")' not in source:
            continue
        match = re.search(r'@DefaultValue\("([^"]*)"\)(?:\s+@\w+(?:\([^)]*\))?)*\s+[\w<>, ]+\s+' + camel + r"\b", source)
        if match:
            return match.group(1)
    return None


def docx_text(path: pathlib.Path) -> str:
    xml = zipfile.ZipFile(path).read("word/document.xml").decode("utf-8")
    return re.sub(r"<[^>]+>", "", xml)


def problems(values: dict, configuration: dict[str, str], java_root: pathlib.Path, document: str) -> list[str]:
    found = []
    for key, entry in values.items():
        actual = configuration.get(entry["property"]) or java_default(entry["property"], java_root)
        if actual is None:
            found.append(f"{key} : propriété introuvable — {entry['property']}")
        elif actual != entry["value"]:
            found.append(f"{key} : {entry['property']} vaut {actual} dans la configuration, "
                         f"{entry['value']} dans values.json")
        if entry["text"] not in document:
            found.append(f"{key} : « {entry['text']} » absent du document — le régénérer")
    if "{{" in document:
        found.append("le document contient un {{…}} non remplacé")
    return found


def main() -> int:
    values = json.loads(VALUES.read_text(encoding="utf-8"))["values"]
    found = problems(values, yaml_values(YAML.read_text(encoding="utf-8")), JAVA, docx_text(DOCX))
    if found:
        print(f"Le guide pédagogique a dérivé de la configuration ({len(found)}) :")
        for problem in found:
            print(f"  - {problem}")
        print("Corriger docs/guide/source/values.json, puis régénérer le document (docs/guide/README.md).")
        return 1
    print(f"Guide pédagogique : {len(values)} valeurs conformes à la configuration.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
