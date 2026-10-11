// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Le premier démarrage, tel que la CI le monte : client MCP éteint, processus déclarés. Chaque cas
// verrouille un défaut vu à l'écran sur une installation neuve, que la suite Java ne voit pas
// puisqu'elle n'exécute aucune ligne de JavaScript ni de CSS.
//
//   PLAYWRIGHT_MODULE=… KEX_AGENT_URL=http://localhost:8081 node src/test/browser/first-run.mjs

import assert from 'node:assert/strict';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');

const BASE = process.env.KEX_AGENT_URL ?? 'http://localhost:8081';
const TOKEN = process.env.KEX_AGENT_API_KEY ?? 'ci-secret';

const problems = [];
const checks = [];

async function check(name, body) {
  try {
    await body();
    checks.push(`  ✓ ${name}`);
  } catch (error) {
    problems.push(`  ✗ ${name}\n      ${error.message.split('\n')[0]}`);
  }
}

const browser = await chromium.launch();

async function open(viewport, view) {
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  await page.addInitScript((token) => sessionStorage.setItem('kex.agent.api-key', token), TOKEN);
  await page.goto(`${BASE}/#/${view}`, { waitUntil: 'domcontentloaded' });
  await page.locator('#credentials').evaluate((dialog) => { if (dialog.open) dialog.close(); });
  return { context, page };
}

const desktop = { width: 1440, height: 900 };
const phone = { width: 390, height: 844 };

await check('la mise en route passe en tête et ne coche pas un serveur MCP injoignable', async () => {
  const { context, page } = await open(desktop, 'overview');
  await page.locator('#onboarding:not([hidden])').waitFor();
  const steps = await page.locator('#onboarding-steps').innerText();
  assert.match(steps, /Joindre un serveur MCP/);
  assert.match(steps, /Aucune connexion déclarée/);
  // Premier bloc visible du tableau de bord : en quatrième position, il restait sous la ligne de
  // flottaison pendant que le reste de l'écran parlait d'une veille qui n'avait pas commencé.
  const first = await page.evaluate(() => [...document.querySelectorAll('#view-overview [data-dashboard-block]')]
    .filter((node) => !node.hidden && node.offsetParent !== null)
    .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top)[0]?.id);
  assert.equal(first, 'onboarding');
  await context.close();
});

await check('« Exécuter maintenant » est désactivé et dit pourquoi', async () => {
  const { context, page } = await open(desktop, 'overview');
  await page.locator('#onboarding:not([hidden])').waitFor();
  const button = page.locator('#run-cycle');
  await page.waitForFunction(() => document.querySelector('#run-cycle').disabled);
  assert.match(await button.getAttribute('title'), /serveur MCP joignable/);
  const launch = page.locator('#onboarding-steps .onboarding-step', { hasText: 'Exécuter le premier cycle' }).locator('button');
  assert.equal(await launch.isDisabled(), true);
  await context.close();
});

await check('une lecture indisponible mène à la fiche de la connexion', async () => {
  const { context, page } = await open(desktop, 'forecasts');
  const link = page.locator('#view-forecasts a.empty-action', { hasText: 'Intégrations' }).first();
  await link.waitFor();
  assert.equal(await link.getAttribute('href'), '#/integrations?q=kafka-explorer');
  assert.doesNotMatch(await page.locator('#view-forecasts').innerText(), /page MCP/);
  await context.close();
});

await check('le pied du rail tient dans le rail, sur une ligne', async () => {
  const { context, page } = await open(desktop, 'overview');
  const rail = await page.locator('.rail').boundingBox();
  const toggle = await page.locator('#rail-toggle').boundingBox();
  const user = await page.locator('.rail-foot .nav-item').boundingBox();
  assert.ok(toggle.x + toggle.width <= rail.x + rail.width, `« Replier » déborde du rail (${toggle.x + toggle.width} > ${rail.x + rail.width})`);
  assert.ok(user.height < 48, `« Espace utilisateur » passe sur deux lignes (${user.height} px)`);
  await page.locator('#rail-toggle').click();
  const collapsed = await page.locator('.rail').boundingBox();
  const after = await page.locator('#rail-toggle').boundingBox();
  assert.ok(after.x + after.width <= collapsed.x + collapsed.width, 'le pied déborde du rail replié');
  await page.locator('#rail-toggle').click();
  await context.close();
});

