// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Produit le guide Word à partir des schémas rendus par figs.cjs.
//   node build.cjs <sortie.docx> <dossier des schémas>
const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, Table, TableRow, TableCell,
  WidthType, ShadingType, BorderStyle, ImageRun, LevelFormat, Header, Footer,
  PageNumber, PageBreak, TabStopType,
} = require('docx');

const OUT = process.argv[2];
const DIR = process.argv[3] ?? __dirname;
const FONT = 'Calibri';
const ACCENT = '167D73';
const INK = '1B2333';
const MUTED = '4C586B';
const CONTENT_W = 9638; // A4 (11906) moins deux marges de 1134

// ── Valeurs de configuration ──────────────────────────────────────────
// Chaque valeur citée vient de values.json, que scripts/check-guide-values.py confronte à
// application.yml : un seuil changé dans la configuration ne peut plus laisser le guide faux.
const VALUES = JSON.parse(fs.readFileSync(path.join(__dirname, 'values.json'), 'utf8')).values;
const V = Object.fromEntries(Object.entries(VALUES).map(([key, entry]) => [key, entry.text]));
// Dérivée, pas recopiée : le seuil de compaction en jetons.
const triggerTokens = Number(VALUES.context_tokens.value) * Number(VALUES.trigger_fraction.value);
V.trigger_tokens = triggerTokens.toLocaleString('fr-FR').replace(/\u202f/g, ' ');
// L'exemple chiffré doit rester au-dessus du seuil qu'il illustre.
if (52000 <= triggerTokens) throw new Error(`L'exemple de compaction (52 000) ne dépasse plus le seuil (${triggerTokens})`);
function valued(text) {
  return text.replace(/\{\{(\w+)\}\}/g, (_, key) => {
    if (!(key in V)) throw new Error(`Valeur inconnue dans le guide : {{${key}}}`);
    return V[key];
  });
}

// ── Aides de mise en forme ────────────────────────────────────────────

// **gras** et `code` dans un texte courant.
function runs(text, base = {}) {
  text = valued(text);
  const out = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0; let m;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(new TextRun({ text: text.slice(last, m.index), ...base }));
    const t = m[0];
    if (t.startsWith('**')) out.push(new TextRun({ text: t.slice(2, -2), bold: true, ...base }));
    else out.push(new TextRun({ text: t.slice(1, -1), font: 'Consolas', size: 19, color: '0F5F57', ...base }));
    last = m.index + t.length;
  }
  if (last < text.length) out.push(new TextRun({ text: text.slice(last), ...base }));
  return out;
}

const P = (text, opts = {}) => new Paragraph({ children: runs(text), spacing: { after: 120, line: 300 }, ...opts });
const H1 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun(text)], pageBreakBefore: true });
const H2 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(text)] });
const H3 = (text) => new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(text)] });
const bullet = (text, level = 0) => new Paragraph({ numbering: { reference: 'puces', level }, children: runs(text), spacing: { after: 60, line: 288 } });
const step = (text, ref) => new Paragraph({ numbering: { reference: ref, level: 0 }, children: runs(text), spacing: { after: 60, line: 288 } });
const bullets = (items) => items.map((t) => bullet(t));
let listCount = 0;
const steps = (items) => { const ref = `etapes-${listCount++}`; numberingRefs.push(ref); return items.map((t) => step(t, ref)); };
const numberingRefs = [];

// Encadré pédagogique : trait d'accent à gauche, fond très clair.
function callout(title, text, color = ACCENT, fill = 'EEF8F6') {
  const border = { style: BorderStyle.SINGLE, size: 24, color, space: 8 };
  const children = [new Paragraph({
    children: [new TextRun({ text: title, bold: true, color })],
    border: { left: border }, shading: { type: ShadingType.CLEAR, fill, color: 'auto' },
    spacing: { before: 120, after: 0 }, indent: { left: 200, right: 200 },
  })];
  for (const line of [].concat(text)) {
    children.push(new Paragraph({
      children: runs(line), border: { left: border }, shading: { type: ShadingType.CLEAR, fill, color: 'auto' },
      spacing: { after: 0, line: 288 }, indent: { left: 200, right: 200 },
    }));
  }
  children.push(new Paragraph({ children: [], spacing: { after: 120 } }));
  return children;
}
const retenir = (t) => callout('À retenir', t);
const exemple = (t) => callout('Exemple', t, '6D28D9', 'F4EFFD');
const attention = (t) => callout('Attention', t, 'B45309', 'FDF4E7');

function table(header, rows, widths) {
  const total = widths.reduce((a, b) => a + b, 0);
  const scale = CONTENT_W / total;
  const w = widths.map((x) => Math.round(x * scale));
  w[w.length - 1] += CONTENT_W - w.reduce((a, b) => a + b, 0);
  const border = { style: BorderStyle.SINGLE, size: 4, color: 'D5DCE5' };
  const borders = { top: border, bottom: border, left: border, right: border };
  const cell = (text, i, head) => new TableCell({
    width: { size: w[i], type: WidthType.DXA }, borders,
    shading: head ? { type: ShadingType.CLEAR, fill: 'E3F1EE', color: 'auto' } : undefined,
    margins: { top: 70, bottom: 70, left: 110, right: 110 },
    children: [new Paragraph({ children: runs(text, head ? { bold: true, color: '0F5F57' } : {}), spacing: { after: 0, line: 264 } })],
  });
  return [new Table({
    width: { size: CONTENT_W, type: WidthType.DXA }, columnWidths: w,
    rows: [new TableRow({ tableHeader: true, children: header.map((h, i) => cell(h, i, true)) }),
      ...rows.map((r) => new TableRow({ cantSplit: true, children: r.map((c, i) => cell(c, i, false)) }))],
  }), new Paragraph({ children: [], spacing: { after: 80 } })];
}

const sizes = { f1: [2000, 818], f2: [2000, 624], f3: [2000, 1280], f4: [2000, 358] };
let figNo = 0;
function figure(id, caption, widthPx = 620) {
  const [w, h] = sizes[id];
  figNo += 1;
  return [
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 120, after: 60 }, keepNext: true,
      children: [new ImageRun({ type: 'png', data: fs.readFileSync(path.join(DIR, `${id}.png`)),
        transformation: { width: widthPx, height: Math.round(widthPx * h / w) },
        altText: { title: caption, description: caption, name: id } })] }),
    new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 200 },
      children: [new TextRun({ text: `Figure ${figNo} — ${caption}`, italics: true, size: 19, color: MUTED })] }),
  ];
}

// ── Contenu ───────────────────────────────────────────────────────────

