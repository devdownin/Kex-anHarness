// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Phrases de la synthèse globale, sans DOM : testées sous Node (src/test/frontend/overview-summary.mjs).

const plural = (count, word) => `${count} ${word}${count === 1 ? '' : 's'}`;

/** Le dernier cycle a échoué : ses compteurs ne mesurent rien de neuf. */
export function lastCycleFailed(data) {
  return Boolean(data?.lastCycle?.failure);
}

/**
 * « Aucune intervention requise » n'est dit que si chaque processus a été relevé sain. Un cycle en
 * échec ou un processus inconnu ressemblaient sinon, sous un point vert, à un système en ordre.
 */
export function heroSummary(data) {
  const total = data?.processesMonitored || 0;
  if (!total) return 'Aucun processus n’est encore déclaré pour la supervision.';
  if (!data.agent?.lastCycleAt) return `${plural(total, 'processus déclaré')} · pas encore analysé${total === 1 ? '' : 's'}`;
  if (lastCycleFailed(data)) return `Dernière analyse en échec · état de ${total} processus non relevé`;
  const healthy = data.processesOk || 0;
  const attention = (data.processesWarning || 0) + (data.processesError || 0);
  const unknown = data.processesUnknown || 0;
  const parts = [`${healthy} processus opérationnel${healthy > 1 ? 's' : ''} sur ${total}`];
  if (attention) parts.push(`${attention} nécessite${attention > 1 ? 'nt' : ''} votre attention`);
  if (unknown) parts.push(`${unknown} dans un état inconnu`);
  if (!attention && !unknown && healthy === total) parts.push('aucune intervention requise');
  return parts.join(' · ');
}

/** Libellé et ton du témoin de cycle : `error` porte le motif, jamais la seule couleur. */
export function heroCycle(data, stamp) {
  const agent = data?.agent || {};
  if (agent.analysing) return { label: 'Analyse en cours', tone: 'running' };
  if (!agent.lastCycleAt) return { label: 'En attente du premier cycle', tone: 'idle' };
  if (lastCycleFailed(data)) return { label: `Dernier cycle en échec · ${stamp(agent.lastCycleAt)}`, tone: 'error' };
  const attention = (data.processesWarning || 0) + (data.processesError || 0) + (data.processesUnknown || 0);
  return { label: `Dernier cycle · ${stamp(agent.lastCycleAt)}`, tone: attention ? 'warning' : 'ok' };
}
