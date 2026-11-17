// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile('src/main/resources/static/assets/overview-summary.js', 'utf8');
const { heroSummary, heroCycle } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const stamp = () => '17/11 03:06';
const at = '2026-11-17T03:06:48Z';
const base = { processesMonitored: 1, processesOk: 0, processesWarning: 0, processesError: 0, processesUnknown: 1, agent: { lastCycleAt: at } };

// Le défaut vu à l'écran : cycle en échec, point vert et « aucune intervention requise ».
const failed = { ...base, lastCycle: { failure: '401 du fournisseur' } };
assert.doesNotMatch(heroSummary(failed), /aucune intervention requise/);
assert.match(heroSummary(failed), /échec/);
assert.equal(heroCycle(failed, stamp).tone, 'error');

// Sans échec, un processus inconnu n'est pas un processus sain.
assert.equal(heroSummary(base), '0 processus opérationnel sur 1 · 1 dans un état inconnu');
assert.equal(heroCycle(base, stamp).tone, 'warning');

const healthy = { ...base, processesOk: 1, processesUnknown: 0 };
assert.equal(heroSummary(healthy), '1 processus opérationnel sur 1 · aucune intervention requise');
assert.equal(heroCycle(healthy, stamp).tone, 'ok');

assert.match(heroSummary({ ...base, agent: {} }), /pas encore analysé/);
assert.equal(heroCycle({ ...base, agent: {} }, stamp).tone, 'idle');
assert.equal(heroCycle({ ...base, agent: { analysing: true, lastCycleAt: at } }, stamp).tone, 'running');
assert.match(heroSummary({ ...base, processesUnknown: 0, processesError: 2, processesMonitored: 2 }), /2 nécessitent votre attention/);
console.log('✓ synthèse : un échec ou un inconnu ne se lit jamais « aucune intervention requise »');
