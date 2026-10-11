# Journal des modifications

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).
Versionnement [sémantique](https://semver.org/lang/fr/).

## [Non publié]

### Corrigé

- Pile prévisions (`bin/forecast-stack.sh`, workflow `Forecast stack`) : la vérification MCP
  envoyait `tools/list` sans ouvrir de session, ce que le transport Streamable HTTP de
  l'Explorer refuse en `400` — le diagnostic restait `BLOQUÉ` sur une pile saine. Elle passe
  désormais par `initialize`, et un blocage de la connexion MCP de l'agent nomme le contrôle
  en échec au lieu d'une consigne générique.
- Lectures de prévisions : l'agent exigeait un booléen `coverage.complete` que KafkaExplorer
  n'émet pas (c'est une méthode de son record `Coverage`, seul `stopReason` est sérialisé). Toute
  réponse réelle était refusée comme « enveloppe de couverture invalide » ; la complétude se
  déduit désormais de `stopReason == EXHAUSTED`.
- Vérification des actions (`ActionVerification`) et des tâches durables (`TaskService`) : même
  défaut, une preuve de KafkaExplorer n'était jamais jugée complète faute de `coverage.complete`,
  si bien qu'aucune postcondition mesurée par lui ne pouvait confirmer une action ou une tâche.
  `EXHAUSTED` suffit désormais ; un `complete` présent doit valoir `true`.

### Documentation

- `docs/MCP.md` : interroger un serveur MCP à la main (la session d'abord, et ce que disent
  `401`/`426`/`400`/`404`), lire l'enveloppe `coverage` de Kafka SQL Explorer, vérifier pas à pas
  la connexion SpectraLLM.
- `docs/FORECAST-STACK.md` : lire le diagnostic ligne par ligne, et que faire quand l'une est
  bloquée.
- `docs/ACTION-VERIFICATION.md`, `docs/DURABLE-TASKS.md` : la règle de complétude réelle, sans
  `complete` obligatoire ; `docs/ARCHITECTURE.md` : enveloppe d'outil contre enveloppe du modèle.
- `README.md` / `README.fr.md` : SpectraLLM en exemple complet de second serveur MCP.

### Ajouté

- Serveur MCP de SpectraLLM (0.10.0+) : surcouche `compose/spectrallm.yml` qui ajoute la
  connexion `spectrallm` et ses outils `spectra_*` en lecture seule. Les entrées
  `kex.mcp.bearer-tokens` acceptent un `header` : SpectraLLM lit sa clé dans `X-API-Key`, pas
  dans un porteur.

- Associations TimesFM persistées depuis le wizard et les fiches processus (ADMIN), avec
  validation du catalogue, diagnostic des prérequis et aperçu daté des risques/qualité.
  Stockage fichier en mono-instance et migration PostgreSQL V3 pour les répliques partagées.

- Wizard de création de processus : choix TimesFM facultatif dans un catalogue complet et
  autorisé, hint contextualisé, export YAML de l’association explicite par série/environnement.
  Documentation MCP/configuration et exemples opérationnels complétés.

- Intégration TimesFM via les cinq outils MCP KafkaExplorer en lecture seule : vue Prévisions
  par environnement, historique, quantiles, qualité réalisée, baselines et dépassements prédits.
  Brouillon de conversation contextuel, contrôle de provenance, états SHADOW/expiré/repli,
  tests de contrat et navigateur, guide `docs/TIMESFM.md`.

- Base de connaissance optionnelle (RAG) : ingestion, recherche et purge par API, recherche
  automatique avant chaque échange. Éteinte par défaut.
- Événements `tool` dans le flux SSE et champ `tools` sur la réponse bloquante.
- Route de sortie structurée contre un schéma JSON fourni par l'appelant.

- Agent conversationnel Spring Boot 4 / Spring AI 2 sur Java 25, client de serveurs MCP
  (stdio, SSE, streamable-HTTP).
- Connexion par défaut au serveur MCP de Kafka SQL Explorer, avec injection du bearer restreinte au
  préfixe d'URL déclaré.
- API REST : chat synchrone, chat en flux SSE, purge de conversation, introspection des serveurs
  MCP, appel direct d'un outil, listage et lecture de ressources.
- Authentification par bearer sur `/api/**`, fermée par défaut (`503` sans clé configurée).
- Limite de débit sur les routes de chat, plafond de 20 appels d'outils par échange, plafond de
  durée d'un échange.
- Métriques de coût et d'outils exposées en Prometheus, endpoint authentifié.
- Mémoire de conversation partagée en option (profil `shared-memory`, PostgreSQL).
- Stacks Docker Compose : stack complète avec Kafka et l'Explorer, surcouche mémoire partagée,
  stack de fumée pour la CI.
- Documentation : README bilingue, architecture, guide MCP, configuration, observabilité.
- CI : tests, construction et démarrage de l'image, stack de fumée montée, CodeQL, Dependabot,
  publication d'image multi-arch sur GHCR au tag.
