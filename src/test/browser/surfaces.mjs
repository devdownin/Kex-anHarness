// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Le système de surfaces, vérifié là où il casse sans bruit : en sombre, sur téléphone, avec le
// mouvement réduit, sans verre. Une comparaison de pixels dériverait au moindre anti-crénelage ;
// ce sont les invariants qui se testent ici — qui est flouté, qui ne l'est pas, ce qui reste
// lisible — et les captures partent en artefact pour l'œil, pas pour une assertion.
//
//   PLAYWRIGHT_MODULE=… KEX_SCREENSHOT_DIR=/tmp/captures node src/test/browser/surfaces.mjs
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const BASE = process.env.KEX_AGENT_URL ?? 'http://localhost:8081';
const TOKEN = process.env.KEX_AGENT_API_KEY ?? 'ci-secret';
const SHOTS = process.env.KEX_SCREENSHOT_DIR;
if (SHOTS) await mkdir(SHOTS, { recursive: true });

const desktop = { width: 1440, height: 900 };
const wide = { width: 1600, height: 1000 };
const phone = { width: 390, height: 844 };

const browser = await chromium.launch();
const failures = [];

async function open(viewport, view, options = {}) {
  const context = await browser.newContext({ viewport, ...options });
  const page = await context.newPage();
  page.setDefaultTimeout(10000);
  await page.addInitScript((token) => sessionStorage.setItem('kex.agent.api-key', token), TOKEN);
  await page.goto(`${BASE}/#/${view}`, { waitUntil: 'domcontentloaded' });
  await page.locator('#credentials').evaluate((dialog) => { if (dialog.open) dialog.close(); });
  await page.waitForSelector(`#view-${view}:not([hidden])`);
  return { context, page };
}

async function check(name, run) {
  try {
    await run();
    console.log(`  ✓ ${name}`);
  } catch (error) {
    failures.push(name);
    console.log(`  ✗ ${name}\n      ${error.message.split('\n')[0]}`);
  }
}

const style = (locator, property) => locator.evaluate((node, key) => getComputedStyle(node)[key], property);
const alpha = (color) => {
  const match = color.match(/rgba?\(([^)]+)\)/);
  if (!match) return 1;
  const parts = match[1].split(/[\s,/]+/).filter(Boolean);
  return parts.length === 4 ? Number(parts[3]) : 1;
};