const cover = [
  new Paragraph({ spacing: { before: 2600, after: 0 }, children: [new TextRun({ text: 'KEX AGENT AI', bold: true, size: 24, color: ACCENT, characterSpacing: 60 })] }),
  new Paragraph({ spacing: { before: 120, after: 120 }, children: [new TextRun({ text: 'Comprendre l’agent', bold: true, size: 64, color: INK })] }),
  new Paragraph({ spacing: { after: 480 }, children: [new TextRun({ text: 'Guide pédagogique : fonctionnement, algorithmes, gains et contraintes', size: 30, color: MUTED })] }),
  new Paragraph({ border: { top: { style: BorderStyle.SINGLE, size: 12, color: ACCENT, space: 12 } }, spacing: { after: 80 },
    children: [new TextRun({ text: 'Public : exploitants, développeurs, responsables techniques — aucune connaissance préalable de l’IA requise.', size: 22, color: MUTED })] }),
  new Paragraph({ children: [new TextRun({ text: 'Version décrite : branche principale du 25 novembre 2026 (Spring Boot 4.2, Spring AI 2.1, Java 21).', size: 22, color: MUTED })] }),
  new Paragraph({ children: [new PageBreak()] }),
  new Paragraph({ children: [new TextRun({ text: 'Sommaire', bold: true, size: 36, color: INK })], spacing: { after: 200 } }),
  ...[
    ['1', 'Avant de commencer', 'à qui s’adresse ce guide, comment le lire'],
    ['2', 'L’agent en une page', 'trois rôles, une analogie'],
    ['3', 'Vocabulaire essentiel', 'les quinze termes à connaître'],
    ['4', 'Architecture d’ensemble', 'briques, modules, stockage'],
    ['5', 'Comment l’agent répond à une question', 'de l’entrée à la réponse'],
    ['6', 'Le cycle de supervision', 'garde-fous, relevé, preuves, états'],
    ['7', 'Décisions et autonomie gouvernée', 'capacités, arbre de décision, vérification'],
    ['8', 'Les tâches durables', 'cycle de vie, reprise prudente'],
    ['9', 'Mémoire et apprentissage', 'mémoire, compétences, charte, connaissance'],
    ['10', 'Les algorithmes, un par un', 'treize mécanismes détaillés'],
    ['11', 'Les gains apportés', 'ce que l’agent change'],
    ['12', 'Les contraintes et les limites', 'ce qu’il ne fait pas'],
    ['13', 'Mettre en route', 'démarrage et réglages clés'],
  ].map(([n, t, d]) => new Paragraph({ spacing: { after: 110 }, border: { bottom: { style: BorderStyle.SINGLE, size: 2, color: 'E3E8EF', space: 4 } },
    children: [new TextRun({ text: `${n}.  `, bold: true, color: ACCENT, size: 24 }), new TextRun({ text: t, bold: true, size: 24 }),
      new TextRun({ text: `  —  ${d}`, color: MUTED, size: 21 })] })),
];

const s1 = [
  H1('1. Avant de commencer'),
  H2('1.1 À qui s’adresse ce guide'),
  P('Ce guide explique, pas à pas, comment fonctionne Kex Agent AI : ce qu’il fait, comment il le fait, quels algorithmes il emploie, ce qu’il apporte et ce qu’il ne sait pas faire. Il s’adresse à trois lecteurs :'),
  ...bullets([
    '**l’exploitant**, qui veut savoir ce que l’agent surveille, quand il agit seul et quand il lui demande son avis ;',
    '**le développeur**, qui veut comprendre les mécanismes avant de modifier le code ;',
    '**le responsable technique**, qui doit juger des gains, des risques et du coût.',
  ]),
  P('Aucune connaissance préalable de l’intelligence artificielle n’est nécessaire. Les termes techniques sont définis au chapitre 3 avant d’être employés.'),
  H2('1.2 Comment lire ce guide'),
  P('Les chapitres 2 à 4 donnent la vue d’ensemble. Les chapitres 5 à 9 décrivent chaque grand mécanisme dans l’ordre où un utilisateur le rencontre. Le chapitre 10 détaille les algorithmes, un par un, avec leur objectif, leur principe et un exemple chiffré. Les chapitres 11 et 12 font le bilan : gains et contraintes. Le chapitre 13 explique comment démarrer.'),
  P('Trois types d’encadrés ponctuent le texte :'),
  ...callout('À retenir', 'L’idée essentielle d’une section, en une ou deux phrases.'),
  ...callout('Exemple', 'Un cas concret, avec des valeurs réelles de la configuration par défaut.', '6D28D9', 'F4EFFD'),
  ...callout('Attention', 'Une limite, un piège ou une condition à ne pas oublier.', 'B45309', 'FDF4E7'),
  P('Les valeurs citées (seuils, délais, plafonds) sont celles du fichier `application.yml` livré avec l’agent. Une installation peut les modifier.'),
];

const s2 = [
  H1('2. L’agent en une page'),
  P('Kex Agent AI est un **agent d’exploitation**. Il combine un grand modèle de langage (le « cerveau » qui comprend et rédige) avec des **outils** qui lui donnent accès à des données réelles. Par défaut, ces outils sont ceux de **Kafka SQL Explorer**, un service qui sait lire l’état d’un cluster Kafka : topics, consumers, retards de consommation, prévisions.'),
  P('L’agent joue trois rôles dans le même programme :'),
  ...table(['Rôle', 'Ce qu’il fait', 'Qui le déclenche'], [
    ['**Assistant conversationnel**', 'Répond aux questions en interrogeant les outils, puis rédige une réponse sourcée.', 'Un humain, depuis la console ou l’API.'],
    ['**Superviseur**', 'Analyse régulièrement des processus métier, détecte des anomalies, propose ou exécute des actions sous contrôle.', 'Un humain (bouton « Exécuter maintenant ») ou un planificateur.'],
    ['**Serveur d’outils**', 'Expose ses propres capacités (état, alertes, incidents, décisions en attente) à d’autres agents ou à un IDE.', 'Un autre programme, par le protocole MCP.'],
  ], [22, 50, 28]),
  ...figure('f1', 'Vue d’ensemble : qui parle à l’agent, et à qui l’agent parle'),
  ...retenir('L’agent ne stocke pas les données métier. Il les **lit** au moment voulu à travers des outils, **raisonne** avec un modèle de langage, et **propose ou exécute** des actions dans des limites fixées par des humains.'),
  H2('2.1 Une analogie'),
  P('On peut se représenter l’agent comme un **technicien d’astreinte très rigoureux** :'),
  ...bullets([
    'il fait sa ronde à intervalle régulier (le **cycle de supervision**) ;',
    'il ne note une panne que s’il l’a constatée sur un instrument (une anomalie doit **citer un outil** qui l’a mesurée) ;',
    'il connaît la liste des gestes qu’il a le droit de faire seul, ceux qui demandent l’accord d’un responsable, et ceux qui lui sont interdits (la **politique d’autonomie**) ;',
    'après chaque geste, il vérifie que la situation s’est réellement améliorée (la **vérification**) ;',
    'il tient un registre de tout ce qu’il a vu et fait (l’**audit**) ;',
    'il apprend de ses erreurs, mais chaque nouvelle règle qu’il se donne doit être validée par un humain (les **compétences**).',
  ]),
];

