// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE);

// L'import des compétences de l'Explorer passe par le serveur : la console ne poste plus de texte
// « vérifié » par elle-même, elle demande l'import et affiche le constat de chaque compétence.
const assets = new URL('../../main/resources/static/assets/', import.meta.url);
const html = '<div id="servers"></div><div id="mcp-storage-status"></div><div id="toasts"></div><section id="drawer" hidden><h2 id="drawer-title"></h2><div id="drawer-body"></div><button id="drawer-close">Fermer</button></section>';
const server = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://localhost').pathname;
    response.setHeader('Content-Type', path.endsWith('.js') ? 'text/javascript' : 'text/html');
    response.end(path.startsWith('/assets/') ? await readFile(new URL(path.slice(8), assets)) : html);
  } catch { response.writeHead(404).end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ headless: true });
const manifest = (name) => ({ uri: `skill://${name}/SKILL.md`, frontmatter: { name, description: `Description de ${name}` },
  resources: [{ uri: `skill://${name}/SKILL.md`, digest: 'sha256:00', size: 10 }] });
try {
  const page = await browser.newPage();
  page.on('pageerror', error => console.error('pageerror', error.message));
  const imports = [];
  let legacyPosts = 0;
  await page.route('**/api/agent/**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    let body = [];
    if (path.endsWith('/mcp/servers')) body = [{ connection: 'kafka-explorer', serverName: 'Kafka SQL Explorer',
      initialized: true, tools: [{ name: 'kex_list_topics', description: 'Topics' }] }];
    if (path.endsWith('/mcp/storage')) body = null;
    if (path.endsWith('/kafka-explorer/resources')) body = [{ uri: 'kex://skills', name: 'Catalogue' }];
    if (path.endsWith('/kafka-explorer/resource')) body = [{ uri: 'kex://skills', text: JSON.stringify([
      manifest('kafka-incident-triage'), manifest('kafkaexplorer-release')]) }];
    if (path === '/api/agent/skills' && route.request().method() === 'POST') legacyPosts++;
    if (path === '/api/agent/skills/import/kafka-explorer') {
      const names = url.searchParams.getAll('name');
      imports.push(names);
      const all = [
        { name: 'kafka-incident-triage', status: 'PROPOSED', detail: 'En revue', skillId: 's-1' },
        { name: 'kafkaexplorer-release', status: 'SKIPPED', detail: 'Compétence d’ingénierie de l’éditeur' },
      ];
      body = { connection: 'kafka-explorer', outcomes: names.length ? all.filter(o => names.includes(o.name)) : all };
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  await page.evaluate(async () => { const tools = await import('/assets/tools.js'); await tools.servers(); });
  const skills = page.locator('.mcp-kafka-skills');
  await skills.getByRole('button', { name: 'Découvrir les compétences' }).click();
  await skills.locator('[data-skill-name="kafka-incident-triage"]').waitFor();

  const engineering = skills.locator('[data-skill-name="kafkaexplorer-release"]');
  assert.equal(await engineering.getByRole('button', { name: 'Proposer à Kex' }).isDisabled(), true,
    'une compétence d’ingénierie de l’éditeur ne se propose pas');

  const triage = skills.locator('[data-skill-name="kafka-incident-triage"]');
  await triage.getByRole('button', { name: 'Proposer à Kex' }).click();
  await triage.getByText(/Proposée : en attente de revue/).waitFor();
  assert.deepEqual(imports.at(-1), ['kafka-incident-triage']);
  // Le verrou se pose après la fin de busy(), qui rend le bouton : on l'attend, on ne le suppose pas.
  await page.waitForFunction(() => [...document.querySelectorAll('[data-skill-name="kafka-incident-triage"] button')]
    .find(button => button.textContent === 'Proposer à Kex')?.disabled === true);
  console.log('✓ une compétence se propose par l’import serveur, nommée, et son constat s’affiche');

  await skills.getByRole('button', { name: 'Proposer toutes les compétences Kafka' }).click();
  await skills.getByText(/1 proposée\(s\) sur 2/).waitFor();
  assert.deepEqual(imports.at(-1), []);
  assert.match(await engineering.textContent(), /Écartée · Compétence d’ingénierie/);
  assert.equal(legacyPosts, 0, 'plus aucun texte n’est posté directement par la console');
  console.log('✓ l’import groupé affiche le constat de chaque compétence, sans POST direct du texte');
} finally {
  await browser.close();
  await new Promise(resolve => server.close(resolve));
}
