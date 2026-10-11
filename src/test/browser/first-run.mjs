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

await browser.close();
console.log(checks.join('\n'));
if (problems.length) {
  console.error(`\n${problems.length} échec(s) :\n${problems.join('\n')}`);
  process.exit(1);
}