await check('la zone de saisie de la conversation est visible sans défiler', async () => {
  const { context, page } = await open(desktop, 'chat');
  await page.locator('#prompt').waitFor();
  const box = await page.locator('#prompt').boundingBox();
  assert.ok(box.y + box.height <= desktop.height, `saisie sous la ligne de flottaison (${Math.round(box.y + box.height)} > ${desktop.height})`);
  await context.close();
});

await check('sur mobile, la mise en route passe avant « Demande une décision »', async () => {
  const { context, page } = await open(phone, 'overview');
  await page.locator('#onboarding:not([hidden])').waitFor();
  const onboarding = await page.locator('#onboarding').boundingBox();
  const decision = await page.locator('#overview-attention').boundingBox();
  assert.ok(onboarding.y < decision.y, 'la mise en route arrive après la décision');
  // L'état s'affiche en entier : un « ? » seul ne dit rien de l'état de l'agent.
  assert.match(await page.locator('#agent-status').innerText(), /ATTENTE/);
  assert.ok(await page.locator('#agent-status-label').isVisible());
  await context.close();
});

await check('sur mobile, l’en-tête reste compact et l’entrée courante est visible', async () => {
  const { context, page } = await open(phone, 'integrations');
  await page.locator('#view-integrations:not([hidden])').waitFor();
  const topbar = await page.locator('.topbar').boundingBox();
  assert.ok(topbar.height <= 130, `en-tête de ${Math.round(topbar.height)} px sur ${phone.height}`);
  // Les icônes gardent un nom accessible une fois les libellés masqués.
  assert.equal(await page.getByRole('button', { name: 'Rechercher' }).isVisible(), true);
  const nav = await page.locator('.rail nav').boundingBox();
  const current = await page.locator('.rail nav .nav-item[aria-current="page"]').boundingBox();
  assert.ok(current.x >= nav.x - 1 && current.x + current.width <= nav.x + nav.width + 1,
    'l’entrée courante est hors de la barre de navigation visible');
  await context.close();
});

await check('l’état de l’agent n’apparaît qu’une fois sur la Vue d’ensemble, sans promettre de calme', async () => {
  const { context, page } = await open(desktop, 'overview');
  await page.locator('#onboarding:not([hidden])').waitFor();
  assert.equal(await page.locator('#overview-health').count(), 0);
  const occurrences = await page.locator('#view-overview').getByText('EN ATTENTE D’ANALYSE').count();
  assert.equal(occurrences, 0, 'l’état est répété dans la vue au lieu de la seule pastille d’en-tête');
  // Avant tout cycle, rien ne permet de dire qu'aucune intervention n'est requise.
  await page.waitForFunction(() => /pas encore analysé/.test(document.querySelector('#overview-summary').textContent));
  await context.close();
});

await check('les disjoncteurs et le mode se lisent sans vocabulaire Resilience4j', async () => {
  const { context, page } = await open(desktop, 'agent');
  const summary = page.locator('#agent-summary');
  await summary.getByText('Modèle', { exact: false }).first().waitFor();
  const text = await summary.innerText();
  assert.doesNotMatch(text, /\b(CLOSED|OPEN|HALF_OPEN|SUPERVISED|AUTOMATIC|MANUAL)\b/);
  assert.match(text, /Supervisé|Automatique|Manuel/);
  assert.match(text, /Normal|Coupé|En test/);
  await context.close();
});

await check('Prévisions : le diagnostic s’ouvre seul, en clair, et la mise en garde suit le contenu', async () => {
  const { context, page } = await open(desktop, 'forecasts');
  const readiness = page.locator('#forecast-readiness');
  await readiness.getByText('Préparation MCP bloquée').waitFor();
  const text = await readiness.innerText();
  assert.match(text, /Connexion à KafkaExplorer/);
  assert.doesNotMatch(text, /\b(connection|visible-series) ·/);
  assert.equal(await readiness.locator('a', { hasText: 'Intégrations' }).getAttribute('href'), '#/integrations?q=kafka-explorer');
  assert.equal(await page.locator('#view-forecasts p.banner').count(), 0, 'la mise en garde reste en bandeau d’alerte');
  const order = await page.evaluate(() => {
    const content = document.querySelector('#forecast-content');
    const caveat = document.querySelector('.forecast-caveat');
    return Boolean(content.compareDocumentPosition(caveat) & Node.DOCUMENT_POSITION_FOLLOWING);
  });
  assert.ok(order, 'la mise en garde précède les résultats');
  await context.close();
});

