#!/usr/bin/env python3
"""Chaque nom cité dans docs/ARCHITECTURE-LOGICIELLE.md existe-t-il encore dans le code ?

Le document promet que « chaque nom de classe, de route, de table et d'état cité ici existe dans
le code ». Une promesse qu'aucun contrôle ne tient s'use à la première classe renommée : la doc
continue d'affirmer, sans que personne ne voie qu'elle ment. Ce script relit chaque nom entre
accents graves et le cherche là où il doit vivre :

- un nom de type (`SupervisionService`) : une classe, un record, une interface ou une énumération
  déclarée sous src/main/java, ou un type d'une bibliothèque nommé dans EXTERNAL ;
- une table (`kex_task`) : un CREATE TABLE d'une migration Flyway — ou, même préfixe, un outil
  que Kex expose comme serveur MCP (`kex_status`), cité en chaîne dans le code ;
- une migration (`V4__durable_tasks`) : un fichier de db/migration ;
- un module de la console (`core.js`) : un fichier de static/assets ;
- une propriété (`kex.agent.tasks.enabled`) : un chemin d'application.yml, ou une chaîne citée
  dans le code Java — entière, ou en préfixe d'un `@ConditionalOnProperty(prefix = …)`.

    python3 scripts/check-architecture-doc.py

Code de sortie : 0 = tout est trouvé, 1 = au moins un nom n'existe plus (la liste est imprimée).
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOC = ROOT / "docs" / "ARCHITECTURE-LOGICIELLE.md"
JAVA = ROOT / "src" / "main" / "java"
RESOURCES = ROOT / "src" / "main" / "resources"

# Types de Spring, Spring AI, Resilience4j ou du JDK que le document nomme pour situer l'agent :
# ils ne sont pas déclarés ici, et ne doivent pas l'être.
EXTERNAL = {
    "AnthropicChatModel", "AnthropicException", "AuthenticationEntryPoint", "BeanPostProcessor", "ChatClient", "ChatMemory", "ChatModel", "ChatResponse",
    "CircuitBreakerOperator", "DataSourceAutoConfiguration", "FlywayAutoConfiguration",
    "McpSyncClient", "MessageChatMemoryAdvisor", "MessageDigest", "OpenAIException", "OpenAiChatModel",
    "QuestionAnswerAdvisor", "SimpleLoggerAdvisor", "SyncMcpToolCallback", "VectorStore",
}

TYPE = re.compile(r"^[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+$")
TABLE = re.compile(r"^kex_[a-z_]+$")
MIGRATION = re.compile(r"^V\d+__[a-z_]+$")
ASSET = re.compile(r"^[a-z][a-z0-9-]*\.(?:js|css)$")
PROPERTY = re.compile(r"^kex\.[a-z0-9.-]+[a-z0-9]$")


def declared_types(java_root: pathlib.Path) -> set[str]:
    pattern = re.compile(r"\b(?:class|record|interface|enum)\s+([A-Z][A-Za-z0-9]*)")
    names: set[str] = set()
    for path in java_root.rglob("*.java"):
        names.update(pattern.findall(path.read_text(encoding="utf-8")))
    return names


def yaml_paths(text: str) -> set[str]:
    """Chemins pointés des clés d'un YAML de configuration — une lecture par indentation suffit
    pour application.yml, et évite une dépendance que les exécuteurs de CI n'ont pas tous."""
    paths: set[str] = set()
    stack: list[tuple[int, str]] = []
    for raw in text.splitlines():
        line = raw.split(" #", 1)[0].rstrip()
        stripped = line.lstrip()
        if not stripped or stripped.startswith(("#", "- ", "---")):
            continue
        match = re.match(r"^([A-Za-z0-9_.\-\[\]\"']+):(?:\s|$)", stripped)
        if not match:
            continue
        indent = len(line) - len(stripped)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        key = match.group(1).strip("\"'")
        stack.append((indent, key))
        paths.add(".".join(part for _, part in stack))
    return paths


def cited(doc: str) -> list[str]:
    return sorted(set(re.findall(r"`([^`\n]+)`", doc)))


def missing(doc: str, root: pathlib.Path = ROOT) -> list[str]:
    java = root / "src" / "main" / "java"
    resources = root / "src" / "main" / "resources"
    types = declared_types(java) | EXTERNAL
    migrations = sorted((resources / "db" / "migration").glob("V*.sql"))
    tables = set()
    for path in migrations:
        tables.update(re.findall(r"CREATE TABLE(?: IF NOT EXISTS)?\s+([a-z_]+)", path.read_text(encoding="utf-8"), re.I))
    migration_names = {path.stem for path in migrations}
    assets = {path.name for path in (resources / "static" / "assets").glob("*") if path.is_file()}
    properties = yaml_paths((resources / "application.yml").read_text(encoding="utf-8"))
    java_text = "\n".join(path.read_text(encoding="utf-8") for path in java.rglob("*.java"))

    problems = []
    for name in cited(doc):
        if TYPE.match(name) and name not in types:
            problems.append(f"type introuvable : {name}")
        elif TABLE.match(name) and name not in tables and f'"{name}"' not in java_text:
            problems.append(f"table ou outil MCP introuvable : {name}")
        elif MIGRATION.match(name) and name not in migration_names:
            problems.append(f"migration introuvable : {name}")
        elif ASSET.match(name) and name not in assets:
            problems.append(f"module de console introuvable : {name}")
        elif PROPERTY.match(name) and name not in properties and f'"{name}"' not in java_text \
                and not any(path.startswith(name + ".") for path in properties) \
                and f'prefix = "{name.rsplit(".", 1)[0]}"' not in java_text:
            problems.append(f"propriété introuvable : {name}")
    return problems


def main() -> int:
    problems = missing(DOC.read_text(encoding="utf-8"))
    if problems:
        print(f"{DOC.relative_to(ROOT)} cite {len(problems)} nom(s) absent(s) du code :")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"{DOC.relative_to(ROOT)} : chaque nom cité existe dans le code.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
