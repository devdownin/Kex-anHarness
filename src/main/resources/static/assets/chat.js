// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Conversation directe avec l'agent : le chemin manuel, quand la supervision ne suffit pas à
// comprendre ce qui se passe.

import {
  $, ago, api, busy, credentials, dismissDrawer, el, empty, failure, featureApi, featureEnabled, headers, onCredentialChange,
  openDrawer, registerDrawer, report,
  params, setDrawerParam, setParams,
} from './core.js';
import { wireProcessWizard } from './process-creation.js';
// EventSource est inutilisable ici : /chat/stream est un POST porteur d'un bearer. Un seul
// découpeur SSE pour toute la console : une seconde copie gardait l'espace qui suit `data:`,
// que la norme retire et que Spring Framework 7.1 émet désormais.
import { events as serverSentEvents } from './user-stream.js';
import { renderMarkdown } from './markdown-view.js';

const CONVERSATION_STORAGE = 'kex.agent.conversation';

// Le serveur ne garde ni la liste des conversations ni leur transcription — seule sa mémoire de
// modèle (ChatMemory) persiste, sans dimension par principal. Reprendre un échange passé n'a donc
// de sens que pour la transcription déjà vue dans ce navigateur : `localStorage`, jamais une source
// de vérité, seulement un moyen de la réafficher telle quelle.
const HISTORY_INDEX = 'kex.agent.conversations';
const TRANSCRIPT_PREFIX = 'kex.agent.conversation.';
const CONTEXT_CONVERSATIONS = 'kex.agent.context-conversations';
const MAX_HISTORY = 20;
const MAX_TURNS_STORED = 40;

// Les questions que pose un exploitant, pas seulement la mise en route : seules les amorces de
// création de processus étaient proposées, et elles restaient affichées après le premier échange.
const OPS_STARTERS = [
  {
    label: 'Lag d’un groupe',
    prompt: 'Quel est le lag du consumer group [groupe] ? Dis s’il rattrape ou prend du retard, avec la mesure et l’outil qui la fournit.',
  },
  {
    label: 'Rejets et DLQ',
    prompt: 'Y a-t-il des messages rejetés ou en DLQ sur la dernière heure ? Donne leur part par rapport au topic source et les causes les plus fréquentes.',
  },
  {
    label: 'Topics actifs',
    prompt: 'Quels topics ont le plus d’activité sur la dernière heure, et lesquels n’en ont aucune ?',
  },
  {
    label: 'Services joignables',
    prompt: 'Quels serveurs MCP sont joignables en ce moment, et quels outils exposent-ils ?',
  },
];

const PROCESS_STARTERS = [
  {
    label: 'Définir un processus',
    prompt: 'Aide-moi à définir un nouveau processus d’intégration à surveiller. Pose-moi les questions nécessaires sur son objectif, ses étapes, ses sources et destinations, et les preuves disponibles avant de proposer sa déclaration dans kex.agent.supervision.processes.',
  },
  {
    label: 'Suivre un flux Kafka',
    prompt: 'Prépare une entrée YAML sous kex.agent.supervision.processes pour un flux Kafka allant de [topic source] à [topic destination], consommé par [consumer group]. Propose id, name, description et hint fondés sur ces informations. Signale ce qu’il faut encore vérifier.',
  },
  {
    label: 'Suivre plusieurs étapes',
    prompt: 'Aide-moi à décrire un processus d’intégration en plusieurs étapes : [étapes et topics dans l’ordre]. Identifie les preuves à contrôler à chaque étape, puis propose les champs id, name, description et hint de son entrée YAML sous kex.agent.supervision.processes.',
  },
  {
    label: 'Surveiller les échecs',
    prompt: 'Prépare une entrée YAML sous kex.agent.supervision.processes pour [flux métier] avec sa DLQ [topic DLQ] et son consumer group [groupe]. Décris comment détecter un retard ou des échecs et propose id, name, description et hint sans supposer de seuils non fournis.',
  },
];

let conversationId = null;
let inFlight = null;
let unauthorized = () => {};
let contextualPayload = '';
let contextualTitle = '';
let activeContextId = null;
let contextualConversationId = null;
let workspaceTransfer = null;
const isolatedConversationIds = new Set();

