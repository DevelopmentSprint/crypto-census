"""Tag inventory and stale-publication checks use disposable repositories only."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import versions


def clean_env():
    return {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}


class ReleaseVersionsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='census-docs-tags-test-')
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.env = {**clean_env(), 'GIT_AUTHOR_NAME': 'Fixture', 'GIT_AUTHOR_EMAIL': 'fixture@example.invalid',
                    'GIT_COMMITTER_NAME': 'Fixture', 'GIT_COMMITTER_EMAIL': 'fixture@example.invalid',
                    'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull}
        self.git('init', '-q')
        (self.repo / 'docs').mkdir()
        (self.repo / 'docs/index.md').write_text('# Tagged release fixture\n')
        self.git('add', 'docs/index.md')
        self.git('commit', '-qm', 'fixture docs')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, env=self.env).decode().strip()

    def test_only_release_tags_include_pinned_sources(self):
        sha = self.git('rev-parse', 'HEAD')
        for tag in ['v0.1.0', 'v0.2.0-SNAPSHOT', 'latest', 'develop-preview', '0.1.1-SNAPSHOT-20261020', 'v0.3.0-rc.01', 'v0.3.0+metadata']:
            self.git('tag', tag)
        self.git('tag', '-a', 'v0.2.0', '-m', 'annotated fixture release')
        (self.repo / 'docs/index.md').write_text('# Untagged changes\n')
        self.git('commit', '-qam', 'unreleased fixture')
        self.assertEqual(versions.inventory(self.repo), [
            {'tag': tag, 'sha': sha, 'path': f'/versions/{tag}/'} for tag in ['v0.2.0', 'v0.1.0']])

    def test_semantic_sort_and_hook_environment(self):
        from unittest.mock import patch
        for tag in ['v0.2.0', 'v0.10.0', 'v0.10.0-rc.2', 'v0.10.0-rc.10']:
            self.git('tag', tag)
        caller = {key: os.environ.get(key) for key in ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE']}
        with patch.dict(os.environ, GIT_DIR='/nonexistent/hook.git', GIT_WORK_TREE='/nonexistent', GIT_INDEX_FILE='/nonexistent/index'):
            self.assertEqual([v['tag'] for v in versions.inventory(self.repo)],
                             ['v0.10.0', 'v0.10.0-rc.10', 'v0.10.0-rc.2', 'v0.2.0'])
        self.assertEqual({key: os.environ.get(key) for key in caller}, caller)

    def test_no_tags_means_no_invented_release(self):
        self.assertEqual(versions.inventory(self.repo), [])

    def test_missing_docs_rejected(self):
        self.git('rm', '-q', 'docs/index.md')
        self.git('commit', '-qm', 'remove fixture docs')
        self.git('tag', 'v1.0.0')
        with self.assertRaisesRegex(ValueError, 'no documentation'):
            versions.inventory(self.repo)

    def test_changed_added_or_deleted_tag_rejected(self):
        self.git('tag', 'v0.1.0')
        original = versions.inventory(self.repo)
        self.git('commit', '--allow-empty', '-qm', 'new fixture commit')
        self.git('tag', '-f', 'v0.1.0')
        with self.assertRaisesRegex(ValueError, 'inventory changed'):
            versions.verify(original, self.repo)
        self.assertNotEqual(versions.fingerprint(original), versions.fingerprint(versions.inventory(self.repo)))
        self.git('tag', '-d', 'v0.1.0')
        with self.assertRaisesRegex(ValueError, 'inventory changed'):
            versions.verify(original, self.repo)
        self.git('tag', 'v0.2.0')
        with self.assertRaisesRegex(ValueError, 'inventory changed'):
            versions.verify(original, self.repo)

    def test_private_state_in_tag_rejected_without_export(self):
        private = self.repo / '.beads'
        private.mkdir()
        (private / 'test-fixture.txt').write_text('Synthetic private-path fixture, not real tracker data\n')
        self.git('add', '.beads/test-fixture.txt')
        self.git('commit', '-qm', 'synthetic forbidden-path fixture')
        self.git('tag', 'v0.1.0')
        with self.assertRaisesRegex(ValueError, 'private tracker state'):
            versions.inventory(self.repo)


if __name__ == '__main__':
    unittest.main()
