// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Lecture des jetons CSS et calcul de contraste, partagés par les contrôles de la console
// (contrast.mjs) et de l'espace utilisateur (user-workspace-tokens.mjs). Aucune dépendance : ces
// contrôles tournent sous Node nu, sans navigateur.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const assets = new URL('../../main/resources/static/assets/', import.meta.url);
export const read = (name) => readFileSync(new URL(name, assets), 'utf8');

/** Les `--nom: valeur` d'un bloc, y compris la dernière déclaration sans point-virgule final. */
export function declarations(body) {
  const found = {};
  for (const part of body.split(';')) {
    const match = part.match(/--([a-z0-9-]+):\s*([\s\S]+)$/);
    if (match) found[match[1]] = match[2].trim();
  }
  return found;
}

/** Corps du premier bloc `selector {`, accolades imbriquées comprises. */
export function block(css, selector) {
  const start = css.indexOf(selector);
  assert.ok(start >= 0, `bloc ${selector} introuvable`);
  let depth = 0;
  for (let i = css.indexOf('{', start); i < css.length; i += 1) {
    if (css[i] === '{') depth += 1;
    if (css[i] === '}' && (depth -= 1) === 0) return css.slice(css.indexOf('{', start) + 1, i);
  }
  throw new Error(`bloc ${selector} non refermé`);
}

/** Découpe sur les virgules de premier niveau : `rgb(1 2 3 / 4%), #fff` n'en fait que deux. */
export function splitTop(text) {
  const parts = [];
  let depth = 0;
  let current = '';
  for (const char of text) {
    if (char === '(') depth += 1;
    if (char === ')') depth -= 1;
    if (char === ',' && depth === 0) { parts.push(current.trim()); current = ''; } else current += char;
  }
  parts.push(current.trim());
  return parts;
}

/** Une valeur `light-dark(a, b)` rendue pour un thème ; toute autre valeur vaut pour les deux. */
export function forTheme(value, theme) {
  const match = value.match(/^light-dark\(([\s\S]*)\)$/);
  if (!match) return value;
  const [light, dark] = splitTop(match[1]);
  return theme === 'sombre' ? dark : light;
}

export function parse(value) {
  const hex = value.match(/^#([0-9a-f]{6})$/i);
  if (hex) return { rgb: [0, 2, 4].map((i) => parseInt(hex[1].slice(i, i + 2), 16)), alpha: 1 };
  const rgb = value.match(/^rgb\((\d+) (\d+) (\d+)(?: \/ ([\d.]+)%)?\)$/);
  if (rgb) return { rgb: rgb.slice(1, 4).map(Number), alpha: rgb[4] === undefined ? 1 : Number(rgb[4]) / 100 };
  throw new Error(`couleur illisible : ${value}`);
}

/** Une couleur translucide posée sur un fond opaque. */
export const over = (top, under) => top.rgb.map((c, i) => c * top.alpha + under[i] * (1 - top.alpha));

function luminance(rgb) {
  const [r, g, b] = rgb.map((c) => c / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function ratio(fg, bg) {
  const [a, b] = [luminance(fg), luminance(bg)].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}