await check('un serveur MCP Kex éteint se dit désactivé, sans toast d’erreur', async () => {
  const { context, page } = await open(desktop, 'settings');
  await page.locator('#mcp-server-disabled:not([hidden])').waitFor();
  assert.equal(await page.textContent('#mcp-server-status-label'), 'Désactivé');
  assert.match(await page.locator('#mcp-server-disabled').innerText(), /KEX_MCP_SERVER_ENABLED=true/);
  assert.equal(await page.locator('#mcp-server-body').isHidden(), true);
  assert.equal(await page.getByText('MCP HTTP 404').count(), 0, 'une fonctionnalité éteinte rendue comme une panne');
  await context.close();
});

await check('la mise en route envoie déclarer un processus vers l’assistant guidé', async () => {
  const { context, page } = await open(desktop, 'overview');
  // La CI déclare des processus : on ne retire que ce compte, pour voir l'étape apparaître.
  await page.route('**/api/agent/supervision/overview', async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, processesMonitored: 0 } });
  });
  await page.reload({ waitUntil: 'domcontentloaded' });
  const step = page.locator('#onboarding-steps .onboarding-step', { hasText: 'Déclarer les processus surveillés' });
  await step.waitFor();
  assert.equal(await step.locator('a').getAttribute('href'), '#/chat?creation=process');
  await context.close();
});

await check('les libellés restent en français : Attention, Essais, santé en clair', async () => {
  const { context, page } = await open(desktop, 'processes');
  await page.locator('[data-filter="WARNING"]').waitFor();
  assert.equal((await page.textContent('[data-filter="WARNING"]')).trim(), 'Attention');
  await page.goto(`${BASE}/#/settings`, { waitUntil: 'domcontentloaded' });
  assert.equal((await page.textContent('[data-mcp-tab="playground"]')).trim(), 'Essais');
  await page.goto(`${BASE}/#/system`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => /En service/.test(document.querySelector('#health-summary')?.textContent || ''));
  assert.match(await page.textContent('#health-summary'), /vivacité, disponibilité/);
  assert.equal(await page.locator('#health-dump').isVisible(), false, 'le JSON brut reste replié');
  await context.close();
});

await check('le contexte opérateur ne s’affiche que sur les vues qu’il filtre, sous leur titre', async () => {
  const { context, page } = await open(desktop, 'integrations');
  await page.locator('#view-integrations:not([hidden])').waitFor();
  for (const view of ['integrations', 'agent', 'settings', 'system', 'chat', 'forecasts', 'knowledge', 'tasks']) {
    await page.goto(`${BASE}/#/${view}`, { waitUntil: 'domcontentloaded' });
    await page.locator(`#view-${view}:not([hidden])`).waitFor();
    assert.equal(await page.locator('#operator-context').isVisible(), false, `filtres affichés sur ${view}, qui n’en lit rien`);
  }
  for (const view of ['processes', 'decisions', 'alerts', 'audit', 'activity', 'attention', 'incidents']) {
    await page.goto(`${BASE}/#/${view}`, { waitUntil: 'domcontentloaded' });
    await page.locator(`#view-${view} #operator-context:not([hidden])`).waitFor();
    const heading = await page.locator(`#view-${view} > .workspace-heading`).boundingBox();
    const filters = await page.locator('#operator-context').boundingBox();
    assert.ok(filters.y >= heading.y + heading.height, `filtres au-dessus du titre sur ${view}`);
  }
  // Les listes reprenaient le 10 px majuscule de leur étiquette.
  const size = await page.locator('#context-process').evaluate((node) => parseFloat(getComputedStyle(node).fontSize));
  assert.ok(size >= 12, `liste du contexte en ${size} px`);
  await context.close();
});

await check('chaque vue s’ouvre sur le même en-tête : un titre, une phrase, les actions à droite', async () => {
  const { context, page } = await open(desktop, 'processes');
  for (const view of ['forecasts', 'activity', 'attention', 'incidents', 'agent', 'processes', 'decisions', 'settings',
    'alerts', 'audit', 'integrations', 'knowledge', 'system', 'tasks']) {
    const shape = await page.evaluate((key) => {
      const section = document.querySelector(`#view-${key}`);
      const heading = section.querySelector(':scope > .workspace-heading');
      return {
        first: section.firstElementChild === heading,
        titles: section.querySelectorAll('h1').length,
        sentence: heading?.querySelector('h1 + p')?.textContent.trim().length > 0,
        kicker: Boolean(heading?.querySelector('.panel-kicker')),
      };
    }, view);
    assert.deepEqual(shape, { first: true, titles: 1, sentence: true, kicker: false }, `en-tête hors gabarit sur ${view}`);
  }
  await context.close();
});