const s3 = [
  H1('3. Vocabulaire essentiel'),
  P('Ces termes reviennent dans tout le guide. Ils sont aussi ceux de la console et de l’API.'),
  ...table(['Terme', 'Définition'], [
    ['**Modèle de langage (LLM)**', 'Programme qui, à partir d’un texte, produit la suite la plus plausible. Ici, il comprend les demandes, choisit les outils à appeler et rédige les réponses. Fournisseurs possibles : Anthropic, OpenAI, ou un modèle local (Ollama, vLLM).'],
    ['**Jeton (token)**', 'Unité de texte que le modèle lit et écrit, environ trois quarts de mot. Les fournisseurs facturent au jeton : c’est l’unité de coût.'],
    ['**MCP**', 'Model Context Protocol. Protocole standard qui permet à un agent de découvrir et d’appeler les outils d’un serveur. L’agent est client MCP (il appelle Explorer) et serveur MCP (d’autres l’appellent).'],
    ['**Outil**', 'Fonction qu’un serveur MCP met à disposition, par exemple « lister les topics » ou « lire le retard d’un consumer ». Le modèle décide de l’appeler ; l’agent l’exécute.'],
    ['**Connexion**', 'Clé qui désigne un serveur MCP dans la configuration (par exemple `kafka-explorer`). C’est toujours par elle que l’agent s’adresse à un serveur.'],
    ['**Processus surveillé**', 'Chaîne métier déclarée par l’exploitant (par exemple « Intégration des commandes »). C’est l’unité que le superviseur analyse.'],
    ['**Cycle**', 'Une passe d’analyse complète sur tous les processus déclarés.'],
    ['**Anomalie**', 'Écart constaté pendant un cycle, appuyé sur au moins une mesure d’outil.'],
    ['**Capacité**', 'Type d’action que l’agent peut entreprendre : NOTIFY, CREATE_INCIDENT, RESTART_CONSUMER, REPLAY_MESSAGES, MODIFY_CONFIGURATION, DECLARE_ANOMALY, CLEAR_ANOMALY.'],
    ['**Autonomie**', 'Ce que la politique autorise pour une capacité : AUTOMATIC (agir seul), SUPERVISED (demander), FORBIDDEN (recommander seulement).'],
    ['**Décision**', 'Proposition d’action née d’une anomalie, avec son statut (en attente, exécutée, vérifiée, rejetée…).'],
    ['**Plancher de confiance**', 'Niveau de confiance minimal ({{confidence}} par défaut) en dessous duquel une action, même autorisée, repasse par un humain.'],
    ['**Compétence**', 'Règle ou procédure apprise, écrite en texte, injectée dans le contexte du modèle une fois approuvée par un administrateur.'],
    ['**Tâche durable**', 'Plan en plusieurs étapes, approuvé avant d’être exécuté, qui survit à un redémarrage.'],
    ['**Audit**', 'Journal horodaté de ce que l’agent a vu, décidé et fait, et de qui l’a validé.'],
  ], [28, 72]),
];

const s4 = [
  H1('4. Architecture d’ensemble'),
  H2('4.1 Les briques techniques'),
  P('L’agent est une application **Spring Boot 4** écrite en **Java 21**, qui s’appuie sur **Spring AI 2** pour dialoguer avec les modèles et les serveurs MCP. Il s’exécute dans un seul processus, sur le port 8081, et se distribue sous forme d’image Docker.'),
  ...table(['Brique', 'Rôle dans l’agent'], [
    ['Spring MVC', 'Expose l’API REST et la console web. Les réponses en direct passent par des flux SSE (Server-Sent Events).'],
    ['Spring AI', 'Abstraction des fournisseurs de modèles, mémoire de conversation, appels d’outils, client MCP.'],
    ['Spring Security', 'Authentification par clé API ou jeton OIDC, et rôles CHAT, OPERATOR, ADMIN, METRICS.'],
    ['Resilience4j', 'Disjoncteurs et réessais devant le modèle et devant chaque serveur MCP.'],
    ['Micrometer, OpenTelemetry', 'Métriques Prometheus et traces distribuées.'],
    ['PostgreSQL et Flyway (facultatif)', 'État partagé entre plusieurs instances, sous le profil `shared-memory`.'],
  ], [30, 70]),
  H2('4.2 Les grands modules'),
  P('Le code est découpé en paquets qui correspondent aux chapitres de ce guide :'),
  ...table(['Paquet', 'Responsabilité'], [
    ['`agent`', 'Conversation : préparation de la demande, appel du modèle, flux de réponse, budget de jetons.'],
    ['`tools`, `mcp`', 'Choix, contrôle et appel des outils ; inventaire des serveurs MCP ; catalogue d’installation ; Kex comme serveur MCP.'],
    ['`supervision`', 'Cycle d’analyse, politique, décisions, validation humaine, vérification, audit, planification.'],
    ['`execution`', 'Tâches durables : planification, approbation, exécution reprenable.'],
    ['`memory`, `skills`', 'Mémoire long terme, compétences, revue humaine, charte.'],
    ['`harness`', 'Gestion du contexte long : compaction, résultats d’outils volumineux stockés à part.'],
    ['`knowledge`', 'Base documentaire facultative interrogée par similarité.'],
    ['`channels`', 'Notifications Slack, Teams, e-mail ; rappels entrants signés.'],
    ['`kafka`', 'Vues techniques du cluster et des prévisions, construites à partir des outils d’Explorer.'],
    ['`web`, `config`', 'API REST, sécurité, limites de débit, câblage du modèle.'],
  ], [22, 78]),
  H2('4.3 Deux modes de stockage'),
  P('Par défaut, l’agent conserve son état dans des **fichiers** (sous `/var/lib/kex` dans l’image Docker) ou en mémoire. Ce mode convient à une instance unique.'),
  P('Avec le profil `shared-memory`, tout l’état passe dans **PostgreSQL** : mémoire, décisions, audit, verrous, budgets, tâches. C’est la condition pour faire tourner plusieurs instances derrière un répartiteur de charge sans qu’elles se marchent dessus. Le schéma de la base est créé et mis à jour par des migrations Flyway versionnées.'),
  ...attention('Sans le profil `shared-memory`, chaque instance tient ses propres verrous et compteurs. Deux instances lanceraient chacune leur cycle, et leurs actions partiraient en double. Une seule réplique, donc, hors de ce profil.'),
];

const s5 = [
  H1('5. Comment l’agent répond à une question'),
  P('Prenons une question tapée dans la console : « Quel est le retard du groupe de consommateurs orders-sync ? ». Voici ce qui se passe, dans l’ordre.'),
  ...figure('f4', 'Le trajet d’une question, de l’entrée à la réponse'),
  H2('5.1 Entrée et contrôle d’accès'),
  ...steps([
    'La console envoie la question à `POST /api/agent/chat/stream`, accompagnée de la clé API de l’utilisateur.',
    'Le **filtre de débit** vérifie que cette clé n’a pas dépassé son quota ({{rate}} requêtes par minute, avec une réserve de {{burst}} pour les rafales ; voir 10.1).',
    'La sécurité vérifie le rôle : poser une question exige CHAT, OPERATOR ou ADMIN.',
  ]),
  H2('5.2 Préparation de la demande'),
  P('Avant de solliciter le modèle, l’agent assemble ce que celui-ci doit savoir :'),
  ...bullets([
    '**le budget** : il réserve les jetons nécessaires sur le budget journalier, et refuse si le plafond est atteint (10.2) ;',
    '**les outils** : il prend la liste des outils autorisés, en retire ceux que la politique interdit, et peut n’en garder qu’un sous-ensemble pertinent (10.3) ;',
    '**le contexte durable** : la charte de l’installation, les compétences invariantes en entier, et un index des compétences de domaine que le modèle peut lire à la demande ;',
    '**la connaissance** : si la base documentaire est active, les passages les plus proches de la question ;',
    '**l’historique** : les derniers messages de la conversation ({{history}} au plus, compactés au besoin ; 10.4).',
  ]),
  P('Le message système part en **deux blocs** : d’abord ce qui ne change jamais pour l’installation, puis ce qui dépend de la demande. Les fournisseurs mettent en cache le début d’un prompt identique d’une requête à l’autre ; garder la partie stable en tête rend ce cache efficace, ce qui réduit le coût et la latence.'),
  H2('5.3 La boucle du modèle'),
  P('Le modèle reçoit la question et la liste des outils. Il peut répondre directement, ou demander un outil. Dans notre exemple, il demande l’outil de retard de consommation avec l’argument `orders-sync`.'),
  ...steps([
    'L’agent exécute l’outil sur le serveur MCP Explorer, à travers un **disjoncteur** (10.5).',
    'Le résultat est enveloppé dans une balise `<tool_result trust="untrusted">` avant de revenir au modèle. Cette balise lui rappelle que ce texte est une **donnée** venue de l’extérieur, jamais une instruction à suivre.',
    'Le modèle lit le résultat et décide : rédiger la réponse, ou appeler un autre outil. La boucle continue autant de tours que nécessaire.',
    'Un **plafond de durée** borne l’échange entier ({{request_timeout}} par défaut), pas seulement le silence entre deux morceaux de réponse (10.6).',
  ]),
  H2('5.4 Restitution'),
  P('La réponse arrive mot à mot dans la console par un flux SSE. À la fin, l’agent enregistre la consommation réelle de jetons, libère la part de budget non utilisée et dépose en mémoire long terme un **résumé non vérifié** de l’échange. Ce résumé ne devient « vérifié » qu’après confirmation.'),
  ...retenir('Le modèle **choisit** les outils, l’agent les **exécute** et **contrôle** tout le reste : droits, budget, durée, robustesse, et traitement des résultats comme données non fiables.'),
  ...attention('Si l’appel échoue (fournisseur injoignable, clé refusée, délai dépassé), l’agent rend une erreur **502** ou **504** qui le dit. Il ne recopie jamais le 401 du fournisseur : on confondrait sinon « votre clé Kex est refusée » et « la clé du fournisseur est fausse ».'),
];

