import importlib.util
import pathlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "check_guide_values", pathlib.Path(__file__).resolve().parent.parent / "check-guide-values.py")
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)

YAML = """kex:
  agent:
    request-timeout: 120s
    tasks:
      enabled: ${KEX_AGENT_TASKS_ENABLED:false}  # commentaire
    supervision:
      confidence-threshold: 0.85
"""

JAVA = """@ConfigurationProperties("kex.agent.tools")
public record ToolControlProperties(
        @DefaultValue("false") boolean selectionEnabled,
        @DefaultValue("12") @Positive int maxSelectedTools) {}
"""


class CheckGuideValuesTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.java = pathlib.Path(self.tmp.name)
        (self.java / "ToolControlProperties.java").write_text(JAVA, encoding="utf-8")
        self.config = check.yaml_values(YAML)

    def tearDown(self):
        self.tmp.cleanup()

    def test_la_configuration_se_lit_par_chemin_avec_le_defaut_des_variables(self):
        self.assertEqual(self.config["kex.agent.request-timeout"], "120s")
        self.assertEqual(self.config["kex.agent.tasks.enabled"], "false")
        self.assertEqual(self.config["kex.agent.supervision.confidence-threshold"], "0.85")

    def test_une_propriete_absente_du_yaml_se_lit_dans_son_defaut_java(self):
        self.assertEqual(check.java_default("kex.agent.tools.max-selected-tools", self.java), "12")
        self.assertIsNone(check.java_default("kex.agent.tools.unknown", self.java))

    def test_un_guide_conforme_passe(self):
        values = {"timeout": {"property": "kex.agent.request-timeout", "value": "120s", "text": "120 secondes"},
                  "tools": {"property": "kex.agent.tools.max-selected-tools", "value": "12", "text": "12"}}
        self.assertEqual(check.problems(values, self.config, self.java, "120 secondes, les 12 meilleurs"), [])

    def test_valeur_changee_document_perime_et_gabarit_oublie_sont_signales(self):
        values = {"confidence": {"property": "kex.agent.supervision.confidence-threshold", "value": "0.9", "text": "0,9"},
                  "ghost": {"property": "kex.agent.ghost", "value": "1", "text": "1"}}
        found = check.problems(values, self.config, self.java, "plancher {{confidence}}")
        self.assertEqual(found, [
            "confidence : kex.agent.supervision.confidence-threshold vaut 0.85 dans la configuration, 0.9 dans values.json",
            "confidence : « 0,9 » absent du document — le régénérer",
            "ghost : propriété introuvable — kex.agent.ghost",
            "ghost : « 1 » absent du document — le régénérer",
            "le document contient un {{…}} non remplacé",
        ])


if __name__ == "__main__":
    unittest.main()
