# Journal des modifications

Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).
Versionnement [sémantique](https://semver.org/lang/fr/).

## [Non publié]

### Intégrations

- **MCP autoroutes optionnel** : surcouche Docker `compose/autoroutes.yml`, connexion `mcpautoroute`
  et bearer limité à son URL. L'agent reçoit une procédure de rapprochement entre preuves d'audit,
  événements et gares uniquement lorsque les outils sont autorisés : lecture de sa source,
  publication de son état complet avec garde de version et vérification de propagation. Les cycles
  de supervision en lecture seule restent sans publication automatique.

### CI

- **Contrat réel avec Kafka SQL Explorer à chaque pull request** : le job `explorer-contract`
  démarre Kafka et l'image Explorer épinglée (tirée de GHCR, pour ne pas dépendre du quota anonyme
  de Docker Hub), sème un topic et lance `KafkaExplorerLiveContractTest`, qui doit avoir tourné et
  non s'être sauté. Le test restait opt-in alors que c'est lui qui a trouvé le défaut de la 2.3.0.
- **Explorer épinglé en 2.3.1**, la version qui rend de nouveau facultatif le groupe d'une étape ;
  l'étape du contrat n'en nomme plus, comme le permet le contrat.
- `scripts/check-explorer-pin.py` : l'épinglage est le même dans les trois fichiers qui le portent
  (sur chaque PR) et, avec `--published`, n'est pas en retard sur Docker Hub (planification
  hebdomadaire `ecosystem.yml`, avec `check-ecosystem.py --siblings`, qui compare le script partagé
  à ses copies dans les deux autres dépôts).
- **PR automatique à chaque publication de l'Explorer** : le job `explorer-bump` d'`ecosystem.yml`
  lance `check-explorer-pin.py --bump`, qui monte les trois fichiers sur la dernière version
  poussée sur Docker Hub, et ouvre la PR `chore/explorer-<version>` ; `explorer-contract` y rejoue
  le contrat réel avant tout merge. Une PR ouverte par `GITHUB_TOKEN` ne déclenche pas la CI : la
  fermer puis la rouvrir.
- **Captures du site régénérées en CI** : une fois le contrat passé, `explorer-contract` démarre
  l'agent sur l'Explorer réel et `site/capture.mjs` (Playwright) photographie la vue d'ensemble et
  les intégrations ; l'artefact `site-screenshots` remplace les images de `site/` d'un copier.
- **Les captures de la page de présentation se rafraîchissent par PR** : après un merge sur main, le
  job `refresh-screenshots` compare celles que `explorer-contract` vient de prendre à `site/img/`
  (`scripts/compare-screenshots.py`, seuil de 3 % de pixels : deux prises à 20 s d'écart en
  diffèrent de 0 à 0,4 %) et ouvre ou met à jour la PR `chore/site-screenshots`. Une dérive non
  traitée ne fait pas dix PR, et un fichier illisible arrête le job au lieu de passer pour « stable ».
- **Correctif — `refresh-screenshots` s'arrêtait sur la dérive qu'il devait signaler.** GitHub lance
  les étapes en `bash -e` : `compare-screenshots.py | tee` renvoyait 1 (dérive, le cas attendu) et
  l'étape mourait avant de lire le code de sortie, donc sans ouvrir de PR. Le code se lit désormais
  hors pipe, `-e` suspendu le temps de l'appel ; vérifié en exécutant l'étape extraite du workflow
  sous `bash -e` (dérive, stable, et code 2 qui échoue toujours). Vu au premier merge sur main.
- **Correctif — un `gh` en échec ne se lit plus comme « une PR est déjà ouverte »** : le compte des PR
  ouvertes se faisait dans `[ "$(gh pr list …)" != "0" ]`, où un jeton refusé (HTTP 401) donnait une chaîne
  vide, donc « déjà ouverte », et l'étape sortait en vert sans PR. Le compte est lu dans une variable et
  son échec est une erreur qui nomme `AUTOMATION_PR_TOKEN`.
- **Les PR des workflows peuvent lancer la CI** : `explorer-bump` et `refresh-screenshots` lisent le
  secret facultatif `AUTOMATION_PR_TOKEN` (jeton à portée restreinte ou jeton d'App : contents et
  pull-requests en écriture). Sans lui, rien ne change : `GITHUB_TOKEN` ouvre la PR, qu'il faut
  fermer puis rouvrir.
- `scripts/check-readme-parity.py` (job `repository-links`) : `README.md` et `README.fr.md` ont
  les mêmes titres niveau par niveau, les mêmes blocs de code et des ancres de navigation vivantes.