const s6 = [
  H1('6. Le cycle de supervision'),
  P('Le cycle est le cœur du superviseur. Il se déclenche à la main (bouton « Exécuter maintenant », rôle OPERATOR) ou automatiquement par le planificateur, toutes les {{schedule_interval}} par défaut si la planification est activée.'),
  ...figure('f2', 'Les six étapes d’un cycle de supervision'),
  H2('6.1 Étape 1 — Les garde-fous'),
  ...bullets([
    'Si l’agent est **en pause**, le cycle est refusé.',
    'Si un autre cycle tient déjà le **verrou**, le cycle est refusé : deux analyses simultanées produiraient deux fois les mêmes décisions.',
    'Si aucun processus n’est déclaré, le cycle se termine sur un état vide **honnête** : rien n’est inventé pour remplir l’écran.',
    'Si le **budget de jetons** du jour est épuisé, le cycle s’arrête avant d’appeler le modèle. Un cycle planifié n’a pas d’humain pour voir la facture : ce plafond est son seul frein.',
  ]),
  H2('6.2 Étape 2 — Le relevé'),
  P('Pour chaque processus, l’agent ouvre **un échange séparé** avec le modèle, en lecture seule : seuls les outils de lecture sont permis. Il demande une réponse **structurée**, conforme à un schéma JSON (états, anomalies, observations). Cette structure est validée avant d’être utilisée. Un échange par processus évite qu’une analyse déborde sur une autre ; une ligne qui parlerait d’un autre processus est écartée à la fusion.'),
  H2('6.3 Étape 3 — Le tri des preuves'),
  P('C’est une règle centrale : **une anomalie sans outil cité n’est qu’une affirmation**. Chaque anomalie doit s’appuyer sur une observation qui cite un appel d’outil **réussi** pendant ce cycle. Sinon, elle est écartée : pas d’alerte, pas de décision. Le déroulé du cycle indique combien d’anomalies ont été écartées et pourquoi.'),
  ...exemple('Le modèle affirme « le consumer orders-sync est bloqué » mais n’a appelé aucun outil de retard pendant le cycle. L’anomalie est écartée et le processus passe en UNKNOWN, faute de mesure. S’il avait appelé l’outil et observé un retard de 120 000 messages, l’anomalie serait retenue.'),
  H2('6.4 Étape 4 — Les états'),
  P('Chaque processus reçoit un état : **OK**, **WARNING**, **ERROR** ou **UNKNOWN**. Trois règles de prudence encadrent ces états :'),
  ...bullets([
    '**Un état illisible vaut UNKNOWN, jamais OK.** Une donnée manquante et une donnée saine se ressemblent dans un tableau de bord ; les confondre fait rater une panne.',
    '**Une mesure absente n’est jamais zéro.** Un retard à 0 affirme « rattrapé » ; une mesure absente n’affirme rien.',
    '**Un relevé partiel prouve une présence, jamais une absence.** Un OK obtenu sur une lecture incomplète redevient UNKNOWN ; un ERROR, lui, tient.',
  ]),
  P('Une **fenêtre de maintenance** déclarée sur un processus fait taire les alertes et les décisions, mais pas l’observation : le relevé reste exact, seule la suite se tait.'),
  H2('6.5 Étapes 5 et 6 — Décider et tracer'),
  P('Chaque anomalie retenue donne lieu à une **décision** (chapitre 7). Tout est inscrit dans l’**audit** : qui a déclenché le cycle, ce qui a été vu, ce qui a été décidé. Si une décision attend un humain, une notification part vers les canaux configurés (Slack, Teams, e-mail, webhook).'),
  H2('6.6 La planification'),
  P('Le planificateur bat à intervalle **fixe**. À chaque battement, il se demande d’abord s’il y a lieu d’analyser, puis seulement prend le verrou. En mode **adaptatif** (désactivé par défaut), il analyse toutes les {{adaptive_degraded}} quand le dernier cycle a vu un problème, et toutes les {{adaptive_healthy}} quand tout allait bien : on réagit vite à un incident sans dépenser de jetons inutilement le reste du temps.'),
  ...attention('La planification automatique exige soit le profil `shared-memory` (verrou en base), soit la déclaration explicite d’une instance unique (`schedule.single-instance`). Au démarrage, l’agent avertit si la propriété est activée sans ce qui la rend sûre.'),
];

