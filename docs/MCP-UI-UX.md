# UI/UX MCP dans Kex-anHarness

Ce document décrit les parcours utilisateur associés à MCP dans le Control Center de Kex-anHarness. Il complète [MCP.md](MCP.md), qui détaille le protocole, les transports et la configuration technique.

## Objectif

L’interface doit permettre de comprendre MCP sans exiger de connaître immédiatement les transports, les propriétés Spring ou JSON-RPC. Deux usages doivent être distingués dès le premier écran :

1. **Connecter Kex à des services MCP** : Kex agit comme client MCP et consomme les outils, ressources ou prompts exposés par des serveurs externes.
2. **Connecter des clients à Kex** : Kex expose son propre serveur MCP à des applications clientes.

Le Control Center présente ces deux directions dans un point d’entrée commun **MCP & intégrations**, puis dirige l’utilisateur vers le parcours approprié.

## Connecter Kex à des services MCP

L’espace des connexions externes affiche d’abord l’état opérationnel : nombre de connexions, connexions prêtes, connexions à vérifier, fraîcheur du dernier relevé et activité récente.

Une connexion peut être ajoutée, modifiée, désactivée, actualisée, diagnostiquée ou retirée. Le catalogue d’outils est récupéré sans exécuter d’outil.

### Assistant de connexion

L’assistant demande d’abord **où se trouve le serveur** plutôt que d’exposer directement le transport MCP :

| Contexte | Usage | Configuration proposée |
|---|---|---|
| **Sur ce PC** | Serveur local lancé par Kex | `stdio` |
| **Réseau privé** | Service sur LAN, VPN ou réseau Docker | Streamable HTTP |
| **Internet** | Service distant | Streamable HTTP, HTTPS et authentification recommandés |

Le transport exact, l’endpoint, les en-têtes HTTP, l’environnement, les commandes locales et les correspondances de capacités restent accessibles dans les paramètres avancés.

Une connexion est testée avant son enregistrement. Le résultat du test doit présenter en priorité : serveur joint ou non, protocole négocié, latence et outils exposés.

## Connecter des clients à Kex

La configuration du serveur MCP exposé par Kex reste disponible dans **Configuration → Serveur MCP Kex**, mais l’espace MCP unifié fournit un accès direct à cette section.

L’utilisateur doit pouvoir vérifier rapidement :

- si le serveur est activé ;
- son état opérationnel ;
- le protocole négocié ;
- le nombre d’outils, ressources, modèles et prompts exposés ;
- les sessions clientes actives ;
- la date du dernier contrôle.

Les détails JSON-RPC et contrats complets restent disponibles dans le catalogue ou le playground, sans encombrer le premier niveau d’information.

## Niveaux d’impact des outils

Le Control Center classe les outils afin d’éviter qu’un appel ayant des effets de bord ressemble à une simple lecture.

| Niveau | Signification | Comportement UI |
|---|---|---|
| **Lecture** | Lecture seule déclarée ou reconnue | Exécution directe possible |
| **Écriture** | Modification possible | Confirmation avant appel direct |
| **Administration** | Effet destructif, suppression ou changement sensible | Confirmation explicite avant appel direct |
| **Effet à vérifier** | Le serveur ne fournit pas assez d’annotations | Avertissement et traitement prudent |

La classification utilise d’abord les annotations MCP du serveur (`readOnlyHint`, `destructiveHint`, `idempotentHint`, etc.). Lorsque ces annotations sont absentes, l’interface utilise des indices prudents fondés sur le nom et la description, sans présenter cette estimation comme une garantie.

Les permissions Kex restent l’autorité réelle : une annotation MCP ne contourne ni les règles d’autonomie, ni les permissions, ni les validations humaines.

## Confirmation des mutations

Les appels directs depuis la console ou le playground doivent demander une confirmation lorsque l’outil est classé **Écriture** ou **Administration**.

La confirmation indique au minimum :

- l’outil appelé ;
- la connexion concernée ;
- le niveau d’impact ;
- le fait que l’opération peut modifier un système externe.