// Une demande transférée conserve le stockage isolé de l'espace utilisateur. Elle ne doit pas
// entrer dans l'ancien historique global de la console, lisible sous un autre compte.
function workspaceRequest(update) {
  if (!workspaceTransfer) return null;
  const key = `kex.agent.user.requests.${encodeURIComponent(workspaceTransfer.identity)}`;
  try {
    const rows = JSON.parse(sessionStorage.getItem(key) || '[]');
    const row = rows.find(r => r.id === workspaceTransfer.requestId);
    if (row && update) { update(row); sessionStorage.setItem(key, JSON.stringify(rows)); }
    return row;
  } catch { return null; }
}

onCredentialChange(() => {
  if (!workspaceTransfer) return;
  inFlight?.abort();
  workspaceTransfer = null;
  conversationId = null;
  $('#transcript')?.replaceChildren();
  if ($('#conversation-id')) Object.assign($('#conversation-id'), { textContent: '', hidden: true });
});

try {
  conversationId = sessionStorage.getItem(CONVERSATION_STORAGE);
} catch {
  /* sans stockage, la conversation reste valide pour la durée de la page */
}

function setConversation(id) {
  conversationId = id || null;
  // Sans conversation, un tiret seul flottait dans l'en-tête : l'identifiant n'apparaît qu'une fois tiré.
  // Un UUID entier ne se lit pas et pousse les boutons : son début suffit à le reconnaître, le
  // reste est au survol et à la copie.
  const label = id && id.length > 13 ? `${id.slice(0, 8)}…` : id || '';
  Object.assign($('#conversation-id'), { textContent: label, title: id ? `Conversation ${id}` : '', hidden: !id });
  $('#conversation-id').dataset.id = id || '';
  $('#clear-conversation').disabled = !id;
  if (workspaceTransfer) return;
  try {
    if (id) sessionStorage.setItem(CONVERSATION_STORAGE, id);
    else sessionStorage.removeItem(CONVERSATION_STORAGE);
  } catch {
    /* idem */
  }
}

function readIndex() {
  try {
    return JSON.parse(localStorage.getItem(HISTORY_INDEX) || '[]');
  } catch {
    return [];
  }
}

function writeIndex(list) {
  try {
    localStorage.setItem(HISTORY_INDEX, JSON.stringify(list.slice(0, MAX_HISTORY)));
  } catch {
    /* navigation privée ou quota dépassé : l'historique reste valide pour la session en cours */
  }
}

function touchIndex(id, label) {
  if (!id) return;
  if (isolatedConversationIds.has(id) && workspaceTransfer?.conversationId !== id) return;
  if (workspaceTransfer?.conversationId === id) {
    workspaceRequest(row => { row.updatedAt = new Date().toISOString(); });
    return;
  }
  const list = readIndex();
  const existing = list.find((entry) => entry.id === id);
  const remaining = list.filter((entry) => entry.id !== id);
  // Le libellé se fixe au premier message et ne bouge plus : le faire suivre le dernier message
  // rendrait une entrée méconnaissable d'un envoi à l'autre dans la liste.
  remaining.unshift({ id, label: existing?.label || label || 'Conversation', updatedAt: new Date().toISOString() });
  writeIndex(remaining);
}

function removeFromIndex(id) {
  writeIndex(readIndex().filter((entry) => entry.id !== id));
  const contexts = readContextConversations();
  Object.entries(contexts).forEach(([contextId, conversation]) => {
    if (conversation === id) delete contexts[contextId];
  });
  writeContextConversations(contexts);
  if (contextualConversationId === id) contextualConversationId = null;
  try {
    localStorage.removeItem(TRANSCRIPT_PREFIX + id);
  } catch {
    /* idem */
  }
}

function readContextConversations() {
  try {
    return JSON.parse(sessionStorage.getItem(CONTEXT_CONVERSATIONS) || '{}');
  } catch {
    return {};
  }
}

function writeContextConversations(contexts) {
  try {
    sessionStorage.setItem(CONTEXT_CONVERSATIONS, JSON.stringify(contexts));
  } catch {
    /* sans stockage, l'isolation reste valable tant que la page n'est pas rechargée */
  }
}

function rememberContextConversation(contextId, id) {
  if (!contextId || !id) return;
  const contexts = readContextConversations();
  contexts[contextId] = id;
  writeContextConversations(contexts);
}