const s7 = [
  H1('7. Décisions et autonomie gouvernée'),
  P('Une anomalie appelle une action. Toute la question est : **l’agent peut-il agir seul ?** La réponse dépend de la politique, de la confiance du modèle et de la nature de l’action.'),
  H2('7.1 Les capacités'),
  ...table(['Capacité', 'Action', 'Remarque'], [
    ['NOTIFY', 'Prévenir l’équipe d’exploitation.', 'Capacité par défaut d’une anomalie sans recommandation. Sans outil lié, passe par le webhook.'],
    ['CREATE_INCIDENT', 'Créer un incident.', 'Selon l’outil MCP lié.'],
    ['RESTART_CONSUMER', 'Redémarrer un consumer.', 'Action à effet réel : supervisée en général.'],
    ['REPLAY_MESSAGES', 'Rejouer des messages en attente.', 'Idem.'],
    ['MODIFY_CONFIGURATION', 'Modifier une configuration.', 'Idem.'],
    ['DECLARE_ANOMALY / CLEAR_ANOMALY', 'Déclarer ou lever une anomalie sur un système tiers.', 'Outils « gouvernés », invisibles du modèle (voir 7.4).'],
  ], [34, 30, 36]),
  ...attention('Le serveur MCP de Kafka SQL Explorer est en **lecture seule**. Devant lui seul, l’agent observe et recommande ; il n’agit pas. Les actions réelles exigent qu’un outil MCP capable d’agir soit lié à la capacité dans la configuration.'),
  H2('7.2 L’arbre de décision'),
  ...figure('f3', 'Comment une anomalie devient une action, une demande ou une recommandation', 560),
  P('Quatre règles structurent cet arbre :'),
  ...steps([
    '**La politique fixe l’autonomie** de chaque capacité. Le mode d’exécution global (AUTOMATIC, SUPERVISED, MANUAL) ne peut que la **restreindre**, jamais l’élargir.',
    '**Le plancher de confiance** ({{confidence}} par défaut) s’applique aux actions automatiques. Un plancher propre à une capacité ne peut que **relever** le plancher global.',
    '**Ce que le modèle a écrit n’atteint jamais un outil sans qu’un humain l’ait lu.** Si la décision porte des arguments rédigés par le modèle, l’autonomie AUTOMATIC est rabaissée à SUPERVISED.',
    '**La politique est relue au moment d’agir.** Si elle a changé entre la décision et l’exécution, ou si l’agent est en pause, ou si une maintenance a commencé, l’exécution est bloquée.',
  ]),
  H2('7.3 La validation humaine'),
  P('Une décision PENDING_APPROVAL apparaît dans la console et peut être notifiée sur Slack, avec des boutons Approuver / Rejeter. Elle expire au bout de {{approval_timeout}} par défaut (statut EXPIRED). Approuver exige le rôle OPERATOR ou ADMIN. Un verrou par décision empêche un double clic d’exécuter deux fois la même action.'),
  P('Un rejet s’accompagne d’un motif. Au-delà de **{{min_refusals}} refus concordants** sur une même capacité, l’agent propose une nouvelle compétence qui tire la leçon de ces refus (voir 9.3).'),
  H2('7.4 Exécution et vérification'),
  P('Une action approuvée passe par `callGoverned`, la seule voie qui atteint les outils marqués « gouvernés ». Ces outils sont **cachés au modèle** : il ne peut pas les appeler de lui-même pendant une conversation.'),
  P('Après l’action, l’agent **vérifie** que l’objectif est atteint en relisant l’état par un outil indépendant (10.8). Les statuts possibles :'),
  ...table(['Statut', 'Signification'], [
    ['VERIFIED', 'L’action a eu lieu et la vérification confirme l’effet attendu.'],
    ['VERIFICATION_FAILED', 'L’action a eu lieu, mais la relecture contredit l’effet attendu.'],
    ['VERIFICATION_UNKNOWN', 'La vérification n’a pas pu conclure.'],
    ['EXECUTED_UNVERIFIED', 'L’action a eu lieu, sans vérificateur configuré.'],
    ['EXECUTED', 'L’action a eu lieu (sans postcondition à contrôler).'],
    ['FAILED', 'L’outil a échoué, ou aucun outil n’est lié à la capacité.'],
    ['SIMULATED', 'Aucun outil lié, mode simulation actif : l’agent décrit ce qu’il aurait fait.'],
    ['BLOCKED, REJECTED, EXPIRED', 'Interdite par la politique, refusée par un humain, ou non traitée à temps.'],
  ], [32, 68]),
  ...retenir('L’agent ne déclare jamais une action « réussie » sur sa seule parole : un statut VERIFIED exige une relecture indépendante.'),
];

const s8 = [
  H1('8. Les tâches durables'),
  P('Certaines demandes demandent plusieurs étapes : relever un état, comparer, puis agir. Les **tâches durables** les encadrent. Elles sont activées par `kex.agent.tasks.enabled`.'),
  H2('8.1 Le cycle de vie'),
  ...steps([
    '**Brouillon (DRAFT)** : un plan est rédigé, à la main ou par le planificateur de tâches à partir d’une demande en langage naturel. Le plan est validé (étapes, outils, contrats de résultat).',
    '**Approuvée (APPROVED)** : un humain approuve le plan sous la politique en vigueur ; la version de la politique est enregistrée avec l’approbation.',
    '**En cours (RUNNING)** : l’exécution démarre sur un fil d’exécution léger (thread virtuel). Un **bail** de durée limitée empêche une autre instance de l’exécuter en même temps.',
    '**Terminée** : VERIFIED si toutes les postconditions sont confirmées, COMPLETED sinon ; FAILED si une étape échoue.',
  ]),
  H2('8.2 La reprise prudente'),
  P('Si l’agent redémarre au milieu d’une tâche, la reprise examine l’étape interrompue :'),
  ...bullets([
    'une étape de **lecture** interrompue est simplement rejouée ;',
    'une étape d’**écriture** interrompue passe en UNKNOWN, et la tâche en **NEEDS_RECONCILIATION** : on ne sait pas si l’effet a eu lieu, donc on ne rejoue pas à l’aveugle une action qui pourrait se produire deux fois.',
  ]),
  P('Une tâche qui dépasse son budget de durée, ou dont la politique a changé, passe en **PAUSED** et attend un examen.'),
  ...retenir('Une tâche durable ne rejoue jamais une action dont l’effet est incertain. Elle demande une réconciliation humaine.'),
];

const s9 = [
  H1('9. Mémoire et apprentissage'),
  P('L’agent apprend, mais sous contrôle. Il distingue soigneusement ce qu’il **retient** (mémoire), ce qu’il **sait faire** (compétences) et ce qu’on lui **impose** (charte).'),
  H2('9.1 La mémoire de conversation'),
  P('Chaque conversation garde ses {{history}} derniers messages. Quand cet historique devient trop long, il peut être **compacté** : les messages anciens sont remplacés par un résumé (10.4). Si une requête échoue sans identifiant de conversation fourni, son entrée est purgée : personne ne connaîtrait l’identifiant pour la retrouver.'),
  H2('9.2 La mémoire long terme'),
  P('Le modèle dispose d’outils pour **retenir** un fait et le **rappeler** plus tard ({{memory_capacity}} faits au plus, conservés {{memory_retention}} par défaut). Après chaque échange, un résumé est déposé avec le statut UNVERIFIED. Un administrateur peut le marquer comme contredit (CONTRADICTED).'),
  H2('9.3 Les compétences'),
  P('Une compétence est une procédure ou une règle écrite en texte, injectée dans le contexte du modèle. Elle suit un circuit de revue strict :'),
  ...table(['Statut', 'Signification'], [
    ['PENDING', 'Proposée : par le modèle à la suite d’une tâche réussie, par un humain, ou tirée de refus répétés.'],
    ['APPROVED', 'Validée par un administrateur : elle agit désormais.'],
    ['REJECTED', 'Refusée : un texte identique ne pourra plus être reproposé.'],
    ['RETIRED', 'Retirée par un administrateur : même interdiction de reproposition.'],
  ], [22, 78]),
  P('Deux portées existent :'),
  ...bullets([
    '**INVARIANT** : une règle valable partout, recopiée en entier dans chaque conversation ({{skills_invariant}} au plus) ;',
    '**DOMAIN** : une procédure liée à un sujet, déclenchée par des mots de la demande, ou lue à la demande via l’outil `read_skill` ({{skills_domain}} au plus par demande).',
  ]),
  P('Pour juger une compétence avant de l’approuver, l’administrateur dispose de trois outils :'),
  ...bullets([
    '**le rejeu contrastif** : la même question est posée avec et sans la compétence, et les deux réponses sont comparées (10.10) ;',
    '**la revue à l’aveugle** : les deux réponses sont présentées sans dire laquelle utilise la compétence, pour éviter de favoriser celle qu’on s’attend à préférer ;',
    '**les précédents** : les décisions passées comparables.',
  ]),
  H2('9.4 La charte'),
  P('La charte est un texte de référence rédigé par un administrateur, versionné, injecté dans tous les échanges. Contrairement aux compétences, elle ne passe pas par une revue : son seul auteur est un administrateur. Elle **restreint** ce que l’agent propose ; elle n’élargit jamais une autonomie.'),
  H2('9.5 La base de connaissance'),
  P('Facultative (`kex.agent.knowledge.enabled`), elle indexe des documents dans un magasin vectoriel. À chaque question, les passages les plus proches en sens sont ajoutés au contexte du modèle. Elle exige un modèle d’embeddings.'),
  ...retenir('L’agent propose, l’humain dispose : aucune règle apprise n’influence le comportement de l’agent sans l’accord explicite d’un administrateur.'),
];

