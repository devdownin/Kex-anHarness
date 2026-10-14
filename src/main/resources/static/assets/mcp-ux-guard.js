// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Garde-fous pour l'enrichissement MCP dynamique : évite une boucle de rendu
// sur les badges de risque et garde le contexte du wizard aligné sur la
// configuration réellement chargée lors de l'ouverture du dialogue.

const originalReplaceWith = Element.prototype.replaceWith;
Element.prototype.replaceWith = function guardedReplaceWith(...nodes) {
  if (this.classList?.contains('mcp-risk') && nodes.length === 1) {
    const [next] = nodes;
    if (next instanceof Element && next.classList.contains('mcp-risk')
        && next.textContent === this.textContent && next.dataset.risk === this.dataset.risk) return;
  }
  return originalReplaceWith.apply(this, nodes);
};

function wizardMode() {
  const transport = document.querySelector('#mcp-transport')?.value;
  if (transport === 'STDIO') return 'local';
  const raw = document.querySelector('#mcp-url')?.value?.trim();
  if (!raw) return 'private';
  try {
    const host = new URL(raw).hostname.toLowerCase();
    const privateHost = host === 'localhost' || host === '127.0.0.1' || host.endsWith('.local')
      || /^10\./.test(host) || /^192\.168\./.test(host)
      || /^172\.(1[6-9]|2\d|3[01])\./.test(host);
    return privateHost ? 'private' : 'internet';
  } catch {
    return 'private';
  }
}

function syncWizardContext() {
  const step = document.querySelector('#mcp-location-step');
  if (!step) return;
  const mode = wizardMode();
  const radio = step.querySelector(`input[name="mcp-location"][value="${mode}"]`);
  if (radio) radio.checked = true;
  const note = document.querySelector('#mcp-security-guidance');
  if (!note) return;
  note.textContent = mode === 'internet'
    ? 'Internet : utilisez une URL HTTPS et un jeton d’accès. Kex testera la connexion avant l’enregistrement.'
    : mode === 'private'
      ? 'Réseau privé : préférez HTTPS ou un réseau de confiance ; l’authentification reste recommandée.'
      : 'Local : aucun service réseau n’est nécessaire avec stdio.';
}

function bindWizardGuard() {
  const dialog = document.querySelector('#add-mcp-server');
  if (!dialog || dialog.dataset.mcpGuard) return;
  dialog.dataset.mcpGuard = '1';
  new MutationObserver(() => {
    if (dialog.open) queueMicrotask(syncWizardContext);
  }).observe(dialog, { attributes: true, attributeFilter: ['open'] });
  document.querySelector('#mcp-transport')?.addEventListener('change', () => queueMicrotask(syncWizardContext));
  document.querySelector('#mcp-url')?.addEventListener('change', () => queueMicrotask(syncWizardContext));
  document.querySelector('#mcp-template')?.addEventListener('change', () => setTimeout(syncWizardContext, 0));
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bindWizardGuard, { once: true });
else bindWizardGuard();
