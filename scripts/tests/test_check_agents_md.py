import importlib.util
import pathlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "check_agents_md", pathlib.Path(__file__).resolve().parent.parent / "check-agents-md.py")
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)

PIEGES = """# Pièges

## Sommaire

- [Démarrage et configuration](#démarrage-et-configuration) — 2
- [Tests](#tests) — 1

## Démarrage et configuration

- **Premier piège.** Raison.

- **Second piège.** Raison.

## Tests

- **Troisième piège.** Raison.
"""


class CheckAgentsMdTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.tmp.name)
        (self.root / "docs").mkdir()
        (self.root / "docs" / "PIEGES.md").write_text(PIEGES, encoding="utf-8")
        (self.root / "CLAUDE.md").write_text("# Notes\n\n@AGENTS.md\n", encoding="utf-8")
        self.agents("Voir [les pièges](docs/PIEGES.md#démarrage-et-configuration).\n")

    def tearDown(self):
        self.tmp.cleanup()

    def agents(self, text):
        (self.root / "AGENTS.md").write_text("# Carte\n\n" + text, encoding="utf-8")

    def test_une_carte_courte_aux_liens_valides_passe(self):
        self.assertEqual(check.problems(self.root), [])

    def test_l_ancre_suit_le_calcul_de_github_accents_compris(self):
        self.assertEqual(check.slug("Sécurité, API et canaux"), "sécurité-api-et-canaux")

    def test_un_lien_ou_une_ancre_morte_est_signale(self):
        self.agents("[a](docs/ABSENT.md) [b](docs/PIEGES.md#demarrage-et-configuration) [c](https://exemple.org)\n"
                    "```\n[pas un lien](nulle/part.md)\n```\n")
        self.assertEqual(check.problems(self.root), [
            "AGENTS.md : lien vers docs/ABSENT.md, fichier introuvable",
            "AGENTS.md : lien vers docs/PIEGES.md#demarrage-et-configuration, ancre introuvable",
        ])

    def test_une_carte_qui_regrossit_ou_un_claude_md_qui_ne_l_importe_plus_est_signale(self):
        self.agents("ligne\n" * (check.MAX_LINES + 1))
        (self.root / "CLAUDE.md").write_text("# Notes\n" + "piège\n" * 30, encoding="utf-8")
        found = check.problems(self.root)
        self.assertTrue(any("AGENTS.md fait" in problem for problem in found), found)
        self.assertIn("CLAUDE.md n'importe pas AGENTS.md (ligne « @AGENTS.md »)", found)
        self.assertTrue(any("second manuel" in problem for problem in found), found)

    def test_un_sommaire_faux_ou_une_entree_en_double_est_signale(self):
        (self.root / "docs" / "PIEGES.md").write_text(
            PIEGES.replace("— 1", "— 2").replace("**Troisième piège.**", "**Premier piège.**"), encoding="utf-8")
        self.assertEqual(check.problems(self.root), [
            "docs/PIEGES.md : « Tests » annonce 2 entrées, en compte 1",
            "docs/PIEGES.md : entrée en double — « Premier piège. » (Démarrage et configuration, Tests)",
        ])

    def test_la_carte_du_depot_tient(self):
        self.assertEqual(check.problems(), [])


if __name__ == "__main__":
    unittest.main()
