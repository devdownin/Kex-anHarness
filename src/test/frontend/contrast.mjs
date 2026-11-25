// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Le contraste se vérifie sur les jetons eux-mêmes, pas sur une capture : un texte secondaire passé
// sous 4,5:1 se lit encore très bien sur l'écran de celui qui le règle, et nulle part ailleurs.
// Les matériaux translucides sont composés sur le fond le plus défavorable qu'ils puissent couvrir.

const css = readFileSync(new URL('../../main/resources/static/assets/console.css', import.meta.url), 'utf8');

function block(selector) {
  const start = css.indexOf(`${selector} {`);
  assert.ok(start >= 0, `bloc ${selector} introuvable`);
  const body = css.slice(start, css.indexOf('\n}', start));
  return Object.fromEntries([...body.matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)].map((m) => [m[1], m[2].trim()]));
}

function parse(value) {
  const hex = value.match(/^#([0-9a-f]{6})$/i);
  if (hex) return { rgb: [0, 2, 4].map((i) => parseInt(hex[1].slice(i, i + 2), 16)), alpha: 1 };
  const rgb = value.match(/^rgb\((\d+) (\d+) (\d+)(?: \/ ([\d.]+)%)?\)$/);
  if (rgb) return { rgb: rgb.slice(1, 4).map(Number), alpha: rgb[4] === undefined ? 1 : Number(rgb[4]) / 100 };
  throw new Error(`couleur illisible : ${value}`);
}

const over = (top, under) => top.rgb.map((c, i) => c * top.alpha + under[i] * (1 - top.alpha));

function luminance(rgb) {
  const [r, g, b] = rgb.map((c) => c / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function ratio(fg, bg) {
  const [a, b] = [luminance(fg), luminance(bg)].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}

const themes = {
  clair: block(':root'),
  sombre: block(':root[data-theme="dark"]'),
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
  assert.ok(ratio(solid('accent'), solid('surface')) >= 4.5, `${name} : --accent sur --surface sous AA`);
  assert.ok(ratio(solid('accent-ink'), solid('accent')) >= 4.5, `${name} : --accent-ink sur --accent sous AA`);
}

// Le thème sombre suivant les préférences du système doit reprendre exactement les mêmes valeurs
// que le thème sombre choisi à la main : deux copies qui divergent, et un seul des deux est vérifié.
const media = css.slice(css.indexOf('@media (prefers-color-scheme: dark)'));
const auto = Object.fromEntries([...media.slice(0, media.indexOf('\n}')).matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)]
  .map((m) => [m[1], m[2].trim()]));
assert.deepEqual(auto, themes.sombre, 'le sombre du système et le sombre choisi divergent');

console.log('✓ contraste des jetons : encres et accent au-dessus de 4,5:1 sur chaque matériau, en clair et en sombre');
