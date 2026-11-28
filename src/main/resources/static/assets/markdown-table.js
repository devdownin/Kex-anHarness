// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Tableaux Markdown (GFM) des réponses de l'agent. Séparé de chat.js, qui dépend du DOM, pour être
// testé sous Node : le modèle restitue volontiers un classement en tableau, et sans ce rendu la
// console affichait les barres verticales en texte brut.

// Une cellule de séparation est des tirets, avec un deux-points facultatif de chaque côté : GFM n'en
// exige pas trois, et `|:--|--:|` est ce que les modèles écrivent le plus. La largeur égale à celle de
// l'en-tête reste le garde-fou contre une ligne de tirets qui n'est pas un tableau.
const SEPARATOR_CELL = /^:?-+:?$/;

function cells(line) {
  let row = line.trim();
  if (row.startsWith('|')) row = row.slice(1);
  if (row.endsWith('|') && !row.endsWith('\\|')) row = row.slice(0, -1);
  // Une barre échappée ou dans un extrait de code (`a|b`) appartient à la cellule, pas à la grille.
  const out = [];
  let current = '';
  let i = 0;
  while (i < row.length) {
    const ch = row[i];
    if (ch === '\\' && row[i + 1] === '|') {
      current += '|';
      i += 2;
      continue;
    }
    if (ch === '`') {
      let run = 1;
      while (row[i + run] === '`') run += 1;
      const marker = '`'.repeat(run);
      const end = row.indexOf(marker, i + run);
      if (end > i + run) {
        current += row.slice(i, end + run);
        i = end + run;
        continue;
      }
      current += marker;
      i += run;
      continue;
    }
    if (ch === '|') {
      out.push(current.trim());
      current = '';
      i += 1;
      continue;
    }
    current += ch;
    i += 1;
  }
  out.push(current.trim());
  return out;
}

/**
 * Lit un bloc de lignes comme un tableau, ou rend {@code null} : en-tête, ligne de séparation dont
 * chaque cellule est des tirets (deux-points facultatifs), puis les lignes de données. Une ligne de données plus
 * courte que l'en-tête est complétée, une plus longue tronquée — comme GFM.
 */
export function parseTable(lines) {
  if (lines.length < 2 || !lines[0].includes('|') || !lines[1].includes('|')) return null;
  const headers = cells(lines[0]);
  const separator = cells(lines[1]);
  if (separator.length !== headers.length || !separator.every((cell) => SEPARATOR_CELL.test(cell))) return null;
  const align = separator.map((cell) => {
    if (cell.startsWith(':') && cell.endsWith(':')) return 'center';
    if (cell.endsWith(':')) return 'right';
    if (cell.startsWith(':')) return 'left';
    return null;
  });
  const rows = lines.slice(2).map((line) => {
    const row = cells(line).slice(0, headers.length);
    while (row.length < headers.length) row.push('');
    return row;
  });
  return { headers, align, rows };
}
