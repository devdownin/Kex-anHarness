// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
// Le dépôt n'a pas de package.json : l'URL data conserve le format module sous Node 18 aussi.
const source = await readFile(new URL('../../main/resources/static/assets/markdown-table.js', import.meta.url), 'utf8');
const { parseTable } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

// Le classement tel que l'agent le restitue : sans ce rendu, la console montrait les barres brutes.
assert.deepEqual(parseTable(['| Rang | Topic | Records |', '|------|-------|--------:|', '| 1 | demo.app.topic | 4000 |', '| 2 | demo.iot.sensors | 144 |']), {
  headers: ['Rang', 'Topic', 'Records'], align: [null, null, 'right'],
  rows: [['1', 'demo.app.topic', '4000'], ['2', 'demo.iot.sensors', '144']],
});
// Barres de bord facultatives, alignements, ligne courte complétée, longue tronquée, barre échappée.
assert.deepEqual(parseTable(['a | b', ':--- | :---:', 'x |', 'y | z | w', 'p\\|q | r']), {
  headers: ['a', 'b'], align: ['left', 'center'], rows: [['x', ''], ['y', 'z'], ['p|q', 'r']],
});
// Pas un tableau : séparateur absent, trop court, ou d'une autre largeur que l'en-tête.
assert.equal(parseTable(['| a | b |', '| x | y |']), null);
assert.equal(parseTable(['| a | b |', '| -- | -- |']), null);
assert.equal(parseTable(['| a | b |', '| --- |']), null);
assert.equal(parseTable(['a | b']), null);
assert.equal(parseTable(['Une phrase sans barre', '---']), null);
console.log('markdown-table : ok');