### Documentation

- **README français au niveau de l'anglais** : « Pourquoi Kex-anHarness ? », « Points forts » et
  « Prêt pour Kafka dès l'installation » y manquaient. Le lien « How it works » / « Comment ça
  marche » de la navigation ne menait à aucun titre ; il mène à la Control Center.
- **Un slogan, partout le même** : « Kex-anHarness: governed AI agent with MCP tools, supervision and
  human approvals. » ouvre `DOCKERHUB.md` et sert de description au site ; le titre du site reprend
  l'accroche du README.
- Retrait des fichiers égarés à la racine (deux `.mht`, `frontskill.md`) ; `Redesign.md` devient
  `docs/REDESIGN-SKILL.md`, à côté de l'audit qui le cite.
- **Écosystème** : la section qui présente Kafka SQL Explorer, SpectraLLM et Kex-anHarness est
  générée par `scripts/check-ecosystem.py`, le même fichier dans les trois dépôts, et vérifiée par
  le job `repository-links`. Elle donne la commande qui lance les trois ensemble
  (`compose/ecosystem.yml` côté Kafka SQL Explorer) et le plan de ports commun : 8080 Explorer,
  8081 l'agent, 8088/8089 interface/API SpectraLLM, 9092 Kafka. L'API SpectraLLM que les exemples
  visent passe de 8082 à 8089, et `compose/spectrallm.yml` ne met plus en garde contre une
  collision sur 8081 : la pile spectra-hub ne publie plus ce port.
- **Site** : `site/` est publié sur GitHub Pages par `pages.yml` — page de présentation en anglais,
  captures de la Control Center réelle branchée sur Kafka SQL Explorer.

- README (anglais et français) et page Docker Hub alignés sur la console actuelle : les seize écrans
  dans l'ordre de la navigation (l'écran « Technique » n'existe plus, ses connexions MCP sont dans
  **Intégrations**), la mise en route d'une installation neuve, « Exécuter maintenant » désactivé
  avec son motif, les filtres limités aux vues qu'ils filtrent et leur période par défaut de 24 h.
- SpectraLLM : la page Docker Hub montre comment brancher la connexion par variables, et les deux
  README disent quels outils l'agent garde (`spectra_search`…) et pourquoi `spectra_ask` est refusé.
- Java 21 partout où Java 25 était annoncé (README français, Docker Hub, `CONTRIBUTING.md`,
  `CLAUDE.md`) : le pom, l'image et la CI sont en 21. La chaîne d'approvisionnement ne se dit plus
  « multi-arch » : l'image est publiée pour `linux/amd64`.
- `CONTRIBUTING.md` liste tous les jobs de CI (`browser`, `mcp-interop`, `mcp-browser`, `stack`,
  `repository-links`…), pas seulement les quatre premiers.
- Aide contextuelle de la console (Configuration) : analyse impossible, filtres Processus et
  Période, fonctionnalité désactivée, réponse en direct, documentation SpectraLLM.

### Modifié

- **Kafka SQL Explorer épinglé en 2.3.0** (au lieu de 2.0.3), dans `docker-compose.yml`,
  `.env.example` et `docs/CONFIGURATION.md`. La 2.0.3 ne portait ni les étapes typées ni le
  `measurementId` dont parlent les consignes de supervision ; le contrat réel
  (`KafkaExplorerLiveContractTest`) passe contre l'image 2.3.0. Une étape y nomme son groupe de
  consommateurs : la 2.3.0 l'exige à tort, l'Explorer le rend de nouveau facultatif à sa version
  suivante.

- SpectraLLM : l'agent ne propose plus au modèle que `spectra_search`, `spectra_list_documents` et
  `spectra_preview_document`. `spectra_ask` faisait rédiger la réponse par le LLM de SpectraLLM —
  deux modèles en série, un coût doublé, des sources que l'agent ne fixe plus — et `spectra_status`
  ne sert qu'au diagnostic. Les deux sont refusés par une règle `kex.agent.tools.rules`, réversible
  par `KEX_SPECTRA_ASK_DENIED=false` / `KEX_SPECTRA_STATUS_DENIED=false`. `docs/CONNAISSANCE.md`
  compare la base interne et SpectraLLM et dit quoi mettre où.

### Corrigé

- Tâches désactivées : le message nomme la propriété à changer (`KEX_AGENT_TASKS_ENABLED=true`) ;
  « Actualiser » et le panneau de détail vide disparaissent. Le panneau de détail ne s'affiche plus
  vide non plus quand aucune tâche n'est sélectionnée.