const algo = (title, objectif, principe, details, ex) => [
  H2(title),
  new Paragraph({ children: [new TextRun({ text: 'Objectif. ', bold: true, color: ACCENT }), ...runs(objectif)], spacing: { after: 100, line: 300 } }),
  new Paragraph({ children: [new TextRun({ text: 'Principe. ', bold: true, color: ACCENT }), ...runs(principe)], spacing: { after: 100, line: 300 } }),
  ...(details ? bullets(details) : []),
  ...(ex ? exemple(ex) : []),
];

const s10 = [
  H1('10. Les algorithmes, un par un'),
  P('Ce chapitre décrit chaque algorithme avec la même grille : son **objectif**, son **principe**, ses détails et un **exemple** chiffré. Aucun n’est une « boîte noire » : tous sont déterministes, à l’exception des réponses du modèle lui-même.'),

  ...algo('10.1 Le seau à jetons (limite de débit)',
    'Empêcher qu’une clé API sature l’agent, tout en tolérant de courtes rafales.',
    'Chaque clé possède un seau qui contient au plus {{burst}} jetons et se remplit en continu au rythme de {{rate}} jetons par minute. Chaque requête consomme un jeton ; seau vide, la requête est refusée (HTTP 429).',
    ['le remplissage est calculé à la demande, à partir du temps écoulé depuis le dernier calcul : pas de minuterie ;',
      'les compteurs sont des entiers atomiques multipliés par un million : pas d’arrondi qui ferait perdre des fractions de jeton ;',
      'sous `shared-memory`, le seau vit dans une ligne de la base et vaut pour toutes les instances ; sinon chaque instance a le sien.'],
    'Un script envoie 30 requêtes d’un coup : les 20 premières passent (la réserve), les 10 suivantes sont refusées. Une seconde plus tard, le seau a regagné un jeton (60 par minute = 1 par seconde) : une nouvelle requête passe.'),

  ...algo('10.2 Le budget de jetons avec réservation',
    'Plafonner la dépense journalière en jetons du modèle, y compris quand plusieurs requêtes partent en même temps.',
    'Avant chaque appel, l’agent **réserve** une estimation des jetons sur le budget du jour. Si la réservation dépasse le plafond, l’appel est refusé avant de coûter quoi que ce soit. Après l’appel, la consommation réelle remplace la réservation.',
    ['réserver avant, plutôt que compter après, empêche dix requêtes simultanées de passer chacune sous le plafond puis de le dépasser ensemble ;',
      'des plafonds par jour, par locataire et en coût sont configurables (0 = sans plafond, par défaut) ;',
      'le cycle de supervision vérifie le budget avant tout appel.'],
    null),

  ...algo('10.3 La sélection d’outils par score lexical',
    'Quand un agent dispose de dizaines d’outils, n’en présenter au modèle que les plus pertinents : moins de jetons, moins de confusion.',
    'La demande et le nom de chaque outil sont découpés en mots, normalisés, puis comparés. Le score d’un outil est le nombre de mots qu’il partage avec la demande. Les {{tools_selected}} meilleurs sont gardés.',
    ['normalisation : minuscules, accents retirés (décomposition Unicode NFD), mots d’une lettre ignorés ;',
      'quelques synonymes français-anglais sont ajoutés : « retard » → lag, « consommateur » → consumer, « sujet » → topic, « prévision » → forecast… ;',
      'les **descriptions** des outils ne comptent pas : elles viennent du serveur MCP, donc d’une source non fiable qui pourrait les rédiger pour se faire choisir ;',
      'les outils retenus sont ensuite **triés par nom** : le score choisit, il n’ordonne pas, pour que le cache du fournisseur reste valable d’une requête à l’autre ;',
      'désactivée par défaut : un jeu d’outils qui change d’un message à l’autre coûte en cache plus qu’il n’économise.'],
    'Demande : « Quel est le retard du consommateur orders-sync ? ». Mots : retard, lag, consommateur, consumer, orders, sync… L’outil `consumer_lag` partage « consumer » et « lag » : score 2. L’outil `list_topics` : score 0.'),

  ...algo('10.4 La compaction du contexte',
    'Permettre des conversations longues sans dépasser la fenêtre du modèle ni faire exploser le coût.',
    'Quand l’historique estimé dépasse {{trigger_fraction}} du contexte disponible ({{context_tokens}} jetons par défaut), les messages anciens sont résumés par le modèle en {{summary_tokens}} jetons au plus ; les {{keep_recent}} messages les plus récents sont gardés intacts.',
    ['l’estimation est volontairement pessimiste (octets UTF-8 plutôt que mots, marge par message) ;',
      'le résumé est marqué `trust="untrusted"` et `status="UNVERIFIED"` : il peut avoir perdu une nuance, le modèle le sait ;',
      'un verrou par conversation empêche deux requêtes de compacter la même conversation en même temps ;',
      'désactivée par défaut (`kex.agent.harness.compaction.enabled`).'],
    'Une conversation de 52 000 jetons dépasse {{trigger_tokens}} ({{trigger_fraction}} de {{context_tokens}}) : les messages anciens deviennent un résumé d’environ 1 000 jetons, les {{keep_recent}} derniers restent tels quels. La requête suivante repart d’une dizaine de milliers de jetons.'),

  ...algo('10.5 Le disjoncteur et le réessai',
    'Éviter qu’un service en panne (fournisseur de modèle ou serveur MCP) ne bloque l’agent ou ne soit martelé de requêtes.',
    'Le disjoncteur observe les {{cb_window}} derniers appels. Dès que {{cb_minimum}} appels au moins ont eu lieu et que {{cb_failure_rate}} ont échoué, il **s’ouvre** : les appels suivants échouent immédiatement, sans attendre. Après {{cb_open_wait}}, il laisse passer {{cb_half_open}} appels d’essai ; s’ils réussissent, il se referme.',
    ['un disjoncteur par serveur MCP pour les appels directs : une panne n’ouvre pas celui des autres ;',
      'un disjoncteur `agent-model` devant le fournisseur, sur l’appel bloquant comme sur le flux en direct ;',
      'réessai : jusqu’à {{retry_attempts}} tentatives espacées de {{retry_wait}} pour les erreurs passagères.'],
    null),

  ...algo('10.6 Le plafond de durée d’un flux',
    'Garantir qu’aucune réponse en direct ne tienne une connexion indéfiniment.',
    'Le flux de réponse est coupé au bout d’une durée totale fixe ({{request_timeout}}), quelle que soit l’activité. Un simple délai « entre deux morceaux » ne suffit pas : vingt tours d’outils entrecoupés de texte ne se taisent jamais assez longtemps pour le déclencher.',
    null, null),

  ...algo('10.7 Le filtrage des anomalies sans preuve',
    'Empêcher qu’une affirmation du modèle, non mesurée, déclenche une alerte ou une action.',
    'Chaque anomalie proposée est confrontée à la liste des appels d’outils **réussis** du cycle. Une anomalie dont aucune observation ne cite un tel appel est écartée et comptée à part. Un processus dont l’analyse a échoué passe en UNKNOWN.',
    null, null),

  ...algo('10.8 La vérification avec fenêtre de stabilisation',
    'Confirmer qu’une action a produit son effet, même quand cet effet n’est pas immédiat.',
    'Après l’action, l’agent relit l’état avec un outil vérificateur et compare à la postcondition attendue. Si la relecture **contredit** l’attente, il relit à nouveau tant que la fenêtre de stabilisation (`settle`) n’est pas écoulée. Il ne relit que ce cas : un succès ou une erreur sont définitifs.',
    ['certains systèmes publient une demande puis l’appliquent plus tard : une relecture immédiate verrait encore l’ancien état et conclurait à tort à un échec ;',
      'une erreur pendant la vérification ne rend jamais l’action « à refaire » : l’action a déjà été acceptée.'],
    null),

  ...algo('10.9 Les verrous',
    'Garantir qu’une même chose ne soit faite qu’une fois, même avec plusieurs instances ou des clics répétés.',
    'L’agent utilise quatre verrous, chacun adapté à son objet :',
    ['**verrou de cycle** : en base, une ligne avec une date d’expiration (`UPDATE … WHERE locked_until < maintenant`), prise pour {{lock_at_most}} au plus ; si l’instance qui la tient tombe, le verrou expire seul ;',
      '**verrou de décision** : un verrou en mémoire par identifiant, pris sans attendre (`tryLock`) ; le second clic reçoit une réponse « déjà en cours » au lieu d’exécuter deux fois ;',
      '**bail de tâche** : une date de fin de bail enregistrée avec la tâche ; une autre instance ne peut la reprendre qu’après expiration ;',
      '**verrou de conversation** : empêche deux compactions simultanées d’une même conversation.'],
    'Pourquoi pas le verrou natif de PostgreSQL (`pg_advisory_lock`) ? Il est attaché à une connexion. Or le pool en prête une différente à chaque appel : celle qui relâche le verrou n’est pas forcément celle qui l’a pris. La ligne à expiration n’a pas ce problème.'),

  ...algo('10.10 Le rejeu contrastif d’une compétence',
    'Mesurer l’effet d’une compétence avant de l’approuver.',
    'La même question est posée deux fois au modèle, avec les mêmes outils : une fois sans la compétence candidate, une fois avec. Les deux réponses et leurs appels d’outils sont présentés côte à côte, éventuellement à l’aveugle. Plusieurs candidates peuvent être rejouées ensemble.',
    ['un rejeu n’est l’échange de personne : il n’écrit rien en mémoire long terme, et sa conversation est purgée ;',
      'les compétences approuvées sont classées de la plus récemment approuvée à la plus ancienne ; celles au-delà des plafonds sont listées par la curation, jamais retirées automatiquement.'],
    null),

  ...algo('10.11 La règle tirée des refus répétés',
    'Transformer des refus humains concordants en règle explicite, pour que l’agent cesse de proposer ce qu’on lui refuse.',
    'Au {{min_refusals}}e refus sur une même capacité, l’agent rédige une proposition de compétence à partir des motifs de refus, **sans appel au modèle**. Le texte est déterministe : une même série de refus donne toujours la même proposition, que la déduplication reconnaît.',
    ['les déclencheurs de la règle sont les noms des processus visés, car c’est ce que le prompt du cycle mentionne ;',
      'la proposition reste PENDING jusqu’à ce qu’un administrateur la juge.'],
    null),

  ...algo('10.12 Le score de confiance d’un serveur MCP',
    'Aider l’administrateur à juger un serveur MCP avant de l’installer depuis un catalogue.',
    'Dix critères pondérés totalisent 100 points. Chaque critère est rempli, non rempli ou **inconnu** ; un critère inconnu n’est jamais compté comme rempli.',
    null, null),
  ...table(['Critère', 'Points'], [
    ['Éditeur officiel ou identité vérifiée', '20'], ['Provenance source → build vérifiable', '15'],
    ['Signature ou attestation de l’artefact', '10'], ['SBOM disponible', '10'], ['Analyse des CVE et dépendances', '10'],
    ['Projet activement maintenu', '10'], ['Permissions minimales', '10'], ['Outils et effets de bord documentés', '5'],
    ['Isolation ou conteneur disponible', '5'], ['Réputation et adoption', '5'],
  ], [80, 20]),

  ...algo('10.13 La signature des rappels de messagerie',
    'Permettre d’approuver une décision depuis Slack sans ouvrir la porte à des approbations falsifiées.',
    'Slack ne peut pas envoyer de jeton d’API. Chaque rappel est donc signé : un HMAC-SHA256 est calculé sur le corps brut **et** l’horodatage avec un secret partagé, puis comparé en temps constant.',
    ['signer le corps sans l’horodatage permettrait de rejouer un vieux message ; signer l’horodatage sans le corps permettrait d’en réécrire le contenu ;',
      'la comparaison en temps constant (`MessageDigest.isEqual`) ne révèle rien par son temps de réponse ;',
      'chaque expéditeur Slack est associé à un acteur d’audit nommé : l’audit dit qui a approuvé, pas « Slack » ;',
      'la fonction est éteinte par défaut, et l’agent refuse de démarrer si elle est activée sans secret.'],
    null),
];

