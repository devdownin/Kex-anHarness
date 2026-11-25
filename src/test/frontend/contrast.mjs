// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { block, declarations, over, parse, ratio, read } from './css-tokens.mjs';

// Le contraste se vérifie sur les jetons eux-mêmes, pas sur une capture : un texte secondaire passé
// sous 4,5:1 se lit encore très bien sur l'écran de celui qui le règle, et nulle part ailleurs.
// Les matériaux translucides sont composés sur le fond le plus défavorable qu'ils puissent couvrir.

const css = read('console.css');

const themes = {
  clair: declarations(block(css, ':root {')),
  sombre: declarations(block(css, ':root[data-theme="dark"] {')),
};

for (const [name, t] of Object.entries(themes)) {
  const solid = (key) => parse(t[key]).rgb;
  // Les deux extrémités du dégradé de fond : le verre se compose sur l'une ou l'autre.
  const backgrounds = { bg: solid('bg'), 'bg-deep': solid('bg-deep') };
  const surfaces = {
    surface: solid('surface'),
    ...Object.fromEntries(['material-base', 'glass-thin-bg', 'glass-regular-bg', 'glass-thick-bg', 'glass-popover-bg']
      .flatMap((key) => Object.entries(backgrounds).map(([under, rgb]) => [`${key} sur ${under}`, over(parse(t[key]), rgb)]))),
  };
  for (const ink of ['ink', 'ink-2', 'ink-3']) {
    for (const [surface, rgb] of Object.entries(surfaces)) {
      const value = ratio(solid(ink), rgb);
      assert.ok(value >= 4.5, `${name} : --${ink} sur ${surface} = ${value.toFixed(2)}:1, sous le seuil AA de 4,5:1`);
    }
  }
  // L'accent sert de texte (liens, rubriques) : même seuil. Sur lui, l'encre d'accent des boutons.
  // Sur le fond aussi, bas du dégradé compris : un lien d'accent posé hors d'un panneau tombe dessus.
  for (const ground of ['surface', 'bg', 'bg-deep']) {
    const measured = ratio(solid('accent'), solid(ground));
    assert.ok(measured >= 4.5, `${name} : --accent sur --${ground} = ${measured.toFixed(2)}:1, sous le seuil AA de 4,5:1`);
  }
  assert.ok(ratio(solid('accent-ink'), solid('accent')) >= 4.5, `${name} : --accent-ink sur --accent sous AA`);
}

// Le thème sombre suivant les préférences du système doit reprendre exactement les mêmes valeurs
// que le thème sombre choisi à la main : deux copies qui divergent, et un seul des deux est vérifié.
const auto = declarations(block(block(css, '@media (prefers-color-scheme: dark)'), ':root:not([data-theme="light"])'));
assert.deepEqual(auto, themes.sombre, 'le sombre du système et le sombre choisi divergent');

console.log('✓ contraste des jetons : encres et accent au-dessus de 4,5:1 sur chaque matériau, en clair et en sombre');