function readTranscript(id) {
  if (workspaceTransfer?.conversationId === id) return workspaceRequest()?.turns || workspaceTransfer.turns;
  if (isolatedConversationIds.has(id)) return [];
  try {
    return JSON.parse(localStorage.getItem(TRANSCRIPT_PREFIX + id) || '[]');
  } catch {
    return [];
  }
}

function appendTranscript(id, turn) {
  if (!id) return;
  if (isolatedConversationIds.has(id) && workspaceTransfer?.conversationId !== id) return;
  if (workspaceTransfer?.conversationId === id) {
    workspaceRequest(row => {
      row.turns = [...row.turns, turn].slice(-MAX_TURNS_STORED);
      row.updatedAt = new Date().toISOString();
      row.status = turn.role === 'agent' ? 'PARTIAL' : 'RUNNING';
    });
    return;
  }
  const turns = readTranscript(id);
  turns.push(turn);
  try {
    localStorage.setItem(TRANSCRIPT_PREFIX + id, JSON.stringify(turns.slice(-MAX_TURNS_STORED)));
  } catch {
    /* idem */
  }
}

/** Le composer grandit avec son contenu, jusqu'à `max-height` (`console.css`) puis défile. */
function resizeComposer(field) {
  field.style.height = 'auto';
  field.style.height = `${field.scrollHeight}px`;
}

/** À cette distance du bas ou moins, on considère que l'utilisateur suit le flux en direct. */
function nearBottom(container, threshold = 56) {
  return container.scrollHeight - container.scrollTop - container.clientHeight <= threshold;
}

function updateBubble(bubble, role, text) {
  if (role === 'error') {
    bubble.textContent = text || '';
  } else {
    bubble.replaceChildren(renderMarkdown(text || ''));
  }
}

function addTurn(role, text) {
  const turn = el('li', `turn ${role}`);
  turn.append(el('span', 'who', role === 'user' ? 'vous' : role === 'error' ? 'erreur' : 'agent'));
  const bubble = el('div', 'bubble');
  updateBubble(bubble, role, text);
  turn.append(bubble);
  const transcript = $('#transcript');
  transcript.append(turn);
  transcript.scrollTop = transcript.scrollHeight;
  return { turn, bubble };
}

// ── Questions de relance ─────────────────────────────────────────────────
//
// Débrayables à deux niveaux : `kex.agent.suggestions.enabled` côté serveur (la route et l'interrupteur
// disparaissent alors) et l'interrupteur « Suggestions » ici, retenu par navigateur. Éteint, aucun appel
// n'est fait : chaque suggestion coûte un appel au modèle, compté dans le budget de jetons.
const SUGGESTIONS_KEY = 'kex.agent.chat.suggestions';

function suggestionsWanted() {
  try {
    return localStorage.getItem(SUGGESTIONS_KEY) !== 'off';
  } catch {
    return true;
  }
}

function clearSuggestions() {
  document.querySelectorAll('#transcript .turn-suggestions').forEach((node) => node.remove());
}

/**
 * Sous la réponse, de une à trois questions proposées. Un clic place la question dans le champ de
 * saisie et rend la main : c'est la personne qui l'envoie, ou la modifie avant. Ce que le modèle a
 * écrit ne part jamais tout seul vers l'agent et ses outils.
 */
async function offerSuggestions(turn, question, answer) {
  const toggle = $('#suggestions-mode');
  if (!toggle || toggle.closest('[hidden]') || !toggle.checked) return;
  const requestCredential = credentials.get();
  let result;
  try {
    result = await featureApi('suggestions', '/api/agent/chat/suggestions', {
      method: 'POST', body: { question, answer: answer.slice(0, 20_000) },
    });
  } catch {
    // Une suggestion n'est jamais la réponse : son absence ne mérite pas une alerte.
    return;
  }
  // Pendant l'attente, l'accès a pu changer, une autre question partir, ou l'interrupteur s'éteindre.
  if (requestCredential !== credentials.get() || !turn.isConnected || !toggle.checked
      || turn !== $('#transcript').lastElementChild) return;
  const items = (Array.isArray(result?.suggestions) ? result.suggestions : [])
    .filter((text) => typeof text === 'string' && text.trim()).slice(0, 3);
  if (!items.length) return;
  const transcript = $('#transcript');
  const stick = nearBottom(transcript);
  const box = el('div', 'turn-suggestions');
  box.setAttribute('role', 'group');
  box.setAttribute('aria-label', 'Questions suggérées');
  box.append(el('span', 'turn-suggestions-label', 'Pour aller plus loin'));
  for (const text of items) {
    const button = el('button', 'suggestion-chip', text);
    button.type = 'button';
    button.title = 'Placer cette question dans le champ de saisie — rien n’est envoyé';
    button.addEventListener('click', () => {
      const field = $('#prompt');
      field.value = text;
      resizeComposer(field);
      field.focus();
    });
    box.append(button);
  }
  turn.append(box);
  if (stick) transcript.scrollTop = transcript.scrollHeight;
}

