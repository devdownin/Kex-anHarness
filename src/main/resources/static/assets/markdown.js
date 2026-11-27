// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Lecture du Markdown des réponses de l'agent, sans DOM : un texte entre, un arbre sort, ce qui se
// teste sous Node et se rend ailleurs (`markdown-view.js`). Deux règles tiennent tout le reste.
//
//  * Rien n'est jamais du HTML. Le rendu ne fabrique que des nœuds de texte et des éléments connus,
//    et un lien n'est un lien que vers http(s) ou mailto : une réponse de modèle recopie ce que les
//    messages Kafka lus contiennent, c'est-à-dire ce qu'un tiers a écrit.
//  * Une réponse en cours de flux est un texte tronqué. Un marqueur qui n'a pas encore son
//    compagnon reste du texte — jamais une page de gras jusqu'à la fin — et un bloc de code non fermé
//    est rendu ouvert au lieu de disparaître.

import { parseTable } from './markdown-table.js';

const FENCE = /^ {0,3}(`{3,}|~{3,})\s*([A-Za-z0-9_+#.-]*)[^`]*$/;
const HEADING = /^ {0,3}(#{1,6})\s+(.*?)(?:\s+#+)?\s*$/;
const RULE = /^ {0,3}([-*_])(?:\s*\1){2,}\s*$/;
const QUOTE = /^ {0,3}>\s?(.*)$/;
const ITEM = /^(\s*)([-*+•]|\d{1,9}[.)])\s+(.*)$/;

/** Un lien n'est cliquable que vers http(s) ou mailto, sans espace ni caractère de contrôle. */
export function safeHref(raw) {
  const href = String(raw ?? '').trim();
  // eslint-disable-next-line no-control-regex
  if (!href || /[\s\u0000-\u001f\u007f]/.test(href)) return null;
  if (/^https?:\/\/[^/?#]+/i.test(href) || /^mailto:[^@\s]+@[^@\s]+$/i.test(href)) return href;
  return null;
}

// -- Inline ---------------------------------------------------------------------------------

const ESCAPABLE = /[\\`*_{}[\]()#+\-.!|~>]/;
const isWord = (ch) => ch !== undefined && /[\p{L}\p{N}]/u.test(ch);

function closingRun(text, from, marker) {
  // Cherche le prochain `marker` qui n'est pas échappé ; un délimiteur de code ne se laisse pas enjamber.
  for (let i = from; i < text.length; i += 1) {
    if (text[i] === '\\') { i += 1; continue; }
    if (text.startsWith(marker, i)) return i;
  }
  return -1;
}

/** Un marqueur d'emphase ouvre ici, ou rend null : le texte qui suit reste alors du texte. */
function delimited(text, i) {
  for (const [marker, kind] of [['**', 'strong'], ['__', 'strong'], ['~~', 'del'], ['*', 'em'], ['_', 'em']]) {
    if (!text.startsWith(marker, i)) continue;
    const width = marker.length;
    // « * » ne s'ouvre pas devant un autre « * » : c'est « ** », déjà essayé plus haut.
    if (width === 1 && text[i + 1] === marker) continue;
    const end = closingRun(text, i + width, marker);
    if (end <= i + width) continue;
    const inner = text.slice(i + width, end);
    if (inner.trim() !== inner) continue; // un délimiteur collé à une espace n'emphatise rien
    // « snake__case » et « a_b_c » : un souligné au milieu d'un mot n'ouvre ni ne ferme.
    if (marker[0] === '_' && (isWord(text[i - 1]) || isWord(text[end + width]))) continue;
    return { node: { t: kind, c: parseInline(inner) }, next: end + width };
  }
  return null;
}

export function parseInline(text) {
  const out = [];
  let buffer = '';
  const flush = () => { if (buffer) { out.push({ t: 'text', v: buffer }); buffer = ''; } };
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '\\' && i + 1 < text.length && ESCAPABLE.test(text[i + 1])) { buffer += text[i + 1]; i += 2; continue; }

    if (ch === '`') {
      let run = 1;
      while (text[i + run] === '`') run += 1;
      const marker = '`'.repeat(run);
      const end = text.indexOf(marker, i + run);
      if (end > i + run) {
        flush();
        // Le code garde ses espaces ; une espace de chaque côté, quand il borde un backtick, se retire.
        let code = text.slice(i + run, end);
        if (/^ .* $/s.test(code) && code.trim()) code = code.slice(1, -1);
        out.push({ t: 'code', v: code });
        i = end + run;
        continue;
      }
      buffer += marker; i += run; continue;
    }

    if (ch === '[') {
      const close = closingRun(text, i + 1, ']');
      if (close > i + 1 && text[close + 1] === '(') {
        const end = closingRun(text, close + 2, ')');
        const href = end > 0 ? safeHref(text.slice(close + 2, end).split(/\s+/)[0]) : null;
        if (href) {
          flush();
          out.push({ t: 'link', href, c: parseInline(text.slice(i + 1, close)) });
          i = end + 1;
          continue;
        }
      }
    }

    if (ch === '*' || ch === '_' || ch === '~') {
      const found = delimited(text, i);
      if (found) { flush(); out.push(found.node); i = found.next; continue; }
    }
    buffer += ch; i += 1;
  }
  flush();
  return out;
}

