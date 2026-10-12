# Contribuer

## Avant d'ouvrir une pull request

```bash
./mvnw verify
```

JDK 21 au minimum. La suite ne sort pas sur le réseau et n'a besoin d'aucun secret : le profil `test` fournit
une clé factice et désactive le client MCP.

## Ce qui est attendu

- **Un test avec chaque correction.** Pas « un test quelque part » : celui qui aurait attrapé le
  défaut. Les tests de ce dépôt ont déjà trouvé trois bugs réels — le refus qui bloquait les sondes
  de santé, un seau à jetons dont le débit lent était faux, le flux qui perdait son identifiant.
- **Le commentaire dit pourquoi.** Le code dit déjà quoi. Un commentaire qui paraphrase la ligne
  suivante est du bruit ; celui qui donne la raison d'un choix non évident est ce qui évite qu'on
  le défasse dans six mois.
- **Pas d'élargissement silencieux.** Une PR fait une chose. Si vous trouvez autre chose en chemin,
  ouvrez une issue.

## Ce que `verify` impose

| Contrôle | Effet |
|---|---|
| Spotless | En-tête SPDX sur chaque fichier Java, imports triés, pas d'import inutilisé. `./mvnw spotless:apply` corrige |
| JaCoCo | Plancher de couverture : 85 % des instructions, 70 % des branches |
| CycloneDX | SBOM généré dans `target/classes/META-INF/sbom/` |

## Style

- Java 21 idiomatique : records, streams, `Optional`, switch sur types scellés.
- Injection par constructeur, jamais `@Autowired` sur un champ.
- Français pour commentaires, documentation et noms de tests.
- Anglais pour `README.md` ; toute modification s'y répercute dans `README.fr.md`.

## Sécurité

Ne pas ouvrir d'issue publique pour une faille : voir [SECURITY.md](SECURITY.md).

## Ce que la CI vérifie

| Job | Portée |
|---|---|
| `build` | `./mvnw verify` sur JDK 21 : format, licences, tests, couverture, SBOM |
| `mcp-interop` | Contrat du serveur MCP Kex, puis appel par le SDK MCP officiel |
| `browser` | Les suites Playwright de `src/test/browser/` contre un agent démarré (`first-run.mjs`, `console.mjs`…) |
| `docker` | Image construite, conteneur démarré sans serveur MCP, stack de fumée montée |
| `mcp-browser` | La console du serveur MCP Kex, serveur activé (`mcp-server.mjs`) |
| `stack` | La stack Kafka + Explorer + TimesFM, et son diagnostic (`scripts/forecast-stack.py`) |
| `explorer-contract` | Le contrat réel (`KafkaExplorerLiveContractTest`) contre l'image Kafka SQL Explorer épinglée, puis les captures du site (artefact `site-screenshots`, `site/capture.mjs`) |
| `repository-links` | Aucune URL vers l'ancien nom du dépôt, section Écosystème conforme, épinglage de l'Explorer identique partout, README EN et FR de même charpente |
| `analyze` | CodeQL |
| `scorecard` | OpenSSF Scorecard, hebdomadaire |
| `ecosystem` | Hebdomadaire : script Écosystème identique dans les trois dépôts ; une Explorer plus récente sur Docker Hub ouvre la PR qui monte l'épinglage (à fermer et rouvrir pour lancer la CI) |

`agent-evals` ne tourne qu'à la main : il appelle un vrai fournisseur de modèle, donc coûte.

Une PR ne part en revue que verte.
