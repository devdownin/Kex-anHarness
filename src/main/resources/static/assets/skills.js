// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Gouvernance de l'agent : la charte, la bibliothèque de compétences approuvées et ce qui attend
// une décision. Trois lectures distinctes d'une même question — que peut dire l'agent de plus que
// son prompt système, et qui l'a autorisé — réunies dans un seul onglet plutôt que dispersées.

import {
  $, ago, api, busy, confirmAction, definition, el, empty, report, stamp, stateTag, toast,
} from './core.js';
import { EQUAL, WITH, blindPairs, preferredSide, tally, tallyLine } from './blind-review.js';

const SKILLS_BASE = '/api/agent/skills';
const CHARTER_BASE = '/api/agent/charter';

export async function governance() {
  await Promise.all([charter(), reviewQueue(), curation()]);
}

/* ── Qui je suis ───────────────────────────────────────────────────────── */

/**
 * Sans jeton reconnu, `/api/agent/whoami` répond 401 comme toute autre route : l'étiquette reste
 * simplement masquée plutôt que d'afficher une erreur qui couvrirait le vrai message, déjà porté
 * par `credential-label` juste au-dessus.
 */
export async function whoami() {
  const label = $('#whoami-label');
  try {
    const data = await api('/api/agent/whoami');
    label.textContent = data.tenant === data.name
      ? `Connecté comme ${data.name}`
      : `Connecté comme ${data.name} · locataire ${data.tenant}`;
    label.hidden = false;
  }
  catch {
    label.hidden = true;
  }
}

/* ── Charte ────────────────────────────────────────────────────────────── */

let historyLoaded = false;

