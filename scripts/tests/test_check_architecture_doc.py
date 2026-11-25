import importlib.util
import pathlib
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "check_architecture_doc", pathlib.Path(__file__).resolve().parent.parent / "check-architecture-doc.py")
check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check)


def project(root: pathlib.Path) -> pathlib.Path:
    """Un dépôt miniature : une classe, une migration, un module de console, une configuration."""
    java = root / "src" / "main" / "java" / "com" / "kex"
    java.mkdir(parents=True)
    (java / "TaskService.java").write_text(
        'public class TaskService { record Step() {} }\n'
        '@ConditionalOnProperty(prefix = "kex.agent.gmail", name = "enabled")\n'
        'class Tools { String tool = "kex_status"; }\n', encoding="utf-8")
    migration = root / "src" / "main" / "resources" / "db" / "migration"
    migration.mkdir(parents=True)
    (migration / "V4__durable_tasks.sql").write_text("CREATE TABLE IF NOT EXISTS kex_task (id TEXT);", encoding="utf-8")
    assets = root / "src" / "main" / "resources" / "static" / "assets"
    assets.mkdir(parents=True)
    (assets / "core.js").write_text("", encoding="utf-8")
    (root / "src" / "main" / "resources" / "application.yml").write_text(
        "kex:\n  agent:\n    tasks:\n      enabled: false  # commentaire\n", encoding="utf-8")
    return root


class CheckArchitectureDocTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = project(pathlib.Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def test_un_document_fidele_au_code_passe(self):
        doc = ("`TaskService` et son `Step`, la table `kex_task` (`V4__durable_tasks`), `core.js`, "
               "`kex.agent.tasks.enabled`, `kex.agent.tasks`, `kex.agent.gmail.enabled`, l'outil `kex_status`, "
               "et `ChatClient` de Spring AI.")
        self.assertEqual(check.missing(doc, self.root), [])

    def test_chaque_nom_disparu_est_signale(self):
        doc = ("`TaskServise`, `kex_tasks`, `V5__durable_tasks`, `cores.js`, `kex.agent.task.enabled`")
        self.assertEqual(check.missing(doc, self.root), [
            "type introuvable : TaskServise",
            "migration introuvable : V5__durable_tasks",
            "module de console introuvable : cores.js",
            "propriété introuvable : kex.agent.task.enabled",
            "table ou outil MCP introuvable : kex_tasks",
        ])

    def test_le_code_cite_entre_accents_sans_forme_reconnue_est_ignore(self):
        self.assertEqual(check.missing("`./mvnw verify`, `GET /api/agent/whoami`, `OK`", self.root), [])

    def test_le_document_du_depot_est_fidele(self):
        self.assertEqual(check.missing(check.DOC.read_text(encoding="utf-8")), [])


if __name__ == "__main__":
    unittest.main()
