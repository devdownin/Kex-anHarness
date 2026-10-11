# Journal des modifications

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).
Versionnement [sémantique](https://semver.org/lang/fr/).

## [Non publié]

### Corrigé

- Console, premier démarrage : la mise en route passe en tête de la Vue d'ensemble (desktop et
  mobile), compte une connexion MCP comme faite seulement si elle est **joignable**, ajoute la clé
  du modèle, et dit pour chaque étape ce qui manque. « Exécuter maintenant » est désactivé tant
  qu'un cycle ne peut pas aboutir (pas de clé, aucun serveur MCP joignable, aucun processus), avec
  la raison en infobulle et au clic depuis la palette de commandes.
- Les messages « lecture indisponible » renvoyaient à une « page MCP » inexistante : ils nomment
  Intégrations et portent un lien direct vers la fiche de la connexion en cause.
- Mise en page : « Replier » ne déborde plus du rail, la zone de saisie de la conversation reste
  visible, l'en-tête mobile tient sur deux lignes sans perdre l'état de l'agent, la navigation
  basse montre l'entrée courante et signale qu'elle défile. `src/test/browser/first-run.mjs`
  verrouille chacun de ces points.
- Vocabulaire : les disjoncteurs se lisent « Normal », « Coupé après trop d'échecs », « En test
  après coupure » au lieu de `CLOSED`/`OPEN`/`HALF_OPEN` — `OPEN` se lisait « disponible ». Le mode
  d'exécution s'affiche « Supervisé », y compris dans le diff de politique ; un serveur MCP jamais
  contacté dit « Jamais joint » plutôt que « Pas de handshake ». La valeur brute reste en infobulle.
- Prévisions : quand la lecture est indisponible, le diagnostic s'ouvre seul et liste ses contrôles
  en clair (« Connexion à KafkaExplorer · Bloqué »), avec un lien vers la connexion ; la mise en
  garde sur les quantiles quitte le bandeau orange pour suivre les résultats.
- Vue d'ensemble : l'état de l'agent n'est plus répété dans le bandeau d'accueil (la pastille de
  l'en-tête le porte), et le résumé ne dit plus « aucune intervention requise » avant le premier
  cycle — il dit « pas encore analysé ».
- Serveur MCP Kex éteint (le défaut) : la page dit « Désactivé » et la variable à poser, au lieu
  d'une pastille « Indisponible », de six compteurs vides et d'un toast rouge « MCP HTTP 404 ».
- Mise en route : « Déclarer les processus surveillés » ouvre l'assistant guidé
  (`#/chat?creation=process`), et non plus la page Configuration où aucun processus ne se déclare.
- Libellés : « Attention » au lieu de « Warning » (filtres et pastilles), « Essais » au lieu de
  « Playground » ; la santé de l'instance se lit « En service · sondes : vivacité, disponibilité »,
  la réponse brute d'Actuator restant dépliable.
- Pile prévisions, étape démo : `POST /api/metrics` de KafkaExplorer répond `200` sans corps, et
  `scripts/forecast-stack.py` décodait toute réponse en JSON — la démo s'interrompait juste après
  avoir créé sa métrique, sur un « Diagnostic interrompu » sans cause. Une réponse vide vaut
  désormais `None`, et une interruption nomme sa cause (code HTTP et chemin, commande en échec,
  service injoignable…) sans URL complète ni secret.
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