function wireSuggestionsToggle() {
  const toggle = $('#suggestions-mode');
  if (!toggle) return;
  toggle.checked = suggestionsWanted();
  toggle.addEventListener('change', () => {
    try {
      localStorage.setItem(SUGGESTIONS_KEY, toggle.checked ? 'on' : 'off');
    } catch {
      // Stockage indisponible : le choix vaut pour cette page.
    }
    if (!toggle.checked) clearSuggestions();
  });
  onCredentialChange(() => refreshSuggestionsSwitch());
}

/**
 * Éteintes sur le serveur : l'interrupteur ne s'affiche pas, plutôt que de promettre ce qui n'existe pas.
 * Lu à l'ouverture de la vue et à chaque changement d'accès, jamais au chargement du module : sans
 * accès, la lecture de whoami ouvrirait la demande d'identifiants avant que la personne n'ait rien fait.
 */
async function refreshSuggestionsSwitch() {
  const holder = $('#suggestions-switch');
  if (!holder || !credentials.get()) return;
  holder.hidden = !(await featureEnabled('suggestions'));
}

/**
 * Une panne du fournisseur ou un disjoncteur ouvert sont passagers : sans bouton, il fallait
 * recopier le message à la main. Le bouton remet le message dans le champ et le renvoie.
 */
function offerRetry(turn, message) {
  if (!message || turn.querySelector('.turn-retry')) return;
  const retry = el('button', 'ghost turn-retry', 'Réessayer');
  retry.type = 'button';
  retry.addEventListener('click', () => {
    const field = $('#prompt');
    if ($('#send').disabled) return;
    field.value = message;
    resizeComposer(field);
    retry.remove();
    $('#composer').requestSubmit();
  });
  turn.append(retry);
}

function addContextTurn(role, text) {
  const turn = el('li', `turn ${role}`);
  turn.append(el('span', 'who', role === 'user' ? 'vous' : role === 'error' ? 'erreur' : 'agent'));
  const bubble = el('div', 'bubble');
  updateBubble(bubble, role, text);
  turn.append(bubble);
  const transcript = $('#context-chat-transcript');
  transcript.append(turn);
  transcript.scrollTop = transcript.scrollHeight;
  return turn;
}

function renderSentContext(turn, saved) {
  if (!saved.context) return;
  const detail = el('details', 'sent-context');
  detail.append(el('summary', null, saved.contextTitle || 'Contexte transmis'),
    el('pre', 'dump', saved.context));
  turn.append(detail);
}

function replayContext(id) {
  $('#context-chat-transcript').replaceChildren();
  if (!id) return;
  readTranscript(id).forEach((saved) => {
    const turn = addContextTurn(saved.role, saved.text);
    if (saved.role === 'agent') {
      renderToolChips(turn, saved.tools || []);
      renderFinishReason(turn, saved.finishReason);
    }
  });
}

/** Ouvre l'assistant à côté du contexte opérationnel, sans changer de route. */
export function openContextual({ title = 'Interroger l’agent', context = '', contextId = null } = {}) {
  contextualPayload = context.trim();
  contextualTitle = title;
  activeContextId = contextId || `${title}\n${contextualPayload}`;
  contextualConversationId = readContextConversations()[activeContextId] || null;
  $('#context-chat-title').textContent = title;
  $('#context-chat-payload').textContent = contextualPayload || 'Aucun contexte structuré.';
  replayContext(contextualConversationId);
  $('#context-chat').hidden = false;
  document.documentElement.dataset.contextChat = 'open';
  $('#context-chat-prompt').focus();
}

