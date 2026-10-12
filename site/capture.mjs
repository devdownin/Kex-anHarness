// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
//
// Les captures de la page de présentation (site/img/), prises sur la Control Center réelle.
// La CI les régénère dans le job explorer-contract, où l'agent est branché sur un vrai Kafka SQL
// Explorer, et les publie en artefact : une capture qui vieillit se voit alors dans la PR qui l'a
// fait vieillir. Les recopier dans site/img/ reste un geste de revue, comme pour l'Explorer.
//
//   PLAYWRIGHT_MODULE=/chemin/playwright/index.mjs node site/capture.mjs http://localhost:8081 site/img
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
const [base = 'http://localhost:8081', out = 'site/img'] = process.argv.slice(2);
const apiKey = process.env.KEX_AGENT_API_KEY ?? 'ci-secret';

const browser = await chromium.launch();
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'dark' });
  const page = await context.newPage();
  await page.addInitScript((key) => sessionStorage.setItem('kex.agent.api-key', key), apiKey);
  for (const view of ['overview', 'integrations']) {
    await page.goto(`${base}/#/${view}`);
    await page.locator('#credentials').evaluate((dialog) => dialog.open && dialog.close());
    await page.waitForTimeout(2500);
    await page.screenshot({ path: `${out}/${view}.png` });
    console.log(`${out}/${view}.png`);
  }
} finally {
  await browser.close();
}
