// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Couche UX MCP transversale : point d'entrée unique, configuration guidée,
// fraîcheur visible et garde-fous avant les outils à effet de bord.

const externalCatalog = new Map();
const exposedCatalog = new Map();
const originalFetch = window.fetch.bind(window);
let enhancing = false;

function riskOf(tool = {}) {
  const annotations = tool.annotations || {};
  const name = String(tool.name || '').toLowerCase();
  if (annotations.destructiveHint === true || /(^|[_-])(delete|remove|drop|purge|destroy|truncate|reset)([_-]|$)/.test(name)) {
    return { level: 'ADMIN', label: 'Administration', state: 'danger', detail: 'Peut modifier ou supprimer durablement des données.' };
  }
  if (tool.readOnlyByPolicy === true || annotations.readOnlyHint === true) {
    return { level: 'READ', label: 'Lecture', state: 'safe', detail: 'Aucune écriture n’est déclarée par le serveur.' };
  }
  if (annotations.readOnlyHint === false || /(^|[_-])(create|update|write|set|publish|send|restart|enable|disable|approve|reject)([_-]|$)/.test(name)) {
    return { level: 'WRITE', label: 'Écriture', state: 'warning', detail: 'Peut modifier un système ou déclencher une action externe.' };
  }
  return { level: 'UNKNOWN', label: 'Effet à vérifier', state: 'unknown', detail: 'Le serveur ne précise pas suffisamment les effets de cet outil.' };
}

function style() {
  if (document.querySelector('#mcp-ux-p1-style')) return;
  const node = document.createElement('style');
  node.id = 'mcp-ux-p1-style';
  node.textContent = `
    .mcp-home-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:1rem;margin-bottom:1rem}
    .mcp-home-card{border:1px solid var(--line, #d9dde5);border-radius:14px;padding:1rem;background:var(--surface, transparent);display:grid;gap:.6rem}
    .mcp-home-card h2{margin:0;font-size:1.05rem}.mcp-home-card p{margin:0}.mcp-home-meta{display:flex;gap:.5rem;flex-wrap:wrap}
    .mcp-location-choices{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:.65rem;margin:.6rem 0}
    .mcp-location-choice{border:1px solid var(--line, #d9dde5);border-radius:12px;padding:.75rem;display:grid;gap:.25rem;cursor:pointer}
    .mcp-location-choice:has(input:checked){outline:2px solid currentColor;outline-offset:1px}.mcp-location-choice input{margin:0}
    .mcp-risk{display:flex;align-items:flex-start;gap:.65rem;padding:.7rem .8rem;border:1px solid var(--line, #d9dde5);border-radius:10px;margin:.55rem 0}
    .mcp-risk-badge{font-weight:700;font-size:.76rem;letter-spacing:.03em;text-transform:uppercase;white-space:nowrap}
    .mcp-risk[data-risk="safe"] .mcp-risk-badge{color:var(--ok, #287a49)}
    .mcp-risk[data-risk="warning"] .mcp-risk-badge{color:var(--warning, #9a6414)}
    .mcp-risk[data-risk="danger"] .mcp-risk-badge{color:var(--danger, #b42318)}
    .mcp-risk[data-risk="unknown"] .mcp-risk-badge{color:var(--muted, #667085)}
    .mcp-source-age{font-size:.84rem;margin:.45rem 0}.mcp-source-age[data-stale="true"]{font-weight:650}
    .mcp-actionable-error{border-left:4px solid var(--danger, #b42318);padding:.7rem .85rem;margin:.7rem 0;background:color-mix(in srgb,currentColor 4%,transparent)}
    .mcp-actionable-error strong,.mcp-actionable-error span{display:block}.mcp-actionable-error button{margin-top:.55rem}
    .mcp-tech-advanced{margin-top:.55rem}.mcp-tech-advanced>summary{cursor:pointer;font-weight:650}
    @media(max-width:760px){.mcp-home-grid,.mcp-location-choices{grid-template-columns:1fr}}
  `;
  document.head.append(node);
}