function closeContextual() {
  $('#context-chat').hidden = true;
  delete document.documentElement.dataset.contextChat;
}

async function sendContextual(question) {
  const contextId = activeContextId;
  const context = contextualPayload;
  const title = contextualTitle;
  const ownConversationId = contextualConversationId;
  const message = [context, `Question de l’opérateur : ${question}`].filter(Boolean).join('\n\n');
  const answer = await api('/api/agent/chat', {
    method: 'POST', body: { conversationId: ownConversationId, message },
  });
  if (activeContextId === contextId) contextualConversationId = answer.conversationId;
  rememberContextConversation(contextId, answer.conversationId);
  const savedQuestion = { role: 'user', text: question, context, contextTitle: title };
  appendTranscript(answer.conversationId, savedQuestion);
  touchIndex(answer.conversationId, question.slice(0, 48));
  appendTranscript(answer.conversationId,
    { role: 'agent', text: answer.content, tools: answer.tools, finishReason: answer.finishReason });

  if (activeContextId === contextId) {
    // La conversation complète reste la continuité de ce panneau : en l'ouvrant ensuite, les tours
    // effectués ici ne disparaissent pas de l'écran ni de la transcription locale. La rejouer évite
    // aussi de juxtaposer à l'écran les tours de deux contextes dont les conversations sont isolées.
    setConversation(answer.conversationId);
    replay(answer.conversationId);
    const contextualTurn = addContextTurn('agent', answer.content);
    renderToolChips(contextualTurn, answer.tools || []);
    renderSources(contextualTurn, answer.sources);
    renderFinishReason(contextualTurn, answer.finishReason);
  }
}

function renderToolChips(turn, calls) {
  if (!calls.length) return;
  let chips = turn.querySelector('.chips');
  if (!chips) {
    chips = el('div', 'chips');
    turn.append(chips);
  }
  chips.replaceChildren(...calls.map((call) => {
    const chip = el('span', call.failed ? 'chip failed' : 'chip');
    chip.append(el('span', null, call.tool), el('span', null, `${call.durationMillis} ms`));
    return chip;
  }));
}

/**
 * Les fins normales restent muettes ; le plafond de jetons atteint, ou un motif que la console ne
 * connaît pas, se voient. Sans ce repère, une réponse coupée en plein milieu s'affiche exactement
 * comme une réponse complète — et le vocabulaire varie d'un fournisseur à l'autre, si bien qu'un
 * motif inconnu vaut un doute affiché, jamais un silence.
 */
const NORMAL_ENDINGS = new Set(['end_turn', 'stop', 'stop_sequence', 'tool_use', 'tool_calls']);
const TRUNCATED = new Set(['max_tokens', 'length']);

function renderSources(turn, sources) {
  if (!sources?.length) return;
  const details = el('details', 'tool-log');
  details.append(el('summary', null, `Sources documentaires (${sources.length})`));
  sources.forEach((source) => {
    details.append(el('p', 'muted', `[source:${source.id}] ${source.source} · ${source.observedAt}`));
    details.append(el('pre', 'dump muted', source.excerpt));
  });
  turn.append(details);
}

function renderFinishReason(turn, finishReason) {
  const reason = (finishReason || '').toLowerCase();
  if (!reason || NORMAL_ENDINGS.has(reason)) return;
  const note = el('div', 'chips');
  note.append(el('span', 'chip failed', TRUNCATED.has(reason)
    ? `réponse coupée au plafond de jetons (${finishReason})`
    : `fin inhabituelle (${finishReason})`));
  turn.append(note);
}

/**
 * Les chiffres de la réponse ne se retrouvent pas tous dans ce que les outils ont rendu. Une note, pas
 * un blocage : une somme calculée par le modèle n'est pas retrouvée telle quelle. Le texte vient du
 * serveur, qui sait distinguer « aucun outil appelé » de « un résultat illisible ».
 */
