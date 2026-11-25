// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { block, declarations, forTheme, over, parse, ratio, read } from './css-tokens.mjs';

// L'espace utilisateur (/app) ne charge pas la feuille de la console : il en recopie les valeurs de
// surface, dans sa propre syntaxe (`light-dark()` là où la console bascule par `data-theme`). Une
// copie que rien ne compare dérive : cinq valeurs avaient déjà divergé (`surface`, `ink`, `soft`,
// `line`, le verre épais) quand ce contrôle a été écrit. Il ne partage pas les valeurs — la console
// et l'espace utilisateur changent de thème par deux mécanismes —, il refuse qu'elles s'écartent, et
// vérifie le contraste de ce que seul l'espace utilisateur définit.

const consoleCss = read('console.css');
const appCss = read('user-workspace.css');

// Le bloc sombre ne redéclare pas ce qui ne change pas d'un thème à l'autre (courbes, durées) : ce
// jeton garde alors sa valeur du bloc clair, comme dans la cascade.
const consoleThemes = {
  clair: declarations(block(consoleCss, ':root {')),
  sombre: declarations(block(consoleCss, ':root[data-theme="dark"] {')),
};

// Les jetons de l'espace utilisateur sont déclarés dans ses deux premiers « :root{…} », le second
// redéfinissant ce que le premier posait (la cascade : le dernier l'emporte) ; ceux qui
// suivent (repli sans verre, contraste renforcé) les remplacent exprès et ne sont pas comparés.
const appTokens = {};
for (const match of appCss.matchAll(/:root\{([^}]*)\}/g)) {
  for (const [name, value] of Object.entries(declarations(match[1]))) appTokens[name] = value;
  if (appTokens['halo-1'] && appTokens['field-bg']) break;
}

// jeton de la console → jeton de l'espace utilisateur
const SHARED = {
  bg: 'bg', 'bg-deep': 'bg-deep', surface: 'surface', 'surface-2': 'soft', line: 'line',
  ink: 'ink', 'ink-2': 'muted', accent: 'accent', 'accent-ink': 'on-accent', danger: 'error',
  'halo-1': 'halo-1', 'halo-2': 'halo-2', 'material-base': 'material-base',
  'glass-regular-bg': 'glass-regular-bg', 'glass-thick-bg': 'glass-thick-bg',
  'glass-border': 'glass-border', hairline: 'hairline', 'control-bg': 'control-bg',
  'control-bg-hover': 'control-bg-hover', 'field-bg': 'field-bg', 'nav-active-bg': 'nav-active-bg',
  scrim: 'scrim', 'ease-spatial': 'ease-spatial',
};

const tidy = (value) => value.replace(/\s+/g, ' ').replace(/\s*,\s*/g, ',').trim().toLowerCase();

for (const [consoleName, appName] of Object.entries(SHARED)) {
  assert.ok(appName in appTokens, `l'espace utilisateur ne déclare pas --${appName} (console : --${consoleName})`);
  for (const theme of ['clair', 'sombre']) {
    assert.equal(tidy(forTheme(appTokens[appName], theme)), tidy(consoleThemes[theme][consoleName] ?? consoleThemes.clair[consoleName]),
      `--${appName} (${theme}) a dérivé de --${consoleName} de la console`);
  }
}

// Ce que seul l'espace utilisateur définit : les couleurs de ses résultats, posées en `light-dark()`
// directement dans les règles. Chacune doit se lire sur les surfaces où ce texte peut tomber.
const semantic = [...appCss.matchAll(/color:light-dark\((#[0-9a-f]{6}),(#[0-9a-f]{6})\)/gi)]
  .map((match) => ({ clair: match[1], sombre: match[2] }));
assert.ok(semantic.length > 0, 'aucune couleur de résultat trouvée : le motif ne lit plus la feuille');

for (const theme of ['clair', 'sombre']) {
  const tokens = Object.fromEntries(Object.entries(appTokens).map(([name, value]) => [name, forTheme(value, theme)]));
  const grounds = {
    '--bg': parse(tokens.bg).rgb, '--bg-deep': parse(tokens['bg-deep']).rgb,
    '--surface': parse(tokens.surface).rgb, '--soft': parse(tokens.soft).rgb,
    '--material-base sur --bg': over(parse(tokens['material-base']), parse(tokens.bg).rgb),
    '--material-base sur --bg-deep': over(parse(tokens['material-base']), parse(tokens['bg-deep']).rgb),
  };
  const texts = {
    '--ink': tokens.ink, '--muted': tokens.muted, '--accent': tokens.accent, '--error': tokens.error,
    ...Object.fromEntries(semantic.map((color, index) => [`couleur de résultat n°${index + 1} (${color[theme]})`, color[theme]])),
  };
  for (const [label, value] of Object.entries(texts)) {
    for (const [ground, rgb] of Object.entries(grounds)) {
      const measured = ratio(parse(value).rgb, rgb);
      assert.ok(measured >= 4.5, `${theme} : ${label} sur ${ground} = ${measured.toFixed(2)}:1, sous le seuil AA de 4,5:1`);
    }
  }
}


console.log('✓ espace utilisateur : valeurs alignées sur la console, contraste AA de ses textes');