- Configuration : les sections suivent l'ordre de leurs onglets — le serveur MCP Kex, désactivé par
  défaut, ouvrait la page alors que son onglet est le dernier.
- La période par défaut du contexte opérateur passe d'une heure à 24 h : Audit et Décisions
  s'ouvraient vides dès que le dernier geste datait de la veille. « Réinitialiser » remet aussi les
  listes à jour quand l'instantané de supervision n'est pas encore lu.
- Connaissance : les descriptions parlent d'usage (`remember_fact`, « magasin vectoriel »,
  « locataire » retirés), et le lien vers la conversation porte un seul libellé.

- Un rendu périmé n'écrase plus un rendu récent : `render()` ignorait l'ordre des réponses, si bien
  qu'un sondage de fond parti avant une navigation ou un filtre pouvait, en arrivant après, remettre
  le bloc dans son état ancien jusqu'au sondage suivant. Plusieurs vérifications navigateur en
  échouaient par intermittence, sur `main` aussi.
- Mise en route : « 3/6 » devient « 3 étapes faites sur 6 », les étapes faites restent listées sous
  « Déjà fait », et « Connecter Kafka » dit par où passe la lecture.
- Conversation : plus de tiret seul à la place de l'identifiant, « Flux » devient « Réponse en
  direct » avec son explication, la saisie prend la police du texte, et les amorces occupent la
  zone vide d'une conversation qui n'a pas commencé.
- Agent : la politique se résume en actions (« 2 sous validation · 3 interdites sur 5 actions »),
  l'identifiant brut suit la préférence d'affichage des identifiants ; « Dernier cycle » dit
  « Jamais exécuté » au lieu d'un tiret.
- Une action principale désactivée perd l'accent : « Exécuter maintenant », inutilisable, restait
  l'élément le plus voyant de l'en-tête.

- « Lancer une analyse » sur une page vide (Cockpit incident, Décisions, Activité, À traiter, dernier
  cycle) n'était qu'un lien vers la Vue d'ensemble, même quand aucun cycle ne pouvait aboutir. Le
  bouton lance désormais le cycle sur place, ou, tant qu'un blocage tient, le nomme et mène à sa
  levée (« Joindre un serveur MCP », « Fournir la clé du modèle », « Préparer un processus ») — le
  même calcul que « Exécuter maintenant ».
- Pages vides : « Élargissez la période ou choisissez tous les processus » s'affichait sur une
  installation jamais analysée, filtres déjà sur « Tous ». Décisions, Activité, Alertes et Audit
  distinguent maintenant « aucun cycle exécuté », « rien dans ce filtre » (nommé, avec « Afficher
  toute la période ») et « rien du tout ».
- Les en-têtes triables des tableaux offrent une cible de 24 px (WCAG 2.5.8) au lieu de 14 px.
- Intégrations sans serveur : un seul état vide et un seul libellé, « Ajouter un serveur », au lieu
  de deux encadrés empilés et trois boutons pour le même geste ; la recherche n'apparaît qu'une fois
  un serveur déclaré.
- Vue d'ensemble : les filtres « Instance courante » passent sous les « Premiers pas », sur la même
  ligne que « Personnaliser le tableau de bord ».

- Système : « Jetons consommés » affichait le **double** des jetons réellement consommés —
  Spring AI incrémente `input`, `output` et `total`, et l'endpoint sans filtre les additionnait. La
  tuile lit désormais la seule série `total`, écrit « 0 depuis le démarrage » quand aucun appel n'a
  eu lieu et « non mesuré » quand la mesure est inaccessible, au lieu d'un « — » qui ne
  distinguait pas les deux.
- Les filtres « Instance courante » (Processus, Période) ne s'affichent plus que sur les huit vues
  qui les appliquent, sous leur titre ; ailleurs ils ne filtraient rien. Leurs listes ne
  s'écrivent plus en 10 px majuscules.
- Un seul gabarit d'en-tête pour toutes les vues — titre, une phrase, actions à droite — et un
  seul `h1` par vue ; Agent et Configuration n'en avaient aucun, Processus ou Audit le portaient dans
  un panneau. « Rafraîchir » devient « Actualiser » partout.
- À traiter : sur une file vide, l'action mise en avant est « Préparer un processus », plus
  « Actualiser ». Processus : la liste « Vues enregistrées » et son « Supprimer » rouge
  n'apparaissent qu'une fois une vue enregistrée.

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
