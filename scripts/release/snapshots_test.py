import shutil
import tempfile
from pathlib import Path
import unittest

import snapshots


class SnapshotTests(unittest.TestCase):
    def test_context_rejects_release_paths_forks_and_moved_sha(self):
        sha = 'a' * 40
        good = ['workflow_dispatch', snapshots.REPOSITORY, 'refs/heads/develop', sha, sha]
        snapshots.validate_context(*good)
        for index, value in ((0, 'push'), (1, 'fork/repo'), (2, 'refs/heads/main'),
                             (2, 'refs/heads/release/0.1.0'), (2, 'refs/tags/v0.1.0'),
                             (3, 'b' * 40), (3, 'bad')):
            bad = good.copy()
            bad[index] = value
            with self.assertRaises(ValueError):
                snapshots.validate_context(*bad)

    def fixture(self, root):
        source = Path(__file__).resolve().parents[2]
        for name in ('pom.xml', *(f'{m}/pom.xml' for m in snapshots.MODULE_DIRS)):
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, target)

    def test_prepare_changes_only_reactor_versions_and_development_scm(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            before = {p.relative_to(root): p.read_text() for p in root.rglob('pom.xml')}
            version = snapshots.prepare(root)
            self.assertEqual(version, '0.1.0-SNAPSHOT')
            snapshots.reactor_versions(root, version)
            for relative, source in before.items():
                restored = (root / relative).read_text().replace('0.1.0-SNAPSHOT', '0.1.0')
                if str(relative) == 'pom.xml':
                    restored = restored.replace('<tag>HEAD</tag>', '<tag>v0.1.0</tag>')
                self.assertEqual(source, restored)
            with self.assertRaises(ValueError):
                snapshots.release_version(root / 'pom.xml')

    def test_mixed_parent_rejected_before_any_rewrite(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.fixture(root)
            bad = root / 'census-core/pom.xml'
            bad.write_text(bad.read_text().replace('0.1.0', '0.2.0'))
            original = (root / 'pom.xml').read_bytes()
            with self.assertRaises(ValueError):
                snapshots.prepare(root)
            self.assertEqual(original, (root / 'pom.xml').read_bytes())


if __name__ == '__main__':
    unittest.main()
