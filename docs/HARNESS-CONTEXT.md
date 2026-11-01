# Contexte, résultats paginés et spécialistes

Ces capacités reprennent quatre principes de Deep Agents dans la pile Spring AI existante :
résultats consultables, compaction du contexte, diagnostics isolés et découverte progressive.
Les quatre fonctions sont indépendantes et **désactivées par défaut**. Elles ne remplacent
ni les plans durables, ni les approbations, ni la vérification des postconditions.

## Configuration de départ

```yaml
kex:
  agent:
    tools:
      selection-enabled: true
      max-selected-tools: 6
      max-result-characters: 12000  # Minimum 1024 pour les outils du harness.
      rules:
        kafka_explorer__kex_consumer_lag:
          read-only: true
    harness:
      results:
        enabled: true
        retention: 1h
        tenant-results: 100
        tenant-characters: 10000000
        max-result-characters: 2000000
        page-characters: 4000
      compaction:
        enabled: true
        context-tokens: 64000
        trigger-fraction: 0.75
        output-reservation: 4096
        summary-tokens: 1024
        keep-recent-messages: 6
        max-history-characters: 2000000
      discovery:
        enabled: true
        max-results: 8
        keywords:
          kafka_explorer__kex_consumer_lag: [retard, consommation, saturation]
      delegation:
        enabled: true
        max-concurrent: 2
        max-calls: 3
        max-tool-calls: 8
        max-report-characters: 4000
        timeout: 30s
        specialists:
          kafka:
            instructions: >-
              Mesurer le lag et dater les observations. Distinguer valeur absente, zéro
              et couverture partielle. Ne proposer aucune mutation comme déjà autorisée.
            tools: [kafka_explorer__kex_consumer_lag]
```

Les noms ci-dessus sont des **exemples**. Utiliser les noms exacts des callbacks de votre
installation, connexion comprise. Aucun outil inconnu n'est créé par cette configuration.
Déclarer réellement en lecture seule les outils permis au spécialiste. Le suffixe du nom
n'est pas une preuve de lecture seule ; la règle administrateur et l'allowlist de la demande
doivent toutes deux l'autoriser.

## Résultats consultables

Un résultat trop long conserve son début et sa fin, avec un marqueur de troncature et son
SHA-256. Quand le stockage est activé et que les quotas le permettent, il porte aussi une
référence UUID à relire avec :

```json
{"id":"<référence>","offset":0,"limit":1000}
```

L'outil `read_tool_result` renvoie le contenu, les offsets de la page, `nextOffset`,
`hasMore`, la taille d'origine, le SHA-256 et les dates d'observation et d'expiration.
Les offsets comptent des unités UTF-16, comme les plafonds de texte existants. Les paires
Unicode sont conservées. Une page peut être réduite pour respecter le plafond JSON ;
toujours suivre `nextOffset`. Une limite trop petite pour un caractère est refusée.

Le résultat complet passe par les contrôles de contenu sensible **avant stockage**.
À la relecture, Kex recoupe le locataire, le périmètre de conversation et de rôles,
l'expiration, la présence actuelle de l'outil, l'allowlist, les règles d'arguments et
les règles de sortie actuelles. Un UUID n'accorde pas un droit de lecture.
Ces données restent balisées comme non fiables.

Un quota atteint ou un résultat dépassant `max-result-characters` produit une notice
explicite de résultat complet indisponible. Aucun nouvel appel métier n'est lancé
pour relire une page. Un résultat tronqué n'établit jamais une couverture exhaustive.
Sous un plafond de sortie trop faible pour porter une référence, l'aperçu reste seul.

Sans `shared-memory`, un fichier JSON privé est remplacé atomiquement ; il ne doit pas
être partagé entre processus. Son chemin par défaut est
`${user.home}/.kex-agent-ai/tool-results.json`. L'image Docker utilise
`/var/lib/kex/tool-results.json`, sur le volume existant.
Avec `shared-memory`, la migration V10 fournit le stockage JDBC et les quotas sont
sérialisés par locataire entre répliques. Elle élargit aussi l'identifiant de conversation
Spring AI à 128 caractères, pour accepter les SHA-256 de Kex, en conservant l'historique
existant. Les expirations sont filtrées et nettoyées
lors des lectures et écritures ; aucune purge périodique n'est promise pendant l'inactivité.
La suppression d'une conversation purge aussi ses résultats dans le périmètre courant.
Les sauvegardes du fichier ou de la base peuvent conserver des données après expiration.

## Compaction du contexte