function renderEvidence(turn, evidence) {
  if (!evidence?.message) return;
  if (evidence.status !== 'UNSUPPORTED' && evidence.status !== 'UNVERIFIABLE') return;
  turn.querySelector('.evidence-note')?.remove();
  const note = el('div', 'chips evidence-note');
  note.append(el('span', evidence.status === 'UNSUPPORTED' ? 'chip failed' : 'chip', `⚠ ${evidence.message}`));
  note.title = 'Contrôle automatique : une mesure citée sans outil derrière n\u2019est qu\u2019une affirmation.';
  turn.append(note);
}

function renderToolLog(calls) {
  $('#tool-log').replaceChildren(...calls.map((call) => {
    const line = el('li');
    line.append(el('span', call.failed ? 'ko' : null, call.tool), el('span', 'dur', `${call.durationMillis} ms`));
    return line;
  }));
  $('#tool-log-empty').hidden = calls.length > 0;
}

async function sendStreaming(message) {
  const requestCredential = credentials.get();
  const controller = new AbortController();
  inFlight = controller;
  const response = await fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
    body: JSON.stringify({ conversationId, message }),
    signal: controller.signal,
  });
  if (response.status === 401) unauthorized();
  if (!response.ok) throw await failure(response);

  const { turn, bubble } = addTurn('agent', '');
  bubble.classList.add('caret');
  const calls = [];
  // Capturé plutôt que relu sur `conversationId` au moment de persister : reprendre une autre
  // conversation pendant que ce flux tourne encore réassigne la variable partagée, et le flux
  // interrompu ne doit pas écrire son tour incomplet dans la conversation qui vient de le remplacer.
  let ownConversationId = null;
  let accumulatedText = '';
  let completed = false;
  let failed = false;
  try {
    for await (const event of serverSentEvents(response)) {
      if (requestCredential !== credentials.get()) throw new DOMException('Accès modifié', 'AbortError');
      if (event.name === 'conversation') {
        setConversation(event.data);
        ownConversationId = event.data;
        appendTranscript(ownConversationId, { role: 'user', text: message });
        touchIndex(ownConversationId, message.slice(0, 48));
      } else if (event.name === 'token') {
        // Capturé avant d'ajouter le texte : une fois le contenu ajouté, `scrollHeight` a déjà
        // grandi et la distance au bas ne dit plus si l'utilisateur y était avant ce jeton.
        const transcript = $('#transcript');
        const stick = nearBottom(transcript);
        accumulatedText += event.data;
        updateBubble(bubble, 'agent', accumulatedText);
        if (stick) transcript.scrollTop = transcript.scrollHeight;
      } else if (event.name === 'sources') {
        renderSources(turn, JSON.parse(event.data));
      } else if (event.name === 'evidence') {
        renderEvidence(turn, JSON.parse(event.data));
      } else if (event.name === 'tool') {
        calls.push(JSON.parse(event.data));
        renderToolChips(turn, calls);
        renderToolLog(calls);
      } else if (event.name === 'done') {
        completed = true;
      } else if (event.name === 'error') {
        failed = true;
        turn.classList.add('error');
        accumulatedText += (accumulatedText ? '\n\n' : '') + event.data;
        updateBubble(bubble, 'error', accumulatedText);
        offerRetry(turn, message);
      }
    }
  } finally {
    bubble.classList.remove('caret');
    // Le motif d'arrêt n'arrive pas sur ce chemin (voir OBSERVABILITE.md) : rien à consigner ici,
    // contrairement au chemin bloquant.
    if (ownConversationId) {
      appendTranscript(ownConversationId, { role: 'agent', text: accumulatedText, tools: calls });
    }
    inFlight = null;
  }
  // Une réponse confirmée de bout en bout, et elle seule : une relance sur une réponse coupée
  // partirait d'un texte que l'agent n'a pas fini d'écrire.
  return completed && !failed && accumulatedText.trim() ? { turn, text: accumulatedText } : null;
}

async function sendBlocking(message) {
  const requestCredential = credentials.get();
  const answer = await api('/api/agent/chat', { method: 'POST', body: { conversationId, message } });
  if (requestCredential !== credentials.get()) return;
  setConversation(answer.conversationId);
  appendTranscript(answer.conversationId, { role: 'user', text: message });
  touchIndex(answer.conversationId, message.slice(0, 48));
  const { turn } = addTurn('agent', answer.content);
  renderToolChips(turn, answer.tools || []);
  renderSources(turn, answer.sources);
  renderFinishReason(turn, answer.finishReason);
  renderEvidence(turn, answer.evidence);
  renderToolLog(answer.tools || []);
  appendTranscript(answer.conversationId,
    { role: 'agent', text: answer.content, tools: answer.tools, finishReason: answer.finishReason });
  return answer.content?.trim() ? { turn, text: answer.content } : null;
}

