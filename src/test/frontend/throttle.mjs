// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
// Un `.mjs` s'importe tel quel ; seuls les fichiers `.js` de la console passent par une URL data,
// faute de package.json qui les déclarerait modules.
import { throttle, throttleFactor } from '../browser/throttle.mjs';

const sent = [];
const context = { newCDPSession: async () => ({ send: async (method, params) => sent.push([method, params]) }) };

assert.equal(await throttle(context, {}, undefined), 1);
assert.equal(await throttle(context, {}, ''), 1);
assert.equal(await throttle(context, {}, '1'), 1);
assert.equal(sent.length, 0, 'aucun appel au navigateur sans ralentissement');

assert.equal(await throttle(context, {}, '8'), 8);
assert.deepEqual(sent, [['Emulation.setCPUThrottlingRate', { rate: 8 }]]);

// Une valeur illisible ne vaut pas « pas de ralentissement » : elle ferait passer pour un test sous
// charge un test qui n'en est pas un.
for (const bad of ['abc', '0', '0.5', '-3', 'NaN', 'Infinity']) {
  await assert.rejects(() => throttle(context, {}, bad), /KEX_CPU_THROTTLE doit être/, bad);
}
// Validable seule, sans navigateur : c'est ce qui permet à un script d'échouer dès son démarrage.
assert.equal(throttleFactor('4'), 4);
assert.equal(throttleFactor(undefined), 1);
assert.throws(() => throttleFactor('abc'), /KEX_CPU_THROTTLE doit être/);
console.log('✓ ralentissement du processeur : valeurs neutres, facteur appliqué, valeurs invalides refusées');
