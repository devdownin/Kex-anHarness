// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Tableaux Markdown (GFM) des réponses de l'agent. Séparé de chat.js, qui dépend du DOM, pour être
// testé sous Node : le modèle restitue volontiers un classement en tableau, et sans ce rendu la
// console affichait les barres verticales en texte brut.

const SEPARATOR_CELL = /^:?-{3,}:?$/;

function cells(line) {
  let row = line.trim();
  if (row.startsWith('|')) row = row.slice(1);
  if (row.endsWith('|') && !row.endsWith('\\|')) row = row.slice(0, -1);
  // Une barre échappée appartient au contenu de la cellule, pas à la grille.
  return row.split(/(?<!\\)\|/).map((cell) => cell.trim().replace(/\\\|/g, '|'));
}

/**
 * Lit un bloc de lignes comme un tableau, ou rend {@code null} : en-tête, ligne de séparation dont
 * chaque cellule compte au moins trois tirets, puis les lignes de données. Une ligne de données plus
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
