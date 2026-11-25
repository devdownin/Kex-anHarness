// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
// Le dépôt n'a pas de package.json : l'URL data conserve le format module sous Node 18 aussi.
const source = await readFile(new URL('../../main/resources/static/assets/blind-review.js', import.meta.url), 'utf8');
const { blindPairs, preferredSide, tally, tallyLine, WITH, WITHOUT, EQUAL } =
  await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

const run = (answer, error = null) => ({ answer, error });
const probe = {
  references: [run('sans 1'), run('sans 2'), run(null, 'AgentTimeoutException'), run('sans 4')],
  candidates: [run('avec 1'), run('avec 2'), run('avec 3'), run('   ')],
};

// Les passes sans réponse d'un côté ne se comparent pas : il ne reste que les passes 1 et 2.
const sequence = [0.1, 0.9];
const pairs = blindPairs(probe, () => sequence.shift());
assert.deepEqual(pairs.map((pair) => pair.pass), [1, 2]);
// Tirage < 0,5 : la compétence passe en A ; sinon elle reste en B.
assert.deepEqual(pairs[0].a, { side: WITH, answer: 'avec 1' });
assert.deepEqual(pairs[0].b, { side: WITHOUT, answer: 'sans 1' });
assert.deepEqual(pairs[1].a, { side: WITHOUT, answer: 'sans 2' });
assert.deepEqual(pairs[1].b, { side: WITH, answer: 'avec 2' });

// Le tirage doit pouvoir placer la compétence des deux côtés, sinon A trahirait le côté.
const positions = new Set();
for (let i = 0; i < 200; i += 1) positions.add(blindPairs({ references: [run('s')], candidates: [run('a')] })[0].a.side);
assert.deepEqual([...positions].sort(), [WITH, WITHOUT].sort());

assert.equal(preferredSide(pairs[0], 'a'), WITH);
assert.equal(preferredSide(pairs[1], 'a'), WITHOUT);
assert.equal(preferredSide(pairs[1], 'equal'), EQUAL);

assert.equal(tally([]), null, 'sans choix, pas de « 0 sur 0 » qui se lirait comme un verdict');
assert.equal(tallyLine(null), '');
assert.deepEqual(tally([WITH, WITHOUT, WITH, EQUAL]), { with: 2, without: 1, equal: 1, total: 4 });
assert.equal(tallyLine(tally([WITHOUT])),
  'Sur 1 comparaison(s) : avec la compétence préférée 0 fois, sans 1 fois, équivalentes 0 fois.');

assert.deepEqual(blindPairs(undefined), []);
assert.deepEqual(blindPairs({ references: [run('s')], candidates: [] }), []);
console.log('✓ comparaison à l’aveugle : paires, tirage, côté préféré et décompte');
