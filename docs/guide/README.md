# Guide pédagogique

[`Kex-Agent-Guide-pedagogique.docx`](Kex-Agent-Guide-pedagogique.docx) explique le fonctionnement
de l'agent à un lecteur sans connaissance préalable de l'IA : ses trois rôles, le trajet d'une
question, le cycle de supervision, les décisions sous autonomie gouvernée, les tâches durables,
la mémoire et les compétences. Il détaille aussi treize algorithmes, chacun avec son objectif,
son principe et, le plus souvent, un exemple chiffré, puis les gains et les contraintes.

C'est un support de formation. La référence technique reste
[`ARCHITECTURE-LOGICIELLE.md`](../ARCHITECTURE-LOGICIELLE.md). Les valeurs citées sont celles de
`application.yml` au 25 novembre 2026 : un seuil modifié depuis doit être corrigé dans
`source/build.cjs`, puis le document régénéré.

## Régénérer

Playwright et la bibliothèque `docx` sont hors du projet, comme pour les tests navigateur :

```bash
node docs/guide/source/figs.cjs /tmp/guide            # rend les quatre schémas en PNG
node docs/guide/source/build.cjs docs/guide/Kex-Agent-Guide-pedagogique.docx /tmp/guide
```
