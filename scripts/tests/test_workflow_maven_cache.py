"""Le cache Maven de chaque workflow porte aussi la distribution du wrapper, sous les mêmes chemins.

Sans `~/.m2/wrapper`, chaque job retéléchargeait Maven par `wget`, sans réessai : un hoquet de
repo.maven.apache.org suffisait à faire échouer `browser` en douze secondes. Et `actions/cache`
range les chemins dans la version de la clé : un job qui en liste d'autres que l'écrivain ne
restaure rien, sans le dire.
"""

import pathlib
import re
import unittest

WORKFLOWS = pathlib.Path(__file__).resolve().parents[2] / ".github" / "workflows"
EXPECTED = ["~/.m2/repository", "~/.m2/wrapper"]

# Un `path:` (une ligne, ou un bloc littéral dont les lignes sont plus indentées) immédiatement
# suivi, au même niveau, de la clé `maven-`.
CACHE = re.compile(
    r"^(?P<indent> +)path:(?P<path>[^\n]*(?:\n(?P=indent) +[^\n]*)*)\n(?P=indent)key: maven-",
    re.MULTILINE,
)


def maven_cache_paths(text):
    for match in CACHE.finditer(text):
        raw = match.group("path").strip()
        if raw.startswith("|"):
            yield [line.strip() for line in raw[1:].splitlines() if line.strip()]
        else:
            yield [raw]


class WorkflowMavenCacheTest(unittest.TestCase):
    def test_chaque_cache_maven_porte_le_depot_et_le_wrapper(self):
        found = 0
        for workflow in sorted(WORKFLOWS.glob("*.yml")):
            for paths in maven_cache_paths(workflow.read_text(encoding="utf-8")):
                found += 1
                self.assertEqual(EXPECTED, paths, workflow.name)
        self.assertGreater(found, 0, "aucun cache Maven trouvé : le motif ne lit plus les workflows")

    def test_un_cache_sans_wrapper_est_signale(self):
        text = "      with:\n        path: ~/.m2/repository\n        key: maven-x\n"
        self.assertEqual([["~/.m2/repository"]], list(maven_cache_paths(text)))


if __name__ == "__main__":
    unittest.main()
