# Stack locale complète KafkaExplorer + TimesFM

Prérequis : Docker avec Compose v2, BuildKit, Git, Python 3 et OpenSSL. Prévoir 4 CPU et 8 Gio
pour TimesFM **en plus** de Kafka, des deux JVM, PostgreSQL et des constructions.
Le premier démarrage télécharge le modèle épinglé et vérifie son SHA-256 ; sa durée
dépend de la connexion. KafkaExplorer et TimesFM sont construits depuis la même
révision Git épinglée dans `compose/forecasts.yml`.

```sh
cp .env.example .env
# Renseigner ANTHROPIC_API_KEY, ou le fournisseur OpenRouter et sa clé dans .env.
bin/forecast-stack.sh
```

Le script génère et conserve les secrets MCP, API agent, PostgreSQL et TimesFM dans
`.forecast-stack/.env` (permissions 0600). Ce fichier complète `.env` et prend la priorité
pour ces secrets. Il ne remplace ni les clés LLM ni la configuration approuvée.
La clé API à saisir dans l'agent est `KEX_AGENT_API_KEY` de `.forecast-stack/.env`.

- KafkaExplorer : http://localhost:8080.
- Kex-anHarness : http://localhost:8081, page **Prévisions**.
- Kafka : localhost:9092 ; les conteneurs utilisent kafka:29092.
- PostgreSQL et TimesFM : aucun port publié sur l'hôte.

`bin/forecast-stack.sh --prepare-only` prépare secrets et répertoire de configuration
sans démarrer les conteneurs. Pour utiliser un checkout local de KafkaExplorer,
renseigner `KAFKAEXPLORER_BUILD_CONTEXT=../Kafkaexplorer` et
`TIMESFM_BUILD_CONTEXT=../Kafkaexplorer/services/timesfm` dans `.env`.

## Configurer les prévisions

L'infrastructure démarre avec les cinq outils MCP de prévision actifs et un catalogue
vide. L'historique reste désactivé jusqu'à l'approbation des métriques ; aucun identifiant,
source ou historique artificiel n'est ajouté automatiquement.

1. Créer ou sélectionner une métrique compatible dans KafkaExplorer.
2. Dans **Metrics Forecast**, utiliser **Configure a forecast**, déclarer les identités
   stables du cluster et du collecteur, les sources et l'environnement `local`.
3. Valider puis exporter le YAML. Enregistrer le fichier dans
   `.forecast-stack/config/forecasts.yml` ; les paramètres PostgreSQL et TimesFM sont
   déjà fournis par le Compose.
4. Relancer `bin/forecast-stack.sh` pour recharger cette configuration dans KafkaExplorer.
5. Attendre l'historique valide : 512 points à une minute représentent 512 minutes.
   Les premiers résultats sont en SHADOW ; l'activation demande une validation distincte.

Pour un autre environnement, définir `FORECAST_ENVIRONMENTS` dans
`.forecast-stack/.env` conformément aux sources approuvées. La clé MCP est partagée
automatiquement avec l'agent ; les lectures MCP ne déclenchent pas d'inférence.

## Vérifier et arrêter

```sh
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml config --quiet
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml ps
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml logs timesfm explorer agent
docker compose --env-file .env --env-file .forecast-stack/.env -f docker-compose.yml -f compose/forecasts.yml down
```

Si `.env` n'existe pas et que la clé LLM est exportée, omettre `--env-file .env`.
Utiliser `config --quiet` pour ne pas afficher les secrets interpolés.
Les volumes conservent données Kafka, état de l'agent, historique PostgreSQL et modèle.
`down -v` supprime ces données. Les ports web restent liés à la boucle locale par défaut.

## Démonstration et diagnostic

```sh
bin/forecast-stack.sh --demo
bin/forecast-diagnose.sh
```