function chip(text) {
  const node = document.createElement('span');
  node.className = 'chip';
  node.textContent = text;
  return node;
}

function button(label, className = 'ghost') {
  const node = document.createElement('button');
  node.type = 'button';
  node.className = className;
  node.textContent = label;
  return node;
}

function enhanceEntryPoint() {
  const nav = document.querySelector('[data-view="integrations"]');
  if (nav && !nav.dataset.mcpUx) {
    nav.dataset.mcpUx = '1';
    const text = [...nav.childNodes].find((candidate) => candidate.nodeType === Node.TEXT_NODE && candidate.textContent.trim());
    if (text) text.textContent = ' MCP & intégrations';
    nav.setAttribute('aria-label', 'MCP et intégrations');
  }
  const view = document.querySelector('#view-integrations');
  const heading = view?.querySelector('.workspace-heading > div');
  if (heading && !heading.dataset.mcpUx) {
    heading.dataset.mcpUx = '1';
    heading.querySelector('h1').textContent = 'MCP & intégrations';
    heading.querySelector('p').textContent = 'Connecter Kex à des services MCP et connecter des clients MCP à Kex depuis un même point d’entrée.';
  }
  const cockpit = document.querySelector('.mcp-cockpit-panel');
  if (!cockpit || document.querySelector('#mcp-home')) return;
  const home = document.createElement('section');
  home.id = 'mcp-home';
  home.className = 'mcp-home-grid';

  const outbound = document.createElement('article');
  outbound.className = 'mcp-home-card';
  outbound.innerHTML = '<span class="panel-kicker">Kex utilise des services</span><h2>Connecter Kex à des services</h2><p>Ajouter et superviser les serveurs MCP externes dont Kex utilise les outils.</p>';
  const outboundMeta = document.createElement('div');
  outboundMeta.className = 'mcp-home-meta';
  outboundMeta.append(chip('Connexions externes'), chip('Outils'), chip('Diagnostics'));
  const outboundAction = button('Gérer les connexions', 'primary');
  outboundAction.addEventListener('click', () => cockpit.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  outbound.append(outboundMeta, outboundAction);

  const inbound = document.createElement('article');
  inbound.className = 'mcp-home-card';
  inbound.innerHTML = '<span class="panel-kicker">Kex expose ses capacités</span><h2>Connecter des clients à Kex</h2><p>Configurer le serveur MCP Kex, vérifier son état et tester les outils qu’il expose.</p>';
  const inboundMeta = document.createElement('div');
  inboundMeta.className = 'mcp-home-meta';
  inboundMeta.append(chip('Serveur Kex'), chip('Catalogue'), chip('Sessions clientes'));
  const inboundAction = button('Configurer le serveur Kex', 'primary');
  inboundAction.addEventListener('click', () => {
    location.hash = '#/settings';
    setTimeout(() => document.querySelector('#settings-mcp-server')?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 120);
  });
  inbound.append(inboundMeta, inboundAction);
  home.append(outbound, inbound);
  cockpit.before(home);
}

function ensureLocationStep() {
  const form = document.querySelector('#add-mcp-form');
  if (!form || form.querySelector('#mcp-location-step')) return;
  const first = form.querySelector('fieldset');
  if (!first) return;
  const fieldset = document.createElement('fieldset');
  fieldset.id = 'mcp-location-step';
  const legend = document.createElement('legend');
  legend.textContent = '1 · Où se trouve le serveur ?';
  const intro = document.createElement('p');
  intro.className = 'hint';
  intro.textContent = 'Choisissez le contexte ; Kex présélectionne le transport et masque les détails techniques courants.';
  const choices = document.createElement('div');
  choices.className = 'mcp-location-choices';
  [
    ['local', 'Sur ce PC', 'Processus local, configuration stdio recommandée.'],
    ['private', 'Réseau privé', 'Serveur HTTP sur LAN, VPN ou réseau interne.'],
    ['internet', 'Depuis Internet', 'Serveur distant : HTTPS et authentification recommandés.'],
  ].forEach(([value, title, detail], index) => {
    const label = document.createElement('label');
    label.className = 'mcp-location-choice';
    const radio = document.createElement('input');
    radio.type = 'radio'; radio.name = 'mcp-location'; radio.value = value; radio.checked = index === 0;
    label.append(radio, Object.assign(document.createElement('strong'), { textContent: title }), Object.assign(document.createElement('span'), { className: 'hint', textContent: detail }));
    choices.append(label);
  });
  const note = document.createElement('p');
  note.id = 'mcp-security-guidance'; note.className = 'hint';
  fieldset.append(legend, intro, choices, note);
  first.before(fieldset);
  const legends = form.querySelectorAll('fieldset > legend');
  if (legends[1]) legends[1].textContent = '2 · Serveur';
  if (legends[2]) legends[2].textContent = '3 · Connexion et sécurité';
  if (legends[3]) legends[3].textContent = '4 · Gouvernance des outils';

  const transport = document.querySelector('#mcp-transport');
  const transportFieldset = transport?.closest('fieldset');
  if (transportFieldset && !transportFieldset.querySelector('.mcp-tech-advanced')) {
    const details = document.createElement('details');
    details.className = 'advanced mcp-tech-advanced';
    const summary = document.createElement('summary');
    summary.textContent = 'Paramètres techniques avancés';
    details.append(summary);
    const endpoint = document.querySelector('#mcp-endpoint');
    const headers = document.querySelector('#mcp-headers');
    [transport, endpoint, headers].forEach((control) => {
      if (!control) return;
      const label = document.querySelector(`label[for="${control.id}"]`);
      if (label) details.append(label);
      details.append(control);
    });
    transportFieldset.append(details);
  }

  function sync(mode) {
    if (!transport) return;
    if (mode === 'local') transport.value = 'STDIO';
    else transport.value = 'HTTP';
    transport.dispatchEvent(new Event('change', { bubbles: true }));
    const noteNode = document.querySelector('#mcp-security-guidance');
    if (noteNode) noteNode.textContent = mode === 'internet'
      ? 'Internet : utilisez une URL HTTPS et un jeton d’accès. Kex testera la connexion avant l’enregistrement.'
      : mode === 'private'
        ? 'Réseau privé : préférez HTTPS ou un réseau de confiance ; l’authentification reste recommandée.'
        : 'Local : aucun service réseau n’est nécessaire avec stdio.';
  }
  fieldset.addEventListener('change', (event) => sync(event.target.value));
  sync('local');
}

function ageText(value) {
  if (!value) return null;
  const time = new Date(value).getTime();
  if (!Number.isFinite(time)) return null;
  const seconds = Math.max(0, Math.round((Date.now() - time) / 1000));
  if (seconds < 60) return `il y a ${seconds} s`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `il y a ${minutes} min`;
  const hours = Math.round(minutes / 60);
  return `il y a ${hours} h`;
}

function annotateFreshness() {
  const cards = document.querySelectorAll('#servers .server');
  cards.forEach((card) => {
    if (card.querySelector('.mcp-source-age')) return;
    const connection = card.querySelector('h3')?.textContent?.trim();
    const server = externalCatalog.get(connection);
    if (!server?.retrievedAt) return;
    const node = document.createElement('p');
    node.className = 'mcp-source-age muted';
    node.dataset.stale = String(Boolean(server.stale || server.cached));
    node.textContent = `${server.stale || server.cached ? 'Catalogue conservé' : 'Catalogue récupéré'} ${ageText(server.retrievedAt) || ''}`.trim();
    if (server.stale) node.textContent += ' · actualisation à vérifier';
    card.querySelector('header')?.after(node);
  });
}

function riskNode(tool) {
  const risk = riskOf(tool);
  const node = document.createElement('div');
  node.className = 'mcp-risk';
  node.dataset.risk = risk.state;
  const badge = document.createElement('span'); badge.className = 'mcp-risk-badge'; badge.textContent = risk.label;
  const detail = document.createElement('span'); detail.textContent = risk.detail;
  node.append(badge, detail);
  return node;
}

function annotateInvocations() {
  document.querySelectorAll('.drawer-invoke').forEach((panel) => {
    if (panel.querySelector('.mcp-risk')) return;
    const title = document.querySelector('#drawer-title')?.textContent || '';
    const name = title.replace(/^Outil MCP ·\s*/, '').trim();
    let tool = null;
    for (const server of externalCatalog.values()) {
      tool = (server.tools || []).find((candidate) => candidate.name === name);
      if (tool) break;
    }
    if (tool) panel.querySelector('p')?.after(riskNode(tool));
  });
  const form = document.querySelector('#mcp-playground-form');
  if (form) {
    let node = form.querySelector('.mcp-risk');
    const target = document.querySelector('#mcp-playground-target')?.value;
    const tool = exposedCatalog.get(target);
    if (tool) {
      const next = riskNode(tool);
      if (node) node.replaceWith(next);
      else document.querySelector('#mcp-playground-target')?.closest('label')?.after(next);
    } else node?.remove();
  }
}

function actionableFailure(status, error = '') {
  if (status === 401 || status === 403) return ['Accès refusé', 'L’authentification ou les permissions ne permettent pas cette opération.', 'Vérifier l’accès'];
  if (status === 404) return ['Service introuvable', 'La connexion ou l’outil n’est plus disponible avec cette configuration.', 'Actualiser'];
  if (status >= 500) return ['Service indisponible', 'Le serveur MCP a répondu en erreur. Les données affichées peuvent provenir du dernier relevé.', 'Réessayer'];
  return ['Connexion impossible', error || 'Le serveur MCP ne répond pas avec la configuration actuelle.', 'Réessayer'];
}

function renderFailure(detail) {
  const host = document.querySelector('#mcp-playground-form') || document.querySelector('#servers');
  if (!host) return;
  host.querySelector('.mcp-actionable-error')?.remove();
  const [title, message, action] = actionableFailure(detail.status || 0, detail.message);
  const box = document.createElement('div'); box.className = 'mcp-actionable-error'; box.setAttribute('role', 'alert');
  const strong = document.createElement('strong'); strong.textContent = title;
  const span = document.createElement('span'); span.textContent = message;
  const retry = button(action, 'ghost');
  retry.addEventListener('click', () => {
    box.remove();
    if (location.hash.startsWith('#/integrations')) document.querySelector('#refresh-tools')?.click();
    else document.querySelector('[data-mcp-tab="catalog"]')?.click();
  });
  box.append(strong, span, retry); host.prepend(box);
}

async function confirmMutation(tool) {
  const risk = riskOf(tool);
  if (!['WRITE', 'ADMIN'].includes(risk.level)) return true;
  const dialog = document.querySelector('#confirm');
  if (!dialog?.showModal) return window.confirm(`${risk.label} — ${tool.name}\n${risk.detail}\n\nContinuer ?`);
  if (dialog.open) return false;
  const title = dialog.querySelector('#confirm-title');
  const body = dialog.querySelector('#confirm-body');
  const accept = dialog.querySelector('#confirm-accept');
  title.textContent = risk.level === 'ADMIN' ? 'Confirmer une opération d’administration' : 'Confirmer une opération d’écriture';
  body.replaceChildren();
  const dl = document.createElement('dl'); dl.className = 'definition-list';
  [['Outil', tool.name || 'Sans nom'], ['Niveau', risk.label], ['Effet', risk.detail]].forEach(([label, value]) => {
    const dt = document.createElement('dt'); dt.textContent = label;
    const dd = document.createElement('dd'); dd.textContent = value;
    dl.append(dt, dd);
  });
  body.append(dl);
  accept.textContent = risk.level === 'ADMIN' ? 'Continuer malgré le risque' : 'Exécuter l’écriture';
  return new Promise((resolve) => {
    const done = () => { dialog.removeEventListener('close', done); resolve(dialog.returnValue === 'confirm'); };
    dialog.addEventListener('close', done);
    dialog.showModal();
  });
}

function decodeExternalTool(url) {
  const match = String(url).match(/\/api\/agent\/mcp\/servers\/([^/]+)\/tools\/([^/?#]+)/);
  if (!match) return null;
  const connection = decodeURIComponent(match[1]);
  const name = decodeURIComponent(match[2]);
  return (externalCatalog.get(connection)?.tools || []).find((tool) => tool.name === name) || { name };
}

async function inspectResponse(url, response) {
  if (!response.ok) {
    if (String(url).includes('/api/agent/mcp')) dispatchEvent(new CustomEvent('kex:mcp-failure', { detail: { status: response.status } }));
    return;
  }
  try {
    if (String(url).endsWith('/api/agent/mcp/servers')) {
      const data = await response.clone().json();
      (data || []).forEach((server) => externalCatalog.set(server.connection, server));
      queueMicrotask(() => { annotateFreshness(); annotateInvocations(); });
    }
  } catch { /* réponse non JSON : aucune amélioration à appliquer */ }
}

window.fetch = async function mcpUxFetch(input, init = {}) {
  const url = typeof input === 'string' ? input : input?.url || '';
  const method = String(init.method || (typeof input !== 'string' ? input?.method : 'GET') || 'GET').toUpperCase();
  const externalTool = method === 'POST' ? decodeExternalTool(url) : null;
  if (externalTool && !(await confirmMutation(externalTool))) throw new DOMException('Exécution annulée par l’utilisateur', 'AbortError');

  let rpcBody = null;
  if (method === 'POST' && String(url).includes('/api/agent/mcp-server') && typeof init.body === 'string') {
    try { rpcBody = JSON.parse(init.body); } catch { /* corps non JSON */ }
    if (rpcBody?.method === 'tools/call') {
      const tool = exposedCatalog.get(rpcBody.params?.name) || { name: rpcBody.params?.name };
      if (!(await confirmMutation(tool))) throw new DOMException('Exécution annulée par l’utilisateur', 'AbortError');
    }
  }

  try {
    const response = await originalFetch(input, init);
    if (rpcBody?.method === 'tools/list' && response.ok) {
      try {
        const payload = await response.clone().json();
        (payload?.result?.tools || []).forEach((tool) => exposedCatalog.set(tool.name, tool));
        queueMicrotask(annotateInvocations);
      } catch { /* réponse non JSON */ }
    }
    inspectResponse(url, response);
    return response;
  } catch (error) {
    if (String(url).includes('/api/agent/mcp') && error?.name !== 'AbortError') {
      dispatchEvent(new CustomEvent('kex:mcp-failure', { detail: { status: 0, message: error.message } }));
    }
    throw error;
  }
};

function enhance() {
  if (enhancing) return;
  enhancing = true;
  try {
    style();
    enhanceEntryPoint();
    ensureLocationStep();
    annotateFreshness();
    annotateInvocations();
  } finally { enhancing = false; }
}

addEventListener('kex:mcp-failure', (event) => renderFailure(event.detail || {}));
addEventListener('hashchange', () => setTimeout(enhance, 0));
document.addEventListener('change', (event) => {
  if (event.target?.id === 'mcp-playground-target' || event.target?.id === 'mcp-playground-operation') queueMicrotask(annotateInvocations);
});
new MutationObserver(() => queueMicrotask(enhance)).observe(document.documentElement, { childList: true, subtree: true });
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', enhance, { once: true });
else enhance();
