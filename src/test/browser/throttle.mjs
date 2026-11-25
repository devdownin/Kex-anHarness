// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Ralentit le processeur du navigateur, pour que les courses entre événements se produisent ici et
// non au hasard d'un exécuteur chargé.
//
//   KEX_CPU_THROTTLE=8 node src/test/browser/console.mjs
//
// Deux défauts n'apparaissaient qu'ainsi : deux `page.goto` de suite dont les `hashchange` lisent
// tous deux le hash final (aucun rechargement), et des poignées d'éléments détachées par un
// re-rendu. Ni l'un ni l'autre ne se voyait à vitesse normale, ni en local, ni souvent en CI.

/**
 * Le facteur demandé, validé. À appeler une fois au démarrage d'un script : une valeur invalide fait
 * alors échouer le script entier, au lieu de faire échouer chacun de ses cas avec le même message.
 *
 * @param rate 1 ou absent : aucun ralentissement. ×N : le processeur travaille N fois moins vite.
 */
export function throttleFactor(rate = process.env.KEX_CPU_THROTTLE) {
  if (rate === undefined || rate === '') return 1;
  const factor = Number(rate);
  // Une valeur illisible n'est pas « pas de ralentissement » : elle ferait croire à un test sous
  // charge qui n'en est pas un, exactement le faux vert que ce réglage existe pour éviter.
  if (!Number.isFinite(factor) || factor < 1) {
    throw new Error(`KEX_CPU_THROTTLE doit être un nombre supérieur ou égal à 1, reçu « ${rate} »`);
  }
  return factor;
}

/** @return le facteur appliqué, pour le journal du script */
export async function throttle(context, page, rate = process.env.KEX_CPU_THROTTLE) {
  const factor = throttleFactor(rate);
  if (factor === 1) return 1;
  const session = await context.newCDPSession(page);
  await session.send('Emulation.setCPUThrottlingRate', { rate: factor });
  return factor;
}