const s11 = [
  H1('11. Les gains apportés'),
  P('Les gains ci-dessous découlent directement des mécanismes décrits. Ils sont qualitatifs : leur ampleur chiffrée dépend de chaque installation (nombre de processus, fréquence des incidents, coût du modèle).'),
  ...table(['Gain', 'D’où il vient'], [
    ['**Surveillance continue sans fatigue**', 'Le cycle planifié analyse tous les processus à intervalle régulier, de jour comme de nuit, avec une cadence qui s’accélère quand un problème est vu.'],
    ['**Moins de fausses alertes**', 'Une anomalie sans mesure d’outil est écartée ; un état illisible est UNKNOWN, pas une alerte inventée.'],
    ['**Diagnostic plus rapide**', 'L’agent rassemble les mesures de plusieurs outils et les résume en langage clair ; la conversation permet de creuser sans connaître chaque outil.'],
    ['**Actions sûres et traçables**', 'Autonomie par capacité, plancher de confiance, validation humaine, vérification après coup, audit complet de qui a fait quoi.'],
    ['**Coût maîtrisé**', 'Budget journalier réservé avant la dépense, cache du fournisseur préservé, compaction et sélection d’outils facultatives, cadence adaptative.'],
    ['**Robustesse**', 'Démarrage même si un serveur MCP est injoignable, disjoncteurs isolant les pannes, tâches reprenables après redémarrage.'],
    ['**Savoir capitalisé sous contrôle**', 'Les leçons tirées des tâches et des refus deviennent des compétences, mais seulement après revue humaine.'],
    ['**Ouverture**', 'Protocole MCP standard : nouveaux outils sans modifier l’agent ; Kex lui-même exposé à d’autres agents.'],
    ['**Indépendance vis-à-vis du fournisseur**', 'Anthropic, OpenAI ou modèle local (Ollama, vLLM) : un réglage, pas une réécriture.'],
    ['**Passage à l’échelle**', 'Profil `shared-memory` : plusieurs instances partagent état, verrous et budgets.'],
  ], [30, 70]),
  ...retenir('Le gain principal n’est pas l’automatisation pour elle-même, mais une **automatisation dont on peut se fier aux affirmations** : chaque état est mesuré, chaque action est autorisée, vérifiée et tracée.'),
];