await check('« Vues enregistrées » n’apparaît qu’une fois une vue enregistrée', async () => {
  const { context, page } = await open(desktop, 'processes');
  await page.evaluate(() => localStorage.removeItem('kex.agent.saved-process-views'));
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.locator('#save-process-view').waitFor();
  assert.equal(await page.locator('#saved-process-view').isVisible(), false);
  assert.equal(await page.locator('#delete-process-view').isVisible(), false, '« Supprimer » rouge sans rien à supprimer');
  page.once('dialog', (dialog) => dialog.accept('Mes erreurs'));
  await page.locator('#save-process-view').click();
  await page.locator('#saved-process-view').waitFor();
  assert.equal(await page.locator('#delete-process-view').isVisible(), true);
  await page.locator('#delete-process-view').click();
  await page.locator('#saved-process-view').waitFor({ state: 'hidden' });
  await context.close();
});

await check('À traiter vide : l’action mise en avant est de préparer un processus', async () => {
  const { context, page } = await open(desktop, 'attention');
  await page.route('**/api/agent/supervision/overview', async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, processesMonitored: 0, processes: [] } });
  });
  await page.reload({ waitUntil: 'domcontentloaded' });
  const action = page.locator('#attention-workspace a.empty-action', { hasText: 'Préparer un processus' });
  await action.waitFor();
  const background = (locator) => locator.evaluate((node) => getComputedStyle(node).backgroundColor);
  assert.notEqual(await background(action), await background(page.locator('#refresh-attention')),
    'l’action utile a le même poids qu’« Actualiser »');
  assert.doesNotMatch(await page.locator('#refresh-attention').getAttribute('class'), /primary/,
    '« Actualiser » reste le bouton principal d’une file vide');
  await context.close();
});

await check('les jetons consommés : un zéro mesuré s’écrit zéro, une mesure absente se dit absente', async () => {
  const { context, page } = await open(desktop, 'system');
  const asked = [];
  page.on('request', (request) => { if (request.url().includes('gen_ai.client.token.usage')) asked.push(request.url()); });
  await page.reload({ waitUntil: 'domcontentloaded' });
  const tokens = page.locator('#metric-tiles .tile', { hasText: 'Jetons consommés' });
  await tokens.waitFor();
  // La CI n'appelle aucun modèle : le compteur n'existe pas encore, ce qui est un vrai zéro.
  assert.match(await tokens.innerText(), /\b0\b[\s\S]*depuis le démarrage/);
  // Spring AI compte input, output et total : sans filtre, l'endpoint rend la somme des trois.
  assert.ok(asked.every((url) => decodeURIComponent(url).includes('tag=gen_ai.token.type:total')), asked.join(' '));
  await page.route('**/actuator/metrics/gen_ai.client.token.usage**', (route) => route.fulfill({ status: 500, body: '' }));
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.locator('#metric-tiles .tile', { hasText: 'non mesuré' }).first().waitFor();
  assert.match(await page.locator('#metric-tiles .tile', { hasText: 'Jetons consommés' }).innerText(), /non mesuré/);
  assert.doesNotMatch(await page.locator('#metric-tiles').innerText(), /—/);
  await context.close();
});

await check('« Lancer une analyse » d’une page vide mène à ce qui bloque le cycle, puis le lance vraiment', async () => {
  const { context, page } = await open(desktop, 'decisions');
  // Sans serveur MCP joignable, le bouton nomme le blocage et mène à sa levée — plus de lien
  // « Lancer une analyse » vers la Vue d'ensemble, qui ne lançait rien.
  for (const view of ['decisions', 'incidents', 'activity']) {
    await page.goto(`${BASE}/#/${view}`, { waitUntil: 'domcontentloaded' });
    const action = page.locator(`#view-${view} [data-cycle-action]`);
    await page.waitForFunction((key) => document.querySelector(`#view-${key} [data-cycle-action]`)?.textContent === 'Joindre un serveur MCP', view);
    assert.equal(await action.getAttribute('href'), '#/integrations', view);
    assert.equal(await page.locator(`#view-${view} a[href="#/overview"]`, { hasText: 'Lancer une analyse' }).count(), 0, view);
  }
  // Blocage levé : le même bouton lance le cycle sur place.
  await page.route('**/api/agent/mcp/servers', (route) => route.fulfill({ json: [{ connection: 'kafka-explorer', initialized: true, tools: [] }] }));
  let launched = 0;
  await page.route('**/api/agent/supervision/cycles', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    launched += 1;
    return route.fulfill({ json: { cycleId: 'c-test', events: [] } });
  });
  await page.goto(`${BASE}/#/decisions`, { waitUntil: 'domcontentloaded' });
  await page.reload({ waitUntil: 'domcontentloaded' });
  const action = page.locator('#view-decisions [data-cycle-action]');
  await page.waitForFunction(() => document.querySelector('#view-decisions [data-cycle-action]')?.textContent === 'Lancer une analyse');
  await action.click();
  await page.waitForFunction(() => !location.hash.includes('overview'));
  await page.waitForTimeout(500);
  assert.equal(launched, 1, 'le bouton n’a pas lancé de cycle');
  assert.match(page.url(), /#\/decisions/);
  await context.close();
});

