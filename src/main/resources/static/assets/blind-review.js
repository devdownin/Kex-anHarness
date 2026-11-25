// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

/*
 * Comparaison à l'aveugle des réponses d'un rejeu. Un réviseur qui sait laquelle porte la
 * compétence la trouve meilleure plus souvent qu'elle ne l'est — d'autant qu'il l'a souvent
 * proposée lui-même. Les deux réponses d'une même passe sont donc tirées au sort en A et B, et le
 * côté n'est révélé qu'après le choix. « Sans la compétence » est une réponse aussi valable que
 * l'autre : c'est elle qui dit que la procédure n'apporte rien.
 *
 * Module pur : aucun accès au DOM, pour être testé sous Node.
 */

export const WITH = 'with';
export const WITHOUT = 'without';
export const EQUAL = 'equal';

const usable = (run) => run && !run.error && typeof run.answer === 'string' && run.answer.trim() !== '';

/**
 * Une paire par passe dont les deux côtés ont rendu une réponse. Une passe en échec n'a rien à
 * comparer : l'échec se lit déjà dans la qualification du rejeu.
 *
 * @param random source de hasard injectable, pour que le tirage se teste
 */
export function blindPairs(probe, random = Math.random) {
  const references = probe?.references || [];
  const candidates = probe?.candidates || [];
  const pairs = [];
  for (let pass = 0; pass < Math.min(references.length, candidates.length); pass += 1) {
    const without = references[pass];
    const withSkill = candidates[pass];
    if (!usable(without) || !usable(withSkill)) continue;
    const swapped = random() < 0.5;
    pairs.push({
      pass: pass + 1,
      a: swapped ? { side: WITH, answer: withSkill.answer } : { side: WITHOUT, answer: without.answer },
      b: swapped ? { side: WITHOUT, answer: without.answer } : { side: WITH, answer: withSkill.answer },
    });
  }
  return pairs;
}

/** Traduit un choix « A », « B » ou « égal » en côté préféré. */
export function preferredSide(pair, choice) {
  if (choice === 'a') return pair.a.side;
  if (choice === 'b') return pair.b.side;
  return EQUAL;
}

/**
 * Le décompte des préférences. Sans choix, aucun résumé : un « 0 sur 0 » se lirait comme un
 * verdict défavorable à la compétence.
 */
export function tally(sides) {
  if (!sides.length) return null;
  const count = (side) => sides.filter((value) => value === side).length;
  return { with: count(WITH), without: count(WITHOUT), equal: count(EQUAL), total: sides.length };
}

export function tallyLine(result) {
  if (!result) return '';
  return `Sur ${result.total} comparaison(s) : avec la compétence préférée ${result.with} fois, `
    + `sans ${result.without} fois, équivalentes ${result.equal} fois.`;
}