const s12 = [
  H1('12. Les contraintes et les limites'),
  P('Un usage avisé de l’agent exige d’en connaître les limites. Elles sont listées ici sans fard.'),
  H2('12.1 Contraintes de fonctionnement'),
  ...table(['Contrainte', 'Conséquence pratique'], [
    ['**Un fournisseur de modèle est nécessaire**', 'Sans clé (ou modèle local), l’agent est en état DEGRADED : ni conversation ni cycle.'],
    ['**Coût et latence du modèle**', 'Chaque question et chaque cycle consomment des jetons et prennent quelques secondes à quelques dizaines de secondes.'],
    ['**Explorer est en lecture seule**', 'Avec le seul serveur par défaut, l’agent observe et recommande, mais n’agit pas. Agir demande un serveur MCP capable de le faire.'],
    ['**Multi-instance = PostgreSQL**', 'Sans le profil `shared-memory`, une seule instance doit tourner.'],
    ['**La qualité dépend des outils**', 'L’agent ne voit que ce que ses outils mesurent. Un indicateur absent des outils est invisible.'],
    ['**Vérification à configurer**', 'Sans vérificateur lié à une capacité, une action reste EXECUTED_UNVERIFIED.'],
  ], [32, 68]),
  H2('12.2 Limites inhérentes aux modèles de langage'),
  ...bullets([
    '**Non-déterminisme** : la même question peut recevoir deux formulations différentes. Les garde-fous (preuves, plancher, validation) encadrent ce qui compte, pas le style.',
    '**Erreur possible** : un modèle peut mal interpréter une mesure. C’est pourquoi les actions à effet réel restent, en général, supervisées.',
    '**Injection** : un texte malveillant dans un résultat d’outil pourrait tenter de détourner le modèle. Il est balisé comme non fiable, les outils d’écriture sont cachés au modèle, et les arguments qu’il rédige passent par un humain ; le risque est réduit, pas nul.',
    '**Résumés imparfaits** : la compaction peut perdre une nuance ; le résumé est marqué non vérifié.',
  ]),
  H2('12.3 Limites connues de l’implémentation'),
  ...bullets([
    'Le disjoncteur des appels pilotés par le modèle est **partagé** entre serveurs MCP : la bibliothèque n’indique pas de quelle connexion vient chaque outil.',
    'La sélection d’outils est **lexicale** : elle ne comprend pas les synonymes au-delà d’une courte liste.',
    'Les **cartes interactives Teams** ne peuvent pas déclencher d’action sans un Bot Framework enregistré : l’approbation depuis Teams n’est pas disponible, contrairement à Slack.',
    'Les **prévisions** sont des estimations : elles annoncent un risque, elles ne confirment pas un incident.',
    'Les **compétences** sont plafonnées ({{skills_domain}} de domaine, {{skills_invariant}} invariantes) : celles au-delà ne s’appliquent pas, et la curation le signale.',
  ]),
  ...attention('Une fonctionnalité désactivée n’est pas une panne. Une route conditionnée par une propriété répond 404 quand la propriété est fausse ; la console le sait par `whoami` et n’affiche pas d’erreur.'),
];

const s13 = [
  H1('13. Mettre en route'),
  H2('13.1 Démarrage rapide'),
  ...steps([
    'Fournir une clé de modèle (par exemple `ANTHROPIC_API_KEY`) et une clé d’API pour l’agent (`kex.agent.api-key`).',
    'Lancer l’agent : `./mvnw spring-boot:run`, ou l’image Docker ; il écoute sur le port 8081.',
    'Ouvrir la console dans un navigateur, saisir la clé d’API : la page « Premiers pas » liste ce qui reste à configurer.',
    'Joindre un serveur MCP (Kafka SQL Explorer par défaut) depuis « MCP & intégrations ».',
    'Déclarer au moins un processus à surveiller, puis lancer un premier cycle.',
  ]),
  H2('13.2 Les réglages qui comptent le plus'),
  ...table(['Propriété', 'Défaut', 'Effet'], [
    ['`spring.ai.model.chat`', '—', 'Choix du fournisseur de modèle (anthropic, openai…).'],
    ['`kex.agent.supervision.confidence-threshold`', '{{confidence}}', 'Plancher de confiance des actions automatiques.'],
    ['`kex.agent.supervision.approval-timeout`', '{{approval_timeout_short}}', 'Délai avant expiration d’une décision en attente.'],
    ['`kex.agent.supervision.schedule.enabled`', 'false', 'Cycle planifié (avec `shared-memory` ou instance unique déclarée).'],
    ['`kex.agent.supervision.schedule.interval`', '{{schedule_interval_short}}', 'Battement du planificateur.'],
    ['`kex.agent.token-budget.daily-limit`', '{{daily_limit}}', 'Plafond journalier de jetons (0 = aucun).'],
    ['`kex.agent.rate-limit.requests-per-minute`', '{{rate}}', 'Débit par clé API (réserve de {{burst}}).'],
    ['`kex.agent.tasks.enabled`', 'false', 'Tâches durables.'],
    ['`kex.agent.knowledge.enabled`', 'false', 'Base de connaissance.'],
    ['`kex.agent.harness.compaction.enabled`', 'false', 'Compaction du contexte.'],
    ['`kex.mcp.server.enabled`', 'false', 'Exposer Kex comme serveur MCP.'],
  ], [44, 14, 42]),
  H2('13.3 Pour aller plus loin'),
  ...bullets([
    '`docs/ARCHITECTURE-LOGICIELLE.md` : la référence technique, paquet par paquet.',
    '`docs/ARCHITECTURE.md` : le journal des décisions et leurs raisons.',
    '`docs/CONFIGURATION.md` : toutes les propriétés.',
    '`docs/OBSERVABILITE.md` : métriques et traces.',
  ]),
];

// ── Document ──────────────────────────────────────────────────────────

const all = [...s1, ...s2, ...s3, ...s4, ...s5, ...s6, ...s7, ...s8, ...s9, ...s10, ...s11, ...s12, ...s13];

const heading = (size, color, before, after) => ({ run: { font: FONT, size, bold: true, color }, paragraph: { spacing: { before, after }, keepNext: true, keepLines: true } });

const doc = new Document({
  creator: 'Kex Agent AI', title: 'Comprendre l’agent — guide pédagogique', language: 'fr-FR',
  styles: {
    default: { document: { run: { font: FONT, size: 22, color: INK } } },
    paragraphStyles: [
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true, ...heading(36, INK, 0, 240) },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true, ...heading(28, ACCENT, 320, 120) },
      { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true, ...heading(24, INK, 220, 80) },
    ],
  },
  numbering: {
    config: [
      { reference: 'puces', levels: [
        { level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 500, hanging: 260 } } } },
        { level: 1, format: LevelFormat.BULLET, text: '–', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 900, hanging: 260 } } } },
      ] },
      ...numberingRefs.map((reference) => ({ reference, levels: [
        { level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 500, hanging: 320 } } } },
      ] })),
    ],
  },
  sections: [{
    properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 1134, bottom: 1134, left: 1134, right: 1134 } }, titlePage: true },
    headers: { default: new Header({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [new TextRun({ text: 'Kex Agent AI — Guide pédagogique', size: 17, color: MUTED })] })] }) },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [new TextRun({ children: ['Page ', PageNumber.CURRENT, ' / ', PageNumber.TOTAL_PAGES], size: 17, color: MUTED })] })] }) },
    children: [...cover, ...all],
  }],
});

Packer.toBuffer(doc).then((buf) => { fs.writeFileSync(OUT, buf); console.log('écrit', OUT); });