await check('une page vide dit la vraie cause : jamais analysé, filtre trop étroit, ou rien', async () => {
  const { context, page } = await open(desktop, 'decisions');
  await page.evaluate(() => localStorage.setItem('kex.agent.operator-context', JSON.stringify({ process: '', period: '1h' })));
  await page.reload({ waitUntil: 'domcontentloaded' });
  const list = page.locator('#decisions-list');
  await list.getByText('Aucun cycle exécuté.').waitFor();
  assert.doesNotMatch(await list.innerText(), /Élargissez/);
  // Un cycle a eu lieu, mais rien ne tombe dans la dernière heure : le filtre se nomme et se lève.
  await page.route('**/api/agent/supervision/overview', async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, agent: { ...body.agent, lastCycleAt: '2026-01-01T00:00:00Z' } } });
  });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => /dans ce contexte/.test(document.querySelector('#decisions-list').textContent));
  assert.match(await list.innerText(), /Filtre actif : dernière heure/);
  await list.getByRole('button', { name: 'Afficher toute la période' }).click();
  await list.getByText('Aucune décision pour l’instant.').waitFor();
  assert.equal(await page.inputValue('#context-period'), 'all');
  await context.close();
});

await check('les en-têtes triables offrent une cible de 24 px', async () => {
  const { context, page } = await open(desktop, 'processes');
  await page.locator('#processes-table th button.sort').first().waitFor();
  const sizes = await page.$$eval('#processes-table th button.sort', (nodes) => nodes.map((node) => node.getBoundingClientRect().height));
  assert.ok(sizes.length && sizes.every((height) => height >= 24), `cibles de ${sizes.join(', ')} px`);
  await context.close();
});

await check('Intégrations sans serveur : un seul état vide, un seul nom pour le geste, pas de recherche à vide', async () => {
  const { context, page } = await open(desktop, 'integrations');
  const panel = page.locator('.mcp-cockpit-panel');
  await panel.getByText('Aucun serveur MCP connecté.').waitFor();
  assert.equal(await panel.locator('[data-empty-state]').count(), 1, 'deux états vides empilés');
  assert.deepEqual(await panel.locator('[data-empty-state] .empty-action').allInnerTexts(), ['Ajouter un serveur']);
  assert.equal(await page.getByText('Ajouter une connexion').count(), 0);
  assert.equal(await page.locator('#tools-search').isVisible(), false);
  await panel.locator('[data-empty-state] .empty-action').click();
  assert.equal(await page.locator('#add-mcp-server').evaluate((dialog) => dialog.open), true);
  await context.close();
});

await check('Vue d’ensemble : la mise en route d’abord, les filtres ensuite, sur la ligne de « Personnaliser »', async () => {
  const { context, page } = await open(desktop, 'overview');
  await page.locator('#onboarding:not([hidden])').waitFor();
  const [onboarding, filters, customize] = await Promise.all([
    page.locator('#onboarding').boundingBox(),
    page.locator('#operator-context').boundingBox(),
    page.locator('#dashboard-customizer summary').boundingBox(),
  ]);
  assert.ok(onboarding.y + onboarding.height <= filters.y, 'les filtres passent au-dessus des « Premiers pas »');
  assert.ok(Math.abs((customize.y + customize.height / 2) - (filters.y + filters.height / 2)) < 20, '« Personnaliser » occupe sa propre ligne');
  await context.close();
});

await browser.close();
console.log(checks.join('\n'));
if (problems.length) {
  console.error(`\n${problems.length} échec(s) :\n${problems.join('\n')}`);
  process.exit(1);
}