try {
  for (const colorScheme of ['light', 'dark']) {
    await check(`${colorScheme} : rail et barre du haut en verre, panneaux de lecture sans flou`, async () => {
      const { context, page } = await open(desktop, 'overview', { colorScheme });
      for (const selector of ['.rail', '.topbar']) {
        assert.match(await style(page.locator(selector), 'backdropFilter'), /blur\(/, `${selector} sans flou`);
      }
      // Des dizaines de panneaux floutés coûteraient cher au GPU pour un gain nul : on y lit.
      const blurred = await page.$$eval('.panel', (nodes) => nodes.filter((node) =>
        getComputedStyle(node).backdropFilter.includes('blur')).length);
      assert.equal(blurred, 0, `${blurred} panneau(x) de lecture flouté(s)`);
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/${colorScheme}-desktop-overview.png` });
      await context.close();
    });

    await check(`${colorScheme} : un menu ouvert depuis la barre se lit par son opacité`, async () => {
      // Le flou d'un menu posé dans la barre (déjà floutée) ne voit que la barre : sans opacité
      // suffisante, le contenu de la page se lirait net à travers lui.
      const { context, page } = await open(desktop, 'overview', { colorScheme });
      await page.locator('.action-menu > summary').first().click();
      const popover = page.locator('.action-menu-popover').first();
      await popover.waitFor();
      const opacity = alpha(await style(popover, 'backgroundColor'));
      assert.ok(opacity >= 0.9, `menu à ${opacity} d'opacité`);
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/${colorScheme}-desktop-menu.png` });
      await context.close();
    });

    await check(`${colorScheme} : téléphone sans défilement horizontal, navigation flottante en bas`, async () => {
      const { context, page } = await open(phone, 'overview', { colorScheme, isMobile: true, hasTouch: true });
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth);
      assert.ok(overflow <= 0, `la page déborde de ${overflow} px`);
      const rail = await page.locator('.rail').boundingBox();
      assert.ok(rail.x > 0 && rail.x + rail.width < phone.width, 'la navigation touche les bords de l’écran');
      assert.ok(parseFloat(await style(page.locator('.rail'), 'borderTopLeftRadius')) >= 16, 'navigation sans arrondi');
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/${colorScheme}-phone-overview.png` });
      await context.close();
    });
  }

  await check('grand écran : la synthèse et les indicateurs se font face, sur la même rangée', async () => {
    const { context, page } = await open(wide, 'overview');
    await page.locator('#kpis .kpi').first().waitFor();
    const [hero, kpis] = await Promise.all([page.locator('.overview-hero').boundingBox(), page.locator('#kpis').boundingBox()]);
    assert.ok(Math.abs(hero.y - kpis.y) < 2, `synthèse à ${hero.y}, indicateurs à ${kpis.y}`);
    assert.ok(hero.x + hero.width <= kpis.x, 'synthèse et indicateurs se chevauchent');
    // L'ordre choisi dans « Personnaliser » reste celui de lecture : la synthèse précède toujours ses indicateurs.
    assert.ok(hero.x < kpis.x, 'les indicateurs passent devant la synthèse');
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/light-wide-overview.png` });
    await context.close();
  });

  await check('écran moyen : les blocs gardent une pleine ligne', async () => {
    const { context, page } = await open({ width: 1280, height: 900 }, 'overview');
    await page.locator('#kpis .kpi').first().waitFor();
    const [hero, kpis] = await Promise.all([page.locator('.overview-hero').boundingBox(), page.locator('#kpis').boundingBox()]);
    assert.ok(kpis.y >= hero.y + hero.height, 'grille à colonnes sur un écran trop étroit pour elle');
    await context.close();
  });

  await check('mouvement réduit : ni transition de survol, ni animation d’entrée', async () => {
    const { context, page } = await open(desktop, 'overview', { reducedMotion: 'reduce' });
    const duration = parseFloat(await style(page.locator('#open-notifications'), 'transitionDuration'));
    assert.ok(duration < 0.001, `transition de ${duration} s`);
    await page.evaluate(() => document.querySelector('#credentials').showModal());
    const animation = parseFloat(await style(page.locator('#credentials'), 'animationDuration'));
    assert.ok(animation < 0.001, `dialogue animé sur ${animation} s`);
    await context.close();
  });

  await check('contraste renforcé : les matériaux redeviennent opaques', async () => {
    const { context, page } = await open(desktop, 'overview', { contrast: 'more' });
    assert.equal(alpha(await style(page.locator('.rail'), 'backgroundColor')), 1, 'rail encore translucide');
    await context.close();
  });

  await check('dialogue : plan le plus proche, voile léger', async () => {
    const { context, page } = await open(desktop, 'overview', { colorScheme: 'dark' });
    await page.evaluate(() => document.querySelector('#credentials').showModal());
    const dialog = page.locator('#credentials');
    assert.ok(parseFloat(await style(dialog, 'borderTopLeftRadius')) >= 24, 'dialogue peu arrondi');
    assert.match(await style(dialog, 'backdropFilter'), /blur\(/);
    // Le voile assombrit sans effacer : l'écran recule, il ne disparaît pas.
    const scrim = await dialog.evaluate((node) => getComputedStyle(node, '::backdrop').backgroundColor);
    assert.ok(alpha(scrim) <= 0.5, `voile à ${alpha(scrim)} d'opacité`);
    await page.waitForTimeout(300);
    if (SHOTS) await page.screenshot({ path: `${SHOTS}/dark-desktop-dialog.png` });
    await context.close();
  });

  await check('espace utilisateur : même fond et même en-tête flottant, en clair et en sombre', async () => {
    for (const colorScheme of ['light', 'dark']) {
      const context = await browser.newContext({ viewport: desktop, colorScheme });
      const page = await context.newPage();
      await page.addInitScript((token) => sessionStorage.setItem('kex.agent.api-key', token), TOKEN);
      await page.goto(`${BASE}/app`, { waitUntil: 'domcontentloaded' });
      const header = page.locator('.app-header');
      assert.match(await style(header, 'backdropFilter'), /blur\(/, `${colorScheme} : en-tête sans verre`);
      assert.ok((await header.boundingBox()).x > 0, `${colorScheme} : en-tête collé au bord`);
      if (SHOTS) await page.screenshot({ path: `${SHOTS}/${colorScheme}-desktop-app.png` });
      await context.close();
    }
  });
} finally {
  await browser.close();
}

if (failures.length) {
  console.log(`\n${failures.length} échec(s) :`);
  failures.forEach((name) => console.log(`  ✗ ${name}`));
  process.exit(1);
}
console.log('\nSystème de surfaces vérifié.');