// -- Blocs ----------------------------------------------------------------------------------

const startsBlock = (line, next) => FENCE.test(line) || HEADING.test(line) || RULE.test(line) || QUOTE.test(line)
  || ITEM.test(line) || (next !== undefined && parseTable([line, next]) !== null);

function newList(ordered, marker) {
  return { type: 'list', ordered, start: ordered ? parseInt(marker, 10) : 1, items: [] };
}

/**
 * Les lignes d'une liste, en arbre. Une pile garde, par niveau, l'indentation où il s'est ouvert :
 * une ligne plus indentée ouvre une sous-liste de l'item précédent, une ligne moins indentée referme
 * les niveaux qu'elle dépasse, et un autre genre de marqueur au même niveau ouvre une nouvelle liste
 * — « 1. » après « - » n'est pas le même objet, comme en CommonMark.
 */
function buildLists(lines) {
  const result = [];
  const stack = [];
  const lastItem = (list) => list.items[list.items.length - 1];
  for (const line of lines) {
    const match = ITEM.exec(line);
    if (!match) {
      // Suite de l'item : une ligne indentée sans marqueur prolonge le dernier.
      if (line.trim() && stack.length) {
        const item = lastItem(stack[stack.length - 1].list);
        if (item) item.text = `${item.text} ${line.trim()}`;
      }
      continue;
    }
    const indent = match[1].replace(/\t/g, '    ').length;
    const ordered = /\d/.test(match[2]);
    while (stack.length && indent < stack[stack.length - 1].indent) stack.pop();
    if (!stack.length) {
      const list = newList(ordered, match[2]);
      result.push(list);
      stack.push({ indent, list });
    } else if (indent > stack[stack.length - 1].indent && lastItem(stack[stack.length - 1].list)) {
      const list = newList(ordered, match[2]);
      lastItem(stack[stack.length - 1].list).children.push(list);
      stack.push({ indent, list });
    } else if (stack[stack.length - 1].list.ordered !== ordered) {
      const list = newList(ordered, match[2]);
      stack.pop();
      if (stack.length) lastItem(stack[stack.length - 1].list).children.push(list); else result.push(list);
      stack.push({ indent, list });
    }
    stack[stack.length - 1].list.items.push({ text: match[3], children: [] });
  }
  return result;
}

function listBlock(list) {
  return { type: 'list', ordered: list.ordered, start: list.start,
    items: list.items.map((item) => ({ inline: parseInline(item.text), children: item.children.map(listBlock) })) };
}

export function parseBlocks(text) {
  const lines = String(text ?? '').replace(/\r\n?/g, '\n').split('\n');
  const blocks = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) { i += 1; continue; }

    const fence = FENCE.exec(line);
    if (fence) {
      const marker = fence[1][0].repeat(fence[1].length);
      const body = [];
      let j = i + 1;
      while (j < lines.length && !new RegExp(`^ {0,3}${marker[0]}{${marker.length},}\\s*$`).test(lines[j])) { body.push(lines[j]); j += 1; }
      // Pas de clôture : la réponse est peut-être encore en route, le bloc se rend ouvert.
      blocks.push({ type: 'code', lang: fence[2] || '', text: body.join('\n'), open: j >= lines.length });
      i = j + 1;
      continue;
    }

    const heading = HEADING.exec(line);
    if (heading) { blocks.push({ type: 'heading', level: heading[1].length, inline: parseInline(heading[2]) }); i += 1; continue; }

    if (RULE.test(line)) { blocks.push({ type: 'rule' }); i += 1; continue; }

    if (QUOTE.test(line)) {
      const inner = [];
      while (i < lines.length && QUOTE.test(lines[i])) { inner.push(QUOTE.exec(lines[i])[1]); i += 1; }
      blocks.push({ type: 'quote', blocks: parseBlocks(inner.join('\n')) });
      continue;
    }

    if (i + 1 < lines.length && parseTable([line, lines[i + 1]])) {
      let end = i + 2;
      while (end < lines.length && lines[end].trim() && lines[end].includes('|')) end += 1;
      const table = parseTable(lines.slice(i, end));
      blocks.push({ type: 'table', align: table.align, headers: table.headers.map(parseInline),
        rows: table.rows.map((row) => row.map(parseInline)) });
      i = end;
      continue;
    }

    if (ITEM.test(line)) {
      const group = [];
      while (i < lines.length) {
        const current = lines[i];
        if (ITEM.test(current)) { group.push(current); i += 1; continue; }
        // Une ligne vide ne referme la liste que si la suivante n'en est pas la suite.
        if (!current.trim() && i + 1 < lines.length && ITEM.test(lines[i + 1])) { i += 1; continue; }
        if (current.trim() && /^\s{2,}\S/.test(current) && !startsBlock(current)) { group.push(current); i += 1; continue; }
        break;
      }
      blocks.push(...buildLists(group).map(listBlock));
      continue;
    }

    const paragraph = [];
    while (i < lines.length && lines[i].trim() && (paragraph.length === 0 || !startsBlock(lines[i], lines[i + 1]))) {
      paragraph.push(lines[i].trim());
      i += 1;
    }
    blocks.push({ type: 'paragraph', lines: paragraph.map(parseInline) });
  }
  return blocks;
}