Quand activée, la compaction remplace la fenêtre fixe de messages et la coupe par message.
L'historique passe par le dépôt existant de conversation. À chaque tour fournisseur,
Kex estime le coût textuel des messages, des contrats d'outils et du schéma de sortie,
avec une réservation de sortie. L'estimation conservative utilise les octets UTF-8 ;
ce n'est pas un tokenizer propre à chaque modèle ni une garantie pour le multimodal.

Configurer `context-tokens` selon la **plus petite fenêtre** des modèles et destinations
de secours utilisés, et `output-reservation` au moins au plus grand plafond de sortie
de ces destinations. Le seuil de 75 % est un réglage de départ à évaluer sur vos tâches.
Quand le seuil est atteint, les anciens messages sont résumés ; les messages récents
restent intacts. Le résumé demande l'objectif initial, les contraintes et corrections,
les sources datées, les incertitudes, les références de résultats et le travail restant.
Il est persisté comme message utilisateur balisé `UNVERIFIED`, jamais comme instruction
système ou connaissance vérifiée. Le prompt de gouvernance reste intact.

Le résumé utilise le même chemin modèle budgété, le même locataire, le même identifiant
de budget de tâche et la garde d'annulation. Aucun outil n'est proposé au modèle de résumé.
Les écritures sont sérialisées et le préfixe est comparé avant remplacement ; un résumé
calculé avant une purge ou une modification concurrente ne ressuscite pas l'ancien contenu.
Les tours suivants de la même boucle réutilisent le résumé au lieu de le recalculer.

Un budget refusé reste un refus. Une fenêtre trop petite pour les instructions, la demande
récente, les schémas ou les appels d'outils indivisibles produit une erreur explicite.
Kex ne supprime pas la dernière demande pour faire passer l'appel. Le plafond
`max-history-characters` empêche une croissance illimitée si aucune compaction n'est possible.
La fidélité du résumé reste dépendante du modèle : vérifier les contraintes critiques
et mesurer les pertes d'information avant une activation large.

## Découverte et suivis

La sélection initiale tient compte de la demande et des derniers échanges, dans une
fenêtre de 4 000 caractères. Le classement normalise les accents et connaît quelques
équivalents français/anglais : retard/lag, consommateur/consumer, prévisions/forecast…
Les synonymes `keywords` sont configurés par l'administrateur par nom exact d'outil.
Les descriptions externes ne participent pas au score.

`discover_tools` recherche dans le catalogue autorisé et expose des contrats bornés.
`invoke_discovered_tool` accepte un nom effectivement exposé pendant cet échange et
un objet `arguments`. Il résout à nouveau l'outil autorisé avant l'appel. Un contrat
trop volumineux n'est pas présenté à moitié comme un contrat utilisable.
La découverte est un choix de contexte, jamais une extension de permission.
Une allowlist vide ne donne accès à aucun de ces outils.

## Diagnostics isolés

`delegate_diagnostic` reçoit uniquement le nom d'un spécialiste configuré et son objectif.
Il utilise un nouveau ChatClient sans advisor de mémoire ni historique parent.
Ses callbacks sont l'intersection des outils déclarés pour ce spécialiste, des droits
de la demande racine, des outils actuellement disponibles et des règles de lecture seule.
La lecture seule est recontrôlée à chaque invocation. Aucun outil de délégation ou de
découverte n'est donné au spécialiste ; il ne peut pas créer de sous-spécialiste.

L'identité du locataire, le budget de tâche et la collecte d'événements sont propagés.
Une garde enfant suit l'annulation de la racine et dispose de son propre délai.
Les plafonds portent sur les délégations par échange, les appels d'outils par diagnostic,
la longueur du rapport et les diagnostics simultanés **par instance**. Le budget partagé
reste applicable entre instances ; le sémaphore de concurrence n'est pas distribué.
Un fournisseur qui ignore l'interruption peut terminer plus tard ; la garde refuse
tout nouvel outil et le slot n'est libéré qu'à la fin effective du worker.

Le rapport porte `UNVERIFIED`, le nombre d'appels et un indicateur de troncature.
Les erreurs et délais ne deviennent jamais une réussite métier. Une mutation envisagée
doit repasser par les plans, approbations et vérificateurs existants.

## Vérification

```sh
./mvnw -Dtest=StoredToolResultsTest,ContextCompactorTest,HarnessToolsTest,JdbcHarnessTest,ToolSelectionServiceTest test
./mvnw verify
```

Les tests déterministes couvrent les frontières d'accès, la relecture après reconstruction,
les quotas partagés sur H2 avec la migration réelle, les offsets Unicode, la conservation
du contexte, le budget et l'isolation des diagnostics. Aucun fournisseur LLM payant
n'est appelé par ces tests. La qualité des résumés et diagnostics exige aussi vos évaluations
sur un modèle réel ; aucun gain de coût, de latence ou de qualité n'est présumé.
