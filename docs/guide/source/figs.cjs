// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Kex Agent AI Contributors

// Rend les quatre schémas de figs.html en PNG (×2), dans le dossier donné.
//   node figs.cjs <dossier>
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ deviceScaleFactor: 2 });
  await p.goto('file://' + require('path').join(__dirname, 'figs.html'));
  for (const id of ['f1','f2','f3','f4']) await p.locator('#' + id).screenshot({ path: `${process.argv[2]}/${id}.png` });
  await b.close();
})();
