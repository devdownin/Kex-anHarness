# Kex Agent AI — carte pour les agents de code

Agent IA Spring Boot 4 / Spring AI 2 sur Java 21, client de serveurs MCP, branché par défaut sur le
serveur MCP de [Kafka SQL Explorer](https://github.com/devdownin/Kafkaexplorer). Il converse,
supervise des processus métier et agit sous une politique d'autonomie gouvernée par des humains.

Ce fichier est une carte, pas un manuel : il dit où se trouve chaque chose. Le détail vit dans
`docs/`, et un contrôle de CI (`scripts/check-agents-md.py`) vérifie que chaque lien d'ici mène
quelque part.

## Commandes

```bash
./mvnw verify                              # format, licences, tests, couverture, SBOM
./mvnw spotless:apply                      # corrige format et en-têtes avant de committer
./mvnw test -Dtest=NomDuTest               # un test
./mvnw spring-boot:run                     # agent sur 8081
docker compose -f compose/smoke.yml up -d --wait   # agent + serveur MCP factice

# Console au navigateur : l'agent doit tourner, Playwright est hors du projet
PLAYWRIGHT_MODULE=/chemin/playwright/index.mjs node src/test/browser/console.mjs
# La même, processeur ralenti ×8 : c'est ainsi qu'apparaissent les courses de test
KEX_CPU_THROTTLE=8 PLAYWRIGHT_MODULE=/chemin/playwright/index.mjs node src/test/browser/console.mjs

# Contrôles de la doc et des scripts (sans dépendance)
python3 scripts/check-agents-md.py && python3 scripts/check-architecture-doc.py
python3 scripts/check-guide-values.py && python3 -m unittest discover -s scripts/tests
```

JDK 21 au minimum (la CI et l'image tournent en 21).

## Structure

| Paquet | Contenu |
|---|---|
| `web/` | Contrôleur REST, sécurité, filtre de débit |
| `agent/` | Conversation : mémoire, appel bloquant, flux SSE, budget de jetons |
| `mcp/` | Introspection et invocation des serveurs MCP, catalogue, Kex comme serveur MCP |
| `config/` | `ChatClient`, propriétés, bearer MCP, seau à jetons, routage des modèles |
| `tools/` | Sélection et politique des outils |
| `harness/` | Compaction, résultats d'outils stockés, découverte, délégation |
| `knowledge/` | Base de connaissance optionnelle : magasin vectoriel, advisor, ingestion |
| `supervision/` | Cycle d'analyse, politique d'autonomie, décisions, validation humaine, audit |
| `execution/` | Tâches durables |
| `memory/`, `skills/` | Mémoire long terme, compétences, charte |
| `kafka/` | Vue technique du cluster : traduit les outils MCP d'Explorer, ne recalcule rien |
| `resources/static/` | La Control Center et l'espace utilisateur, sans étape de build |

## Conventions

- Français pour les commentaires, la documentation et les noms de tests ; anglais pour le
  `README.md` vitrine (`README.fr.md` en miroir).
- Commentaire = la raison d'un choix, pas la paraphrase du code.
- Injection par constructeur, records pour les DTO et les `@ConfigurationProperties`.
- Chaque correction de défaut arrive avec le test qui l'aurait attrapée.
- En-tête SPDX en tête de chaque fichier Java — `spotless:apply` le pose.
- Plancher de couverture : 85 % instructions, 70 % branches. `verify` échoue en dessous.

## Les règles qui ne se discutent pas

Chacune a coûté un défaut ; la raison est dans [`docs/PIEGES.md`](docs/PIEGES.md).

- Un état illisible vaut `UNKNOWN`, une mesure absente n'est jamais zéro, un relevé partiel ne
  prouve pas une absence — [Supervision](docs/PIEGES.md#supervision-décisions-et-mesures).
- Une anomalie sans outil cité n'est qu'une affirmation — [Supervision](docs/PIEGES.md#supervision-décisions-et-mesures).
- Le mode d'exécution et les planchers par capacité ne font que restreindre — [Supervision](docs/PIEGES.md#supervision-décisions-et-mesures).
- Ce que le modèle a écrit n'atteint jamais un outil d'écriture sans un humain — [Serveurs MCP](docs/PIEGES.md#serveurs-mcp).
- Pas de `@Scheduled` nu : tout ce qui part seul passe par un verrou — [Supervision](docs/PIEGES.md#supervision-décisions-et-mesures).
- `spring.ai.mcp.client.initialized` reste à `false` ; un serveur MCP s'adresse par sa clé de
  connexion — [Serveurs MCP](docs/PIEGES.md#serveurs-mcp).
- Pas de starter JDBC ni de bean `VectorStore` hors de leur profil ou propriété — [Démarrage](docs/PIEGES.md#démarrage-et-configuration).
- CSRF n'est levé que sur `/api/**` ; le `permitAll` de la console reste borné — [Sécurité](docs/PIEGES.md#sécurité-api-et-canaux-de-messagerie).
- Un test qui a besoin d'un état le simule, il ne le suppose pas — [Tests](docs/PIEGES.md#tests).

## Où lire

| Besoin | Document |
|---|---|
| Avant de toucher un domaine | [`docs/PIEGES.md`](docs/PIEGES.md) — les pièges, classés par domaine |
| Ce que le code est, composant par composant | [`docs/ARCHITECTURE-LOGICIELLE.md`](docs/ARCHITECTURE-LOGICIELLE.md) |
| Pourquoi il est ainsi (journal de décisions) | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Toutes les propriétés | [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) |
| Compaction, résultats d'outils, découverte, délégation | [`docs/HARNESS-CONTEXT.md`](docs/HARNESS-CONTEXT.md) |
| Mesurer le harnais avant d'en changer les défauts | [`docs/AGENT-EVALUATIONS.md`](docs/AGENT-EVALUATIONS.md) |
| Tâches durables | [`docs/DURABLE-TASKS.md`](docs/DURABLE-TASKS.md) |
| Serveurs MCP | [`docs/MCP.md`](docs/MCP.md) |
| Métriques, traces, audit | [`docs/OBSERVABILITE.md`](docs/OBSERVABILITE.md) |
| Présentation sans prérequis | [`docs/guide/README.md`](docs/guide/README.md) |

## Avant d'ouvrir une PR

1. Lire la section de [`docs/PIEGES.md`](docs/PIEGES.md) du domaine touché.
2. `./mvnw spotless:apply`, puis `./mvnw verify`.
3. Si la console change : les scripts de `src/test/browser` et `src/test/frontend` concernés.
4. Un défaut corrigé laisse une entrée dans [`docs/PIEGES.md`](docs/PIEGES.md), dans la section de
   son domaine — jamais ici : ce fichier reste une carte.