/** Redessine une conversation déjà connue depuis sa transcription locale, sans appel réseau. */
function replay(id) {
  $('#transcript').replaceChildren();
  let lastTools = [];
  readTranscript(id).forEach((saved) => {
    const { turn } = addTurn(saved.role, saved.text);
    if (saved.role === 'user') renderSentContext(turn, saved);
    if (saved.role === 'agent') {
      if (saved.tools?.length) {
        renderToolChips(turn, saved.tools);
        lastTools = saved.tools;
      }
      if (saved.finishReason) renderFinishReason(turn, saved.finishReason);
    }
  });
  renderToolLog(lastTools);
}

function historyRow(entry) {
  const card = el('article', 'card');
  const head = el('header');
  head.append(el('h3', null, entry.label || 'Conversation'));
  if (entry.id === conversationId) head.append(el('span', 'muted', 'en cours'));
  card.append(head);
  card.append(el('p', 'muted', `${ago(entry.updatedAt) || '—'} · ${entry.id}`));

  const actions = el('div', 'row-end');
  const remove = el('button', 'ghost danger', 'Supprimer');
  remove.type = 'button';
  remove.setAttribute('aria-label', `Supprimer : ${entry.label}`);
  remove.addEventListener('click', () => busy(remove, async () => {
    try {
      await api(`/api/agent/conversations/${encodeURIComponent(entry.id)}`, { method: 'DELETE' });
    } catch (error) {
      // Une conversation déjà purgée côté serveur (expiration, redémarrage) n'empêche pas de
      // nettoyer la trace locale : seul un échec réseau mérite d'être signalé ici.
      if (error.status !== 404) report(error);
    }
    removeFromIndex(entry.id);
    if (entry.id === conversationId) {
      setConversation(null);
      $('#transcript').replaceChildren();
      renderToolLog([]);
    }
    openHistory();
  }));
  const resume = el('button', 'primary', 'Reprendre');
  resume.type = 'button';
  resume.setAttribute('aria-label', `Reprendre : ${entry.label}`);
  resume.addEventListener('click', () => {
    inFlight?.abort();
    setConversation(entry.id);
    replay(entry.id);
    dismissDrawer();
  });
  actions.append(remove, resume);
  card.append(actions);
  return card;
}

function openHistory() {
  setDrawerParam('historique', '1');
  const list = readIndex();
  const body = el('div');
  if (!list.length) {
    body.append(empty('Aucune conversation récente.', 'Elle apparaît ici après le premier message envoyé.'));
  } else {
    const cards = el('div', 'cards wide');
    list.forEach((entry) => cards.append(historyRow(entry)));
    body.append(cards);
  }
  openDrawer('Conversations récentes', body);
}

/** Reprend un diagnostic contextuel préparé depuis une alerte ou un processus. */
export async function prefill() {
  refreshSuggestionsSwitch();
  const transferred = params().get('userConversation');
  if (transferred) {
    // Le transfert reste local à l'onglet et doit appartenir au compte actuellement authentifié.
    // Une URL seule ne permet jamais d'importer la transcription d'un autre utilisateur.
    try {
      const saved = JSON.parse(sessionStorage.getItem('kex.agent.user.handoff') || 'null');
      const ownCredential = credentials.get();
      const me = await api('/api/agent/whoami');
      if (saved?.conversationId === transferred
          && ownCredential === credentials.get()
          && saved.identity === JSON.stringify([me.tenant, me.name]) && Array.isArray(saved.turns)) {
        inFlight?.abort();
        workspaceTransfer = saved;
        isolatedConversationIds.add(transferred);
        setConversation(transferred);
        touchIndex(transferred, saved.title);
        replay(transferred);
      }
      sessionStorage.removeItem('kex.agent.user.handoff');
    } catch (error) { report(error); }
    setParams({ userConversation: null }, true);
  }
  if (params().get('creation') === 'process') {
    setParams({ creation: null }, true);
    $('#start-process-wizard').click();
  }
  const draft = params().get('draft');
  if (!draft) return;
  const field = $('#prompt');
  const serviceDraft = params().get('serviceDraft') === '1';
  if (!field.value || serviceDraft) {
    field.value = serviceDraft && field.value.trim() ? `${field.value.trimEnd()}\n\n${draft}` : draft;
    // Posé par script, donc sans l'événement `input` qui grandit d'habitude le composer : sans cet
    // appel, un brouillon de plusieurs lignes reste coincé dans une zone d'une seule ligne tant que
    // l'opérateur n'a pas lui-même tapé un caractère.
    resizeComposer(field);
  }
  setParams({ draft: null, serviceDraft: null });
  field.focus();
}