Le mode `--demo` lance le service du profil Compose `forecast-demo`, puis crée
la métrique via l’API KafkaExplorer. Il crée le topic `forecast.demo.orders`, trois messages, le groupe
`forecast-demo` et une métrique `CONSUMER_TIME_LAG` nommée `forecast-demo-lag`.
Une relance conserve la métrique existante et ajoute trois messages de démonstration.
Utiliser une stack locale dédiée ; ce mode écrit des données Kafka et une configuration
métrique, mais ne valide pas les sources et ne fabrique pas d'historique.

Le démarrage indique quatre étapes : validation, construction, démarrage/préchargement,
puis vérification des services et de MCP. Compose affiche le téléchargement du modèle.

## Lire le diagnostic

`bin/forecast-diagnose.sh` (et l'attente de `bin/forecast-stack.sh`, qui le rejoue toutes les
cinq secondes) contrôle la pile **de bas en haut** : chaque ligne suppose que les précédentes
tiennent. Lire donc de haut en bas et corriger le **premier** `BLOQUÉ` — les suivants en sont
souvent la conséquence. Une pile saine, avant toute approbation, ressemble à ceci :

```text
OK · kafka · healthy
OK · forecast-postgres · healthy
OK · forecast-model-prefetch · exited
OK · timesfm · healthy
OK · explorer · healthy
OK · agent · healthy
OK · PostgreSQL
OK · TimesFM prêt
OK · Authentification et cinq outils MCP
OK · Connexion MCP de l’agent
À CONFIGURER · Séries autorisées · ouvrir Metrics Forecast, approuver et exporter les sources.
```

| Ligne | Ce qui est réellement vérifié |
|---|---|
| `kafka` … `agent` | L'état Compose de chaque conteneur ; `forecast-model-prefetch` doit être **terminé** avec le code 0 (il ne sert qu'à télécharger le modèle) |
| `PostgreSQL` | Une vraie requête `SELECT 1` authentifiée, pas seulement le port ouvert |
| `TimesFM prêt` | `/health/ready` depuis l'intérieur du conteneur : le modèle est chargé, pas seulement le processus lancé |
| `Authentification et cinq outils MCP` | Deux preuves sur `/mcp` de l'Explorer : sans jeton il **refuse** (`401`), avec le jeton il ouvre une session MCP (`initialize`) puis annonce les cinq outils de prévision |
| `Connexion MCP de l’agent` | Le point de vue de l'agent (`/api/agent/forecasts/readiness`) : il joint l'Explorer, voit les cinq outils, a le droit de les appeler et lit un catalogue **complet** |
| `Séries autorisées` | Combien de séries approuvées l'agent voit. `À CONFIGURER` n'est pas une panne : l'infrastructure est saine, il reste à approuver des sources |

Le code de retour est non nul dès qu'une ligne est `BLOQUÉ` ; `À CONFIGURER` ne le rend pas non
nul. Aucun secret n'est affiché, et les ports personnalisés sont lus dans la configuration Compose
effective.

### Quand une ligne est bloquée