async function charter() {
  const host = $('#charter-current');
  if (!host.firstChild) host.replaceChildren(el('p', 'state loading', 'Chargement…'));
  try {
    const data = await api(CHARTER_BASE);
    $('#charter-markdown').value = data.markdown || '';
    host.replaceChildren(data.updatedBy ? charterSummary(data)
      : empty('Aucune charte écrite.',
        'Le prompt système reste seul en vigueur tant que personne ne l’a rédigée.'));
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

function charterSummary(data) {
  const wrap = el('div', 'summary');
  wrap.append(definition('Écrite par', el('span', null, data.updatedBy)));
  wrap.append(definition('Le', el('span', null, stamp(data.updatedAt))));
  wrap.append(definition('Motif', el('span', null, data.reason || '—')));
  return wrap;
}

async function saveCharter(event) {
  event.preventDefault();
  const markdown = $('#charter-markdown').value;
  const reason = $('#charter-reason').value.trim();
  try {
    await api(CHARTER_BASE, { method: 'PUT', body: { markdown, reason } });
    toast('Charte enregistrée.');
    $('#charter-reason').value = '';
    await charter();
    if (historyLoaded) await charterHistory();
  }
  catch (error) {
    report(error);
  }
}

async function toggleCharterHistory() {
  const host = $('#charter-history');
  host.hidden = !host.hidden;
  if (!host.hidden && !historyLoaded) {
    historyLoaded = true;
    await charterHistory();
  }
}

async function charterHistory() {
  const host = $('#charter-history');
  try {
    const rows = await api(`${CHARTER_BASE}/versions`);
    if (!rows.length) {
      host.replaceChildren(empty('Aucune version antérieure.'));
      return;
    }
    const list = el('ol', 'stack');
    // Le service rend déjà la plus récente d'abord : rien à trier de plus ici.
    rows.forEach((row) => {
      const item = el('li', 'confirm-row');
      item.append(el('span', 'label', `${row.reviewedBy || row.owner} · ${ago(row.reviewedAt || row.createdAt)}`));
      item.append(el('span', 'value muted', row.reviewReason || '—'));
      list.append(item);
    });
    host.replaceChildren(list);
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

/* ── File de revue ─────────────────────────────────────────────────────── */

async function reviewQueue() {
  const host = $('#skills-review-queue');
  if (!host.firstChild) host.replaceChildren(el('div', 'state loading skeleton skeleton-list'));
  try {
    const rows = await api(`${SKILLS_BASE}/review-queue`);
    host.replaceChildren(rows.length ? reviewQueueList(rows)
      : empty('Aucune compétence en attente.',
        'Une tâche aux résultats vérifiés peut en proposer une ; rien n’attend de décision pour l’instant.'));
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

function reviewQueueList(rows) {
  const list = el('div', 'stack');
  rows.forEach((row) => list.append(reviewQueueCard(row)));
  return list;
}

function reviewQueueCard(row) {
  const card = el('article', 'card');
  card.dataset.state = 'PENDING';
  const head = el('header');
  head.append(el('h3', null, row.title));
  head.append(stateTag('PENDING', 'En attente'));
  card.append(head);
  card.append(definition('Propriétaire', el('span', null, row.owner)));
  card.append(definition('Proposée', el('span', null, ago(row.createdAt))));
  card.append(definition('Preuve', el('span', 'muted', row.evidence)));
  if (row.verification) {
    card.append(definition('Version', el('span', 'mono', row.verification.version || '—')));
    card.append(definition('Source', el('span', 'mono', row.verification.sourceId || '—')));
    card.append(definition('Validité', el('span', null, row.verification.validUntil || '—')));
  }
  card.append(definition('Portée', el('span', null, scopeLabel(row))));
  card.append(el('pre', 'dump muted', row.markdown));
  const probes = row.probes || [];
  if (probes.length) card.append(probeSummary(probes[probes.length - 1], row.id));

  const actions = el('div', 'card-actions');
  const probe = el('button', 'ghost', 'Rejouer');
  probe.type = 'button';
  probe.setAttribute('aria-label', `Rejouer avec et sans : ${row.title}`);
  probe.addEventListener('click', () => busy(probe, () => probeSkill(row)));
  const reject = el('button', 'ghost danger', 'Rejeter');
  reject.type = 'button';
  reject.setAttribute('aria-label', `Rejeter : ${row.title}`);
  reject.addEventListener('click', () => busy(reject, () => decideSkill(row, false)));
  const approve = el('button', 'primary', 'Approuver');
  approve.type = 'button';
  approve.setAttribute('aria-label', `Approuver : ${row.title}`);
  approve.addEventListener('click', () => busy(approve, () => decideSkill(row, true)));
  actions.append(probe, reject, approve);
  card.append(actions);
  return card;
}

function scopeLabel(entry) {
  if (entry.scope === 'INVARIANT') return 'Invariante — injectée dans toute conversation';
  const triggers = entry.triggers || [];
  return triggers.length ? `Domaine — sur : ${triggers.join(', ')}` : 'Domaine — sans déclencheur, toujours éligible';
}

// Un écart stable n'est pas « bon » : il dit que la compétence change le comportement, pas
// qu'elle l'améliore. D'où une pastille neutre, et l'avertissement réservé au confondu.
const READINGS = {
  CONFOUNDED: ['Écart confondu', 'WARNING'],
  NO_OBSERVED_EFFECT: ['Aucun effet observé', 'UNKNOWN'],
  STABLE_DIFFERENCE: ['Écart stable', 'INFO'],
};

/**
 * Un écart de comportement, jamais un verdict sur la réponse : la qualification dit seulement si
 * l'écart est lisible. Une réponse qui « a l'air meilleure » se lit dans les réponses dépliées.
 */
function probeSummary(probe, skillId) {
  const wrap = el('div', 'stack');
  const passes = probe.candidates.length;
  wrap.append(el('h4', 'drawer-sub',
    `Dernier rejeu · ${passes} passe(s) de chaque côté · ${ago(probe.observedAt)} · ${probe.actor}`));
  wrap.append(definition('Demande', el('span', null, probe.request)));
  const blind = blindComparison(probe);
  if (blind) wrap.append(blind);
  // Absente sur un rejeu antérieur à la qualification : rien ne s'affiche plutôt qu'un défaut.
  if (probe.reading && READINGS[probe.reading.outcome]) {
    const [label, state] = READINGS[probe.reading.outcome];
    const reading = el('div', 'stack');
    reading.append(stateTag(state, label));
    probe.reading.reasons.forEach((reason) => reading.append(el('span', 'muted', reason)));
    wrap.append(definition('Lecture de l’écart', reading));
  }
  const read = probe.candidates.filter((run) => (run.skillsRead || []).includes(skillId)).length;
  // Une candidate jamais lue n'a pas pu produire l'écart : le dire avant de le montrer.
  wrap.append(definition('Compétence lue', el('span', null, `${read} passe(s) sur ${passes}`)));
  wrap.append(definition('Outils ajoutés à chaque passe', el('span', 'mono', probe.toolsAdded.join(', ') || '—')));
  wrap.append(definition('Outils abandonnés à chaque passe', el('span', 'mono', probe.toolsDropped.join(', ') || '—')));
  if (probe.tools.length) {
    const list = el('ul', 'stack');
    probe.tools.forEach((tool) => list.append(el('li', 'mono',
      `${tool.tool} : ${tool.inReferences}/${probe.references.length} sans, ${tool.inCandidates}/${passes} avec`)));
    wrap.append(definition('Fréquence des outils', list));
  }
  const runs = el('details');
  // Ouvrir ce bloc révèle quel côté porte la compétence : il vient après la comparaison à l'aveugle.
  runs.append(el('summary', null, 'Les exécutions et leurs réponses, côtés révélés'));
  probe.references.forEach((run, index) => runs.append(runBlock(`Sans la compétence, passe ${index + 1}`, run)));
  probe.candidates.forEach((run, index) => runs.append(runBlock(`Avec la compétence, passe ${index + 1}`, run)));
  wrap.append(runs);
  return wrap;
}

const SIDE_LABELS = { with: 'avec la compétence', without: 'sans la compétence' };

/**
 * Les réponses de chaque passe, tirées au sort en A et B. Le côté ne s'affiche qu'après le choix,
 * et le choix ne se refait pas : changer d'avis une fois le côté connu, c'est retomber dans le
 * biais que la comparaison existe pour écarter. Rien n'est enregistré côté serveur.
 */
function blindComparison(probe) {
  const pairs = blindPairs(probe);
  if (!pairs.length) return null;
  const box = el('div', 'stack blind-review');
  box.append(el('h4', 'drawer-sub', 'Comparaison à l’aveugle'));
  box.append(el('p', 'muted',
    'Les deux réponses d’une même passe, dans un ordre tiré au sort. Choisir la meilleure révèle laquelle portait la compétence. Préférer celle sans la compétence est une réponse valable.'));
  const sides = [];
  const summary = el('p', 'blind-tally');
  pairs.forEach((pair) => {
    const item = el('div', 'stack blind-pair');
    item.append(el('strong', null, `Passe ${pair.pass}`));
    const labelA = el('span', 'muted', 'Réponse A');
    const labelB = el('span', 'muted', 'Réponse B');
    item.append(labelA, el('pre', 'dump', pair.a.answer), labelB, el('pre', 'dump', pair.b.answer));
    const actions = el('div', 'card-actions');
    const verdict = el('span', 'blind-verdict');
    const buttons = [['a', 'A est meilleure'], ['b', 'B est meilleure'], ['equal', 'Équivalentes']].map(([choice, text]) => {
      const button = el('button', 'ghost', text);
      button.type = 'button';
      button.dataset.choice = choice;
      button.addEventListener('click', () => {
        const side = preferredSide(pair, choice);
        sides.push(side);
        buttons.forEach((other) => { other.disabled = true; });
        labelA.textContent = `Réponse A — ${SIDE_LABELS[pair.a.side]}`;
        labelB.textContent = `Réponse B — ${SIDE_LABELS[pair.b.side]}`;
        verdict.textContent = side === EQUAL ? 'Jugées équivalentes.'
          : `Préférée : ${side === WITH ? SIDE_LABELS.with : SIDE_LABELS.without}.`;
        summary.textContent = tallyLine(tally(sides));
      });
      return button;
    });
    actions.append(...buttons, verdict);
    item.append(actions);
    box.append(item);
  });
  box.append(summary);
  return box;
}

function runBlock(label, run) {
  const block = el('div', 'stack');
  block.append(definition(label, el('span', null, runLine(run))));
  block.append(el('pre', 'dump muted', run.answer || '—'));
  return block;
}

function runLine(run) {
  if (run.error) return `Échec (${run.error})`;
  // Une mesure absente n'est jamais zéro : un fournisseur muet ne devient pas « 0 jeton ».
  const tokens = run.inputTokens == null && run.outputTokens == null
    ? 'jetons non rapportés'
    : `${run.inputTokens ?? '?'} jetons en entrée, ${run.outputTokens ?? '?'} en sortie`;
  return `${run.tools.length} appel(s) d’outil, ${run.failedCalls} en échec · ${tokens}`;
}

const PROBE_REPETITIONS = 3;

const SUGGESTION_SOURCES = {
  TASK: 'Pré-remplie avec l’objectif de la tâche vérifiée dont la procédure est issue.',
  REFUSALS: 'Pré-remplie avec les processus visés par les refus : ce sont les déclencheurs de la règle.',
};

async function probeSkill(row) {
  // Une suggestion indisponible ne bloque pas le rejeu : le réviseur écrit sa demande comme avant.
  let suggestion = null;
  try {
    suggestion = await api(`${SKILLS_BASE}/${encodeURIComponent(row.id)}/probe-suggestion`);
  }
  catch {
    suggestion = null;
  }
  const triggers = suggestion?.triggers?.length ? suggestion.triggers : (row.triggers || []);
  const { confirmed, reason } = await confirmAction({
    title: 'Rejouer une demande avec et sans cette compétence',
    accept: `Rejouer : ${row.title}`,
    lines: [
      ['Compétence', row.title],
      ['Exécution', `${PROBE_REPETITIONS} passes de chaque côté, soit ${PROBE_REPETITIONS * 2} appels au modèle en parallèle, sous l’allowlist d’outils de la configuration.`],
      ['Coût', 'Imputé au budget du locataire propriétaire.'],
      SUGGESTION_SOURCES[suggestion?.source] ? ['Demande', SUGGESTION_SOURCES[suggestion.source]] : null,
      // Une demande qui ne touche aucun déclencheur n'indexe même pas la candidate.
      row.scope !== 'INVARIANT' && triggers.length ? ['Déclencheurs', `La demande doit en citer un : ${triggers.join(', ')}`] : null,
    ],
    reasonLabel: 'Demande à rejouer (une question où la compétence devrait agir)',
    reasonRequired: true,
    reasonValue: suggestion?.request || '',
  });
  if (!confirmed) return;
  try {
    await api(`${SKILLS_BASE}/${encodeURIComponent(row.id)}/probe`, { method: 'POST', body: { request: reason, repetitions: PROBE_REPETITIONS } });
    toast('Rejeu terminé : l’écart est joint à la compétence.');
    await reviewQueue();
  }
  catch (error) {
    report(error);
  }
}

async function decideSkill(row, approve) {
  const confirmed = await confirmAction({
    title: approve ? 'Confirmer cette approbation' : 'Confirmer ce rejet',
    accept: approve ? `Approuver : ${row.title}` : `Rejeter : ${row.title}`,
    lines: [
      ['Compétence', row.title],
      ['Propriétaire', row.owner],
      ['Conséquence', approve
        ? 'Entre dans le contexte durable de ce locataire dès la prochaine conversation.'
        : 'Reste hors du prompt ; l’opérateur qui l’a proposée devra en tirer les conséquences.'],
    ],
  });
  if (!confirmed) return;
  try {
    await api(`${SKILLS_BASE}/${encodeURIComponent(row.id)}/${approve ? 'approve' : 'reject'}`,
      { method: 'POST', body: { reason: approve ? 'Approuvée depuis la console' : 'Rejetée depuis la console' } });
    toast(approve ? 'Compétence approuvée.' : 'Compétence rejetée.');
    await Promise.all([reviewQueue(), curation()]);
  }
  catch (error) {
    report(error);
  }
}

/* ── Curation ──────────────────────────────────────────────────────────── */

async function curation() {
  const host = $('#skills-curation');
  if (!host.firstChild) host.replaceChildren(el('div', 'state loading skeleton skeleton-kpis'));
  try {
    const data = await api(`${SKILLS_BASE}/curation`);
    host.replaceChildren(curationReport(data));
  }
  catch (error) {
    host.replaceChildren(el('p', 'state error', error.message));
  }
}

function curationReport(data) {
  const wrap = el('div', 'stack');

  const tiles = el('div', 'tiles');
  tiles.append(tile('Approuvées', data.approved));
  tiles.append(tile('Injectées', data.injected.length));
  tiles.append(tile('Conditionnelles', data.conditional.length));
  tiles.append(tile('En sommeil', data.dormant.length));
  tiles.append(tile('Jamais lues', data.unread.length));
  tiles.append(tile('Obsolètes', data.stale.length));
  tiles.append(tile('Caractères injectés', data.characters.toLocaleString('fr-FR')));
  const alwaysOn = data.alwaysOn;
  if (alwaysOn) {
    tiles.append(tile('Toujours présent',
      `${alwaysOn.total.toLocaleString('fr-FR')} / ${alwaysOn.budget.toLocaleString('fr-FR')}`));
  }
  wrap.append(tiles);
  // Signalé, jamais corrigé ici : alléger, c'est retirer ou réécrire une procédure qu'un humain a
  // approuvée, ou raccourcir la charte. Le détail dit où se trouve le poids.
  if (alwaysOn?.overBudget) {
    const warning = el('p', 'banner always-on-warning',
      `Le texte présent dans tout échange dépasse le budget : ${alwaysOn.total.toLocaleString('fr-FR')} caractères `
      + `pour ${alwaysOn.budget.toLocaleString('fr-FR')} (prompt système ${alwaysOn.systemPrompt.toLocaleString('fr-FR')}, `
      + `contexte durable ${alwaysOn.durable.toLocaleString('fr-FR')}). Chaque caractère y est payé à chaque échange ; `
      + 'une procédure de domaine à déclencheur ne coûte qu’à la demande.');
    warning.setAttribute('role', 'status');
    wrap.append(warning);
  }

  if (data.dormant.length) {
    wrap.append(digestGroup('En sommeil : approuvées, mais au-delà du plafond d’injection', data.dormant, true));
  }
  if (data.unread.length) {
    wrap.append(digestGroup('Jamais lues : indexées, jamais ouvertes par l’agent depuis leur approbation', data.unread, true));
  }
  if (data.stale.length) {
    wrap.append(digestGroup('Obsolètes : non revues depuis longtemps', data.stale, true));
  }
  if (data.injected.length) {
    wrap.append(digestGroup('Injectées dans toute conversation', data.injected, false));
  }
  if (data.conditional.length) {
    wrap.append(digestGroup('Conditionnelles : injectées quand la demande touche un déclencheur', data.conditional, false));
  }
  if (data.duplicates.length) {
    wrap.append(duplicatesGroup(data.duplicates));
  }
  if (!data.dormant.length && !data.stale.length && !data.injected.length && !data.conditional.length) {
    wrap.append(empty('Aucune compétence approuvée.', 'La bibliothèque reste vide tant que personne n’en approuve.'));
  }
  return wrap;
}

function tile(label, value) {
  const node = el('div', 'tile');
  node.append(el('div', 'label', label), el('div', 'value', String(value)));
  return node;
}

function digestGroup(title, entries, retirable) {
  const group = el('div', 'stack');
  group.append(el('h3', 'drawer-sub', title));
  entries.forEach((entry) => group.append(digestRow(entry, retirable)));
  return group;
}

function digestRow(entry, retirable) {
  const row = el('div', 'confirm-row');
  row.append(el('span', 'label', entry.title));
  row.append(el('span', 'value muted',
    `${scopeLabel(entry)} · ${usageLabel(entry)} · approuvée par ${entry.reviewedBy} · ${ago(entry.reviewedAt)} · ${entry.characters.toLocaleString('fr-FR')} car.`));
  if (retirable) {
    const retire = el('button', 'ghost danger', 'Retirer');
    retire.type = 'button';
    retire.setAttribute('aria-label', `Retirer : ${entry.title}`);
    retire.addEventListener('click', () => busy(retire, () => retireSkill(entry)));
    row.append(retire);
  }
  return row;
}

/** Une invariante est injectée en entier : elle agit sans jamais être « lue », ne rien compter pour elle. */
function usageLabel(entry) {
  if (entry.scope === 'INVARIANT') return 'injectée en entier';
  if (!entry.reads) return 'jamais lue';
  return `lue ${entry.reads} fois, dernière ${ago(entry.lastReadAt)}`;
}

function duplicatesGroup(duplicates) {
  const group = el('div', 'stack');
  group.append(el('h3', 'drawer-sub', 'Doublons de titre'));
  duplicates.forEach((duplicate) => {
    const row = el('div', 'confirm-row');
    row.append(el('span', 'label', duplicate.title));
    row.append(el('span', 'value muted', `${duplicate.entries.length} compétences au même titre`));
    group.append(row);
  });
  return group;
}

/** Le curateur signale, un humain nommé retire — jamais l'inverse, et jamais sans motif. */
async function retireSkill(entry) {
  const { confirmed, reason } = await confirmAction({
    title: 'Confirmer le retrait',
    accept: `Retirer : ${entry.title}`,
    lines: [
      ['Compétence', entry.title],
      ['Conséquence', 'Sort du contexte de toutes les conversations suivantes de ce locataire.'],
    ],
    reasonLabel: 'Motif du retrait (conservé dans l’audit)',
    reasonRequired: true,
  });
  if (!confirmed) return;
  try {
    await api(`${SKILLS_BASE}/${encodeURIComponent(entry.id)}/retire`, { method: 'POST', body: { reason } });
    toast('Compétence retirée.');
    await curation();
  }
  catch (error) {
    report(error);
  }
}

/* ── Câblage ───────────────────────────────────────────────────────────── */

export function wire() {
  $('#charter-form').addEventListener('submit', saveCharter);
  $('#charter-history-toggle').addEventListener('click', toggleCharterHistory);
}