export function wire(onUnauthorized) {
  unauthorized = onUnauthorized;
  setConversation(conversationId);
  wireProcessWizard();
  const starter = ({ label, prompt }) => {
    const button = el('button', 'ghost', label);
    button.type = 'button';
    button.addEventListener('click', () => {
      const field = $('#prompt');
      field.value = field.value.trim() ? `${field.value.trimEnd()}\n\n${prompt}` : prompt;
      resizeComposer(field);
      field.focus();
      // Le premier crochet est ce qu'il reste à compléter : le sélectionner évite de le chercher.
      const hole = field.value.lastIndexOf('[');
      const end = hole >= 0 ? field.value.indexOf(']', hole) + 1 : 0;
      if (hole >= 0 && end > hole) field.setSelectionRange(hole, end);
      else field.setSelectionRange(field.value.length, field.value.length);
    });
    return button;
  };
  $('#chat-ops-starters').replaceChildren(...OPS_STARTERS.map(starter));
  $('#chat-process-starters').replaceChildren(...PROCESS_STARTERS.map(starter));
  registerDrawer('historique', openHistory);
  addEventListener('kex:context-chat', (event) => openContextual(event.detail));
  $('#context-chat-close').addEventListener('click', closeContextual);
  $('#context-chat-full').addEventListener('click', () => {
    if (contextualConversationId) {
      setConversation(contextualConversationId);
      replay(contextualConversationId);
    }
    closeContextual();
  });
  addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('#context-chat').hidden) closeContextual();
  });
  $('#context-chat-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const field = $('#context-chat-prompt');
    const question = field.value.trim();
    if (!question) return;
    addContextTurn('user', question);
    field.value = '';
    const send = $('#context-chat-send');
    send.disabled = true;
    try {
      await sendContextual(question);
    } catch (error) {
      addContextTurn('error', error.message);
      report(error);
    } finally {
      send.disabled = false;
      field.focus();
    }
  });

  $('#composer').addEventListener('submit', async (event) => {
    event.preventDefault();
    const field = $('#prompt');
    const message = field.value.trim();
    if (!message) return;

    clearSuggestions();
    addTurn('user', message);
    field.value = '';
    resizeComposer(field);
    renderToolLog([]);

    const streaming = $('#stream-mode').checked;
    $('#send').disabled = true;
    $('#abort').hidden = !streaming;
    let answered = null;
    try {
      answered = await (streaming ? sendStreaming(message) : sendBlocking(message));
    } catch (error) {
      if (error.name === 'AbortError') addTurn('error', 'Flux interrompu.');
      else {
        offerRetry(addTurn('error', error.message).turn, message);
        report(error);
      }
    } finally {
      $('#send').disabled = false;
      $('#abort').hidden = true;
      field.focus();
    }
    // Après la main rendue : les suggestions arrivent quand elles arrivent, sans bloquer l'envoi suivant.
    if (answered) offerSuggestions(answered.turn, message, answered.text);
  });
  wireSuggestionsToggle();

  $('#abort').addEventListener('click', () => inFlight?.abort());

  $('#prompt').addEventListener('input', (event) => resizeComposer(event.target));

  $('#prompt').addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      $('#composer').requestSubmit();
    }
  });

  $('#clear-conversation').addEventListener('click', async () => {
    if (!conversationId) return;
    try {
      await api(`/api/agent/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE' });
      removeFromIndex(conversationId);
      setConversation(null);
      $('#transcript').replaceChildren();
      renderToolLog([]);
    } catch (error) {
      report(error);
    }
  });

  $('#open-history').addEventListener('click', openHistory);
}