| Symptôme | Cause probable | Que faire |
|---|---|---|
| Un service `BLOQUÉ · … · starting` qui dure | Démarrage encore en cours (le premier téléchargement du modèle est long) | Attendre ; suivre `docker compose … logs -f timesfm` |
| `forecast-model-prefetch` en échec | Pas d'accès Internet, disque plein, empreinte du modèle refusée | Lire ses logs ; libérer de l'espace ; relancer |
| `HTTP 426` sur la ligne MCP | L'Explorer exige TLS alors que la pile parle HTTP | Vérifier `EXPLORER_MCP_REQUIRETLS=false` (voir les noms de variables plus bas) |
| `HTTP 401` sur la ligne MCP | Le jeton envoyé n'est pas celui de l'Explorer | Les deux services doivent lire le même `EXPLORER_MCP_AUTH_TOKEN` de `.forecast-stack/.env` |
| `HTTP 400` sur la ligne MCP | Requête MCP hors session | Ne doit plus arriver avec le script actuel ; un appel fait à la main doit commencer par `initialize` (voir [MCP.md](MCP.md#interroger-un-serveur-à-la-main--la-session-dabord)) |
| `Forecast MCP tool catalogue incomplete` | L'Explorer construit n'expose pas les cinq outils | Vérifier la révision épinglée et `EXPLORER_FORECASTING_PILOT_ENABLED=true` |
| `BLOQUÉ · Connexion MCP de l’agent` | Voir la ou les lignes indentées qui suivent : elles nomment le contrôle en échec (`connection`, un outil, `visible-series`) et le motif | Traiter le contrôle nommé ; `Découverte MCP indisponible` renvoie à l'URL et au jeton, un outil `BLOCKED` à la politique d'outils de l'agent |

Un script peut aussi s'arrêter **avant** d'avoir pu diagnostiquer, par exemple pendant `--demo` :
il affiche alors `Diagnostic interrompu : <cause>` sur la sortie d'erreur. La cause dit où
regarder sans jamais recopier d'URL complète ni de variable d'environnement :

| Cause affichée | Ce qu'elle veut dire |
|---|---|
| `HTTP 4xx sur /api/…` | L'Explorer a refusé l'appel nommé ; ses logs donnent le motif |
| `commande Docker Compose en échec (code N) …` | Une commande `docker compose` a échoué ; sa dernière ligne d'erreur suit |
| `service injoignable (…)` | Le port publié ne répond pas : service arrêté ou port personnalisé |
| `métrique de démonstration absente des candidats KafkaExplorer` | La métrique `forecast-demo-lag` n'a pas été créée ou n'est pas listée |
| `ValueError : Demo metric is not eligible…` | La métrique existe mais l'Explorer la refuse : lire ses `blockers` dans **Metrics Forecast** |

Un exemple de ligne détaillée, et ce qu'il faut en lire :

```text
BLOQUÉ · Connexion MCP de l’agent · vérifier les permissions et le token.
  visible-series · BLOCKED · kex_list_forecastable_metrics : enveloppe de couverture invalide
```

L'agent joint l'Explorer et voit les outils (sinon `connection` serait la ligne en échec) ; c'est
la **lecture** du catalogue qu'il refuse. C'est ce que produisait l'agent tant qu'il exigeait un
champ `coverage.complete` que l'Explorer n'émet pas — expliqué dans
[Lire l'enveloppe `coverage`](MCP.md#lire-lenveloppe-coverage-de-kafka-sql-explorer).

La page **Prévisions** de l'agent présente un parcours dépliable « Créer ma première
prévision ». La progression réelle des 512 points reste visible dans Metrics Forecast
chez KafkaExplorer ; elle n'est pas estimée à partir de la durée de fonctionnement.

## Vérification CI

Le workflow **Forecast stack** construit les vrais composants, télécharge le modèle
épinglé, démarre une stack isolée, crée la métrique démo et vérifie santé, PostgreSQL,
TimesFM, authentification MCP/agent et découverte des cinq outils. Il ne requiert
aucune clé LLM réelle et n'appelle pas le LLM. Le catalogue doit rester vide avant
approbation. Il ne prétend pas mesurer la justesse des prévisions ou la qualité du modèle.
Les volumes CI sont supprimés après le job ; les volumes locaux sont conservés.

### Noms des variables Spring

Les clés Docker suivent la conversion Spring : points remplacés par `_`, tirets
supprimés, puis majuscules. Ainsi `explorer.mcp.require-tls` devient
`EXPLORER_MCP_REQUIRETLS`, et non `EXPLORER_MCP_REQUIRE_TLS`. La stack locale
positionne cette propriété à `false` car ses échanges MCP se font en HTTP.
Le diagnostic signale explicitement HTTP 426 si le serveur exige encore TLS.
Les URLs JDBC/inférence et la liste des environnements suivent la même convention.
