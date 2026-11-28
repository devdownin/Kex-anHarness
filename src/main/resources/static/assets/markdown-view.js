// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Rend l'arbre de `markdown.js` en éléments du DOM : jamais de `innerHTML`, un nœud de texte pour
// tout ce qui vient du modèle. Partagé par le chat de la console et l'espace utilisateur (`/app`),
// qui rendaient auparavant chacun la réponse à sa façon — l'un en blocs, l'autre en texte brut.

import { parseBlocks } from './markdown.js';

const make = (tag, className, text) => {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
};

/**
 * `options.inline(texte, parent)` reçoit chaque suite de texte ordinaire à la place d'un simple nœud
 * de texte : l'espace utilisateur y reconnaît les renvois `[source:…]` sans que le rendu ait à les
 * connaître. `options.literal(texte)` passe sur ce qui s'affiche tel quel — le code — pour y remettre
 * ce que l'appelant avait masqué.
 */
function appendInline(nodes, parent, options) {
  for (const node of nodes) {
    if (node.t === 'text') {
      if (options.inline) options.inline(node.v, parent); else parent.append(document.createTextNode(node.v));
    } else if (node.t === 'code') {
      parent.append(make('code', 'inline-code', options.literal(node.v)));
    } else if (node.t === 'link') {
      const link = make('a', 'chat-link');
      link.href = node.href;
      // Un lien sort de l'application : il ne garde ni l'onglet ni la référence de la page qui l'ouvre.
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      appendInline(node.c, link, options);
      parent.append(link);
    } else {
      const element = make(node.t === 'strong' ? 'strong' : node.t === 'del' ? 'del' : 'em');
      appendInline(node.c, element, options);
      parent.append(element);
    }
  }
}

export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // Contexte non sécurisé ou permission refusée : on retombe sur la sélection, qui marche partout.
    const area = make('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.cssText = 'position:fixed;opacity:0;pointer-events:none';
    document.body.append(area);
    area.select();
    let done = false;
    try { done = document.execCommand('copy'); } catch { done = false; }
    area.remove();
    return done;
  }
}

function codeBlock(block, options) {
  const wrap = make('div', block.open ? 'code-wrap streaming' : 'code-wrap');
  const head = make('div', 'code-head');
  head.append(make('span', 'code-lang', block.lang || 'texte'));
  // Pas de bouton tant que le bloc arrive : il copierait un morceau de code, et son libellé changerait
  // sous le pointeur à chaque jeton.
  if (!block.open) {
    const copy = make('button', 'code-copy', 'Copier');
    copy.type = 'button';
    copy.setAttribute('aria-label', `Copier le bloc de code${block.lang ? ` ${block.lang}` : ''}`);
    copy.addEventListener('click', async () => {
      const ok = await copyText(options.literal(block.text));
      copy.textContent = ok ? 'Copié' : 'Copie impossible';
      setTimeout(() => { copy.textContent = 'Copier'; }, 1600);
    });
    head.append(copy);
  }
  const pre = make('pre', 'code-block');
  pre.append(make('code', undefined, options.literal(block.text)));
  wrap.append(head, pre);
  return wrap;
}

function table(block, options) {
  // Enveloppé pour défiler seul : un tableau large n'élargit ni la bulle ni la page.
  const wrap = make('div', 'chat-table-wrap');
  const element = make('table', 'chat-table');
  const cell = (tag, nodes, i) => {
    const node = make(tag);
    if (tag === 'th') node.scope = 'col';
    if (block.align[i]) node.style.textAlign = block.align[i];
    appendInline(nodes, node, options);
    return node;
  };
  const head = make('thead');
  const headRow = make('tr');
  block.headers.forEach((nodes, i) => headRow.append(cell('th', nodes, i)));
  head.append(headRow);
  const body = make('tbody');
  for (const row of block.rows) {
    const tr = make('tr');
    row.forEach((nodes, i) => tr.append(cell('td', nodes, i)));
    body.append(tr);
  }
  element.append(head, body);
  wrap.append(element);
  return wrap;
}

function list(block, options) {
  const element = make(block.ordered ? 'ol' : 'ul');
  if (block.ordered && block.start !== 1) element.start = block.start;
  for (const item of block.items) {
    const isTask = typeof item.checked === 'boolean';
    const li = make('li', isTask ? 'task-item' : undefined);
    if (isTask) {
      const mark = make('span', item.checked ? 'task-mark checked' : 'task-mark', item.checked ? '☑' : '☐');
      mark.setAttribute('role', 'img');
      mark.setAttribute('aria-label', item.checked ? 'Fait' : 'À faire');
      li.append(mark, document.createTextNode(' '));
    }
    appendInline(item.inline, li, options);
    item.children.forEach((child) => li.append(list(child, options)));
    element.append(li);
  }
  return element;
}

function renderBlock(block, options) {
  switch (block.type) {
    case 'heading': {
      // h1 de la réponse n'est pas le h1 de la page : trois tailles, du h3 au h5.
      const element = make(block.level === 1 ? 'h3' : block.level === 2 ? 'h4' : 'h5', 'chat-header');
      appendInline(block.inline, element, options);
      return element;
    }
    case 'paragraph': {
      const p = make('p', 'chat-paragraph');
      block.lines.forEach((line, index) => {
        if (index > 0) p.append(make('br'));
        appendInline(line, p, options);
      });
      return p;
    }
    case 'list': return list(block, options);
    case 'quote': {
      const quote = make('blockquote', 'chat-quote');
      block.blocks.forEach((child) => quote.append(renderBlock(child, options)));
      return quote;
    }
    case 'rule': return make('hr', 'chat-rule');
    case 'code': return codeBlock(block, options);
    case 'table': return table(block, options);
    default: return make('p');
  }
}

export function renderMarkdown(text, { inline, literal = (value) => value } = {}) {
  const container = make('div', 'markdown-body');
  if (!text) return container;
  const options = { inline, literal };
  for (const block of parseBlocks(text)) container.append(renderBlock(block, options));
  return container;
}
