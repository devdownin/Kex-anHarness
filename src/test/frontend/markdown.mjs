// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
// Le dépôt n'a pas de package.json : l'URL data garde le format module sous Node 18 aussi. markdown.js
// importe markdown-table.js, que le test charge d'abord et dont il retire la dépendance.
const dir = new URL('../../main/resources/static/assets/', import.meta.url);
const table = await readFile(new URL('markdown-table.js', dir), 'utf8');
const source = (await readFile(new URL('markdown.js', dir), 'utf8'))
  .replace("import { parseTable } from './markdown-table.js';", table.replace('export function parseTable', 'function parseTable'));
const { parseBlocks, parseInline, safeHref } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

const types = (text) => parseBlocks(text).map((b) => b.type);
const plain = (nodes) => nodes.map((n) => (n.v ?? plain(n.c ?? []))).join('');

// Liens : seuls http(s) et mailto sont cliquables ; le reste reste du texte, jamais un lien.
assert.equal(safeHref('https://exemple.fr/a?b=1#c'), 'https://exemple.fr/a?b=1#c');
assert.equal(safeHref('mailto:a@b.fr'), 'mailto:a@b.fr');
for (const bad of ['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'data:text/html,x', '//evil.example', '/chemin', 'https://a b', 'https://a\u0000b', '', null]) {
  assert.equal(safeHref(bad), null, String(bad));
}
assert.deepEqual(parseInline('[x](javascript:alert(1))'), [{ t: 'text', v: '[x](javascript:alert(1))' }]);
assert.equal(parseInline('[doc](https://e.fr/a)')[0].href, 'https://e.fr/a');

// Emphase : appariée, jamais ouverte au milieu d'un mot, jamais jusqu'à la fin d'une réponse tronquée.
assert.deepEqual(parseInline('**gras** et *it* et ~~bar~~ et `code`').map((n) => n.t), ['strong', 'text', 'em', 'text', 'del', 'text', 'code']);
assert.equal(parseInline('snake__case_et_plus').length, 1, 'les soulignés d’un identifiant n’ouvrent rien');
assert.deepEqual(parseInline('**inachevé'), [{ t: 'text', v: '**inachevé' }], 'un marqueur sans compagnon reste du texte (flux en cours)');
assert.deepEqual(parseInline('a * b * c'), [{ t: 'text', v: 'a * b * c' }], 'des étoiles entourées d’espaces ne sont pas une emphase');
assert.equal(plain(parseInline('\\*littéral\\* et \\`x\\`')), '*littéral* et `x`');
assert.deepEqual(parseInline('`a *b* c`'), [{ t: 'code', v: 'a *b* c' }], 'le code ne s’interprète pas');
assert.equal(plain(parseInline('<script>alert(1)</script> **x**')), '<script>alert(1)</script> x', 'le HTML reste du texte');

// Blocs.
assert.deepEqual(types('# T\n\ntexte\n\n- a\n- b\n\n1. x\n2. y\n\n> cite\n\n---\n\n| a | b |\n|:--|--:|\n| 1 | 2 |'),
  ['heading', 'paragraph', 'list', 'list', 'quote', 'rule', 'table']);
assert.deepEqual(parseBlocks('texte\nsuite').map((b) => b.lines.length), [2], 'les retours à la ligne d’un paragraphe sont gardés');
const tree = parseBlocks('- a\n  - b\n    - c\n- d')[0];
assert.deepEqual(tree.items.map((i) => i.children.length), [1, 0]);
assert.equal(tree.items[0].children[0].items[0].children[0].items[0].inline[0].v, 'c');
const mixed = parseBlocks('- a\n1. x\n2. y');
assert.deepEqual(mixed.map((b) => b.ordered), [false, true], 'un autre genre de marqueur ouvre une autre liste');
assert.equal(parseBlocks('3. trois\n4. quatre')[0].start, 3);
assert.deepEqual(types('* * *\n\n* item'), ['rule', 'list']);
assert.deepEqual(types('Tarif : 3 - 2 = 1'), ['paragraph'], 'un tiret dans une phrase n’est pas une liste');

// Code : fermé, ou ouvert quand la réponse arrive encore.
const code = parseBlocks('```js\nconst x = 1;\n```\napres');
assert.deepEqual([code[0].type, code[0].lang, code[0].text, code[0].open], ['code', 'js', 'const x = 1;', false]);
assert.equal(code[1].type, 'paragraph');
const open = parseBlocks('```py\nprint(1)')[0];
assert.deepEqual([open.lang, open.text, open.open], ['py', 'print(1)', true]);
assert.equal(parseBlocks('````\n```\ninterne\n```\n````')[0].text, '```\ninterne\n```', 'une clôture plus longue enveloppe une plus courte');
assert.equal(types('```\n# pas un titre\n- ni une liste\n```').join(), 'code');

// Un tableau tronqué en plein flux ne casse rien : tant que la ligne de séparation manque, c'est du texte.
assert.deepEqual(types('| a | b |'), ['paragraph']);
assert.deepEqual(types('| a | b |\n|---|'), ['paragraph']);
assert.deepEqual(types('| a | b |\n|---|---|'), ['table']);

// Entrée hostile ou vide : jamais d'exception, jamais de HTML.
for (const hostile of ['', null, undefined, '\u0000', '[', '](', '`', '```', '>', '#', '|', '- ', '1.', '**', '_'.repeat(500), '[a](b)'.repeat(300)]) parseBlocks(hostile);
console.log('✓ markdown : liens sûrs, emphase appariée, listes imbriquées, citations, code ouvert ou fermé, tableaux, entrées hostiles');