Une opération classée Administration doit être présentée comme potentiellement destructive lorsqu’elle peut supprimer ou altérer durablement des données.

Le chemin conversationnel reste soumis aux garde-fous ordinaires de Kex : autonomie, capacités, permissions et approbations.

## Fraîcheur et cache

Un catalogue MCP peut rester affiché après un échec d’actualisation. L’interface ne doit jamais le présenter comme fraîchement vérifié.

Elle distingue notamment :

- **Catalogue récupéré il y a …** : dernière récupération réussie ;
- **Catalogue conservé** : données provenant du cache ;
- **Actualisation à vérifier** : une tentative récente a échoué ;
- **Disponibilité non confirmée** : catalogue connu mais connexion courante non validée.

L’âge des informations et leur provenance doivent rester visibles près de la connexion ou du service concerné.

## Diagnostics actionnables

Les erreurs de premier niveau sont formulées comme un problème compréhensible et une action possible, les détails techniques restant disponibles au second niveau.

| Situation | Message utilisateur | Action proposée |
|---|---|---|
| HTTP 401/403 | **Accès refusé** | Vérifier l’authentification ou les permissions |
| HTTP 404 | **Service introuvable** | Actualiser la connexion ou la configuration |
| HTTP 5xx | **Service indisponible** | Réessayer et consulter le diagnostic |
| Erreur réseau | **Connexion impossible** | Vérifier l’adresse, le réseau et l’authentification |
| Catalogue conservé | **Informations potentiellement anciennes** | Actualiser le catalogue |

Les diagnostics techniques peuvent inclure le code HTTP, le transport, la latence, l’historique de santé et les différences entre catalogues, mais ils ne doivent pas être le seul message présenté.

## Catalogue et playground

Le catalogue doit commencer par l’usage métier d’un outil : nom, description, disponibilité et niveau d’impact. Le contrat JSON, l’exemple d’appel et la réponse brute restent disponibles sous forme progressive.

Dans le playground :

1. sélectionner l’opération ;
2. sélectionner la cible ;
3. renseigner les champs dérivés du schéma ;
4. vérifier le niveau d’impact ;
5. confirmer si l’opération écrit ou administre ;
6. exécuter ;
7. consulter d’abord le résultat structuré et textuel, puis le JSON-RPC brut si nécessaire.

## Terminologie UI

La terminologie suivante doit rester stable :

- **Connexion MCP** : configuration permettant à Kex de joindre un serveur externe ;
- **Serveur MCP Kex** : serveur exposé par Kex à des clients externes ;
- **Outil** : action MCP ;
- **Ressource** : donnée adressable par URI ;
- **Prompt** : gabarit MCP fourni par un serveur ;
- **Catalogue** : métadonnées découvertes auprès d’un serveur ;
- **Diagnostic** : état et informations de dépannage d’une connexion.

Éviter d’utiliser indifféremment « service », « serveur » et « intégration » lorsqu’ils décrivent des objets différents.

## Critères UX de validation

Une évolution MCP UI/UX est considérée acceptable si :

1. la différence entre **Kex consomme MCP** et **Kex expose MCP** est compréhensible sans lire de documentation technique ;
2. une configuration locale peut être créée sans connaître les noms des transports MCP ;
3. chaque erreur importante propose une action ;
4. l’âge d’un catalogue conservé est visible ;
5. les outils d’écriture et d’administration sont identifiés avant leur exécution ;
6. une mutation directe exige une confirmation ;
7. les contrats JSON et données brutes restent accessibles sans dominer l’interface ;
8. aucune information sensible n’est affichée durablement ;
9. les états ne reposent pas uniquement sur la couleur ;
10. les parcours restent utilisables au clavier et avec les technologies d’assistance.

## Relation avec la documentation technique

Pour les propriétés Spring, variables d’environnement, transports, politiques de sécurité, persistance chiffrée et APIs REST, voir [MCP.md](MCP.md) et [CONFIGURATION.md](CONFIGURATION.md).
