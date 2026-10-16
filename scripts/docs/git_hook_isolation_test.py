#!/usr/bin/env python3
"""Run real linked-worktree pushes; fixture Git must never target the caller."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from git_test_env import clean_git_environment, isolate_git_for_test, isolated_git_environment

# Explicit public source allowlist. Do not copy a checkout, tracker, .git, node_modules
# or this regression itself (the real hook runs discovery in its disposable copy).
FIXTURES = (
    '.gitignore', '.githooks/pre-push', '.githooks/pre-commit', '.github/workflows/ci.yml',
    '.github/workflows/docs.yml', '.github/workflows/dependency-check.yml',
    'scripts/check-private-tracker.sh', 'scripts/ci_changes.py',
    'scripts/ci_changes_test.py', 'scripts/private_tracker_test.py', 'scripts/git_test_env.py',
    'scripts/coverage_badges.py', 'scripts/coverage_badges_test.py',
    'scripts/docs/prepare.mjs', 'scripts/docs/prepare_test.mjs',
    'scripts/docs/analytics.mjs', 'scripts/docs/analytics_test.mjs',
    'scripts/docs/example_cbom.py', 'scripts/docs/example_cbom_test.py',
    'scripts/docs/evidence_source.py', 'scripts/docs/evidence_source_test.py',
)


class EnvironmentTest(unittest.TestCase):
    def test_all_git_local_names_and_config_injections_are_removed(self):
        names = subprocess.check_output(['git', 'rev-parse', '--local-env-vars'],
                                        env=clean_git_environment(), text=True).splitlines()
        contaminated = {name: 'caller-repository' for name in names}
        contaminated.update(GIT_CONFIG_KEY_0='core.bare', GIT_CONFIG_VALUE_0='true',
                            GIT_FUTURE_OVERRIDE='caller-repository', git_dir='caller-repository', Git_Work_Tree='caller-repository',
                            PATH=os.environ['PATH'])
        clean = clean_git_environment(contaminated)
        self.assertEqual({name for name in clean if name.upper().startswith('GIT_')},
                         {'GIT_CONFIG_GLOBAL', 'GIT_CONFIG_NOSYSTEM'})
        self.assertEqual(clean['GIT_CONFIG_GLOBAL'], os.devnull)
        self.assertEqual(clean['GIT_CONFIG_NOSYSTEM'], '1')
        self.assertEqual(clean['PATH'], os.environ['PATH'])

    def test_context_restores_caller_environment_after_exception(self):
        original = os.environ.copy()
        with patch.dict(os.environ, {'GIT_DIR': '/synthetic/caller', 'GIT_CONFIG_COUNT': '1'}):
            poisoned = os.environ.copy()
            with self.assertRaisesRegex(RuntimeError, 'fixture error'):
                with isolated_git_environment():
                    self.assertNotIn('GIT_DIR', os.environ)
                    self.assertNotIn('GIT_CONFIG_COUNT', os.environ)
                    raise RuntimeError('fixture error')
            self.assertEqual(dict(os.environ), poisoned)
        self.assertEqual(dict(os.environ), original)

    def test_failed_fixture_setup_restores_environment_through_registered_cleanup(self):
        class BrokenFixture(unittest.TestCase):
            def setUp(self):
                isolate_git_for_test(self)
                self.assertNotIn('GIT_DIR', os.environ)
                raise RuntimeError('synthetic setup failure')

            def runTest(self):
                self.fail('setUp should fail before the test body')

        with patch.dict(os.environ, {'GIT_DIR': '/synthetic/caller', 'GIT_INDEX_FILE': '/synthetic/index'}):
            original = os.environ.copy()
            result = unittest.TestResult()
            BrokenFixture().run(result)
            self.assertEqual(len(result.errors), 1)
            self.assertIn('synthetic setup failure', result.errors[0][1])
            self.assertEqual(dict(os.environ), original)


class HookIsolationTest(unittest.TestCase):
    def setUp(self):
        isolate_git_for_test(self)
        self.temp = tempfile.TemporaryDirectory(prefix='census-real-push-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.remote, self.owner, self.linked = [self.root / name for name in ('remote.git', 'owner', 'linked')]
        self.env = clean_git_environment()
        # Read-only classifier/status calls must not opportunistically refresh caller indexes.
        self.env['GIT_OPTIONAL_LOCKS'] = '0'
        self.git(self.root, 'init', '--bare', '-q', str(self.remote))
        self.git(self.root, 'clone', '-q', str(self.remote), str(self.owner))
        self.git(self.owner, 'config', 'user.name', 'Caller Identity Sentinel')
        self.git(self.owner, 'config', 'user.email', 'caller@example.invalid')
        for name in FIXTURES:
            destination = self.owner / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, destination)
        (self.owner / 'runtime-sentinel.txt').write_text('caller runtime and worktree sentinel\n')
        # Any accidental full-build classification must fail this docs-only regression.
        (self.owner / 'mvnw').write_text('#!/bin/sh\necho "Unexpected Maven invocation" >&2\nexit 77\n')
        (self.owner / 'mvnw').chmod(0o755)
        self.git(self.owner, 'add', '--', *FIXTURES, 'runtime-sentinel.txt', 'mvnw')
        self.git(self.owner, 'commit', '-qm', 'synthetic hook baseline')
        self.git(self.owner, 'branch', '-M', 'develop')
        self.git(self.owner, 'push', '-q', 'origin', 'develop')
        self.git(self.owner, 'fetch', '-q', 'origin')
        self.git(self.owner, 'worktree', 'add', '-q', '-b', 'feature/docs-proof', str(self.linked), 'develop')
        (self.linked / 'docs').mkdir()
        (self.linked / 'docs/proof.md').write_text('Documentation-only proof of hook isolation.\n')
        self.git(self.linked, 'add', 'docs/proof.md')
        self.git(self.linked, 'commit', '-qm', 'synthetic docs update')
        self.head = self.git(self.linked, 'rev-parse', 'HEAD')
        self.common = Path(self.git(self.owner, 'rev-parse', '--absolute-git-dir'))
        self.linked_git = Path(self.git(self.linked, 'rev-parse', '--absolute-git-dir'))
        self.owner_index = self.common / 'index'
        self.linked_index = self.linked_git / 'index'

        # Only npm's install/build/audit commands are stand-ins. Node/Python suites,
        # Git, the classifier and the private tracker guard all execute for real.
        binary = self.root / 'bin'
        binary.mkdir()
        npm = binary / 'npm'
        npm.write_text('#!/bin/sh\nprintf "npm:%s\\n" "$*" >> "$NPM_CHECK_LOG"\n')
        npm.chmod(0o755)
        self.env['PATH'] = str(binary) + os.pathsep + self.env['PATH']
        self.env['NPM_CHECK_LOG'] = str(self.root / 'npm.log')
        self.env['HOOK_CONTEXT_LOG'] = str(self.root / 'hook-context.log')
        capture = self.root / 'capture-hooks'
        capture.mkdir()
        hook = capture / 'pre-push'
        hook.write_text('#!/bin/sh\nprintf "GIT_DIR=%s\\nGIT_COMMON_DIR=%s\\n" "${GIT_DIR-}" "${GIT_COMMON_DIR-}" > "$HOOK_CONTEXT_LOG"\nexec bash .githooks/pre-push "$@"\n')
        hook.chmod(0o755)
        self.git(self.owner, 'config', 'core.hooksPath', str(capture))

    def git(self, directory, *args):
        result = subprocess.run(['git', *args], cwd=directory, env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result.stdout.strip()

    def snapshot(self):
        return {
            'owner-head': self.git(self.owner, 'rev-parse', 'HEAD'),
            'linked-head': self.git(self.linked, 'rev-parse', 'HEAD'),
            'owner-branch': self.git(self.owner, 'symbolic-ref', 'HEAD'),
            'linked-branch': self.git(self.linked, 'symbolic-ref', 'HEAD'),
            'branches': self.git(self.owner, 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/heads/'),
            'config': (self.common / 'config').read_bytes(),
            'owner-index': self.owner_index.read_bytes(),
            'linked-index': self.linked_index.read_bytes(),
            'owner-sentinel': (self.owner / 'runtime-sentinel.txt').read_bytes(),
            'linked-sentinel': (self.linked / 'runtime-sentinel.txt').read_bytes(),
            'docs': (self.linked / 'docs/proof.md').read_bytes(),
        }

    def test_real_push_and_contaminated_fixture_suites_leave_caller_unchanged(self):
        before = self.snapshot()
        result = subprocess.run(['git', 'push', 'origin', 'refs/heads/feature/docs-proof:refs/heads/feature/docs-proof'],
                                cwd=self.linked, env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('documentation-only outgoing history', result.stdout)
        self.assertIn('checks passed', result.stdout)
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(self.git(self.root, '--git-dir=' + str(self.remote), 'rev-parse', 'refs/heads/feature/docs-proof'), self.head)
        context = (self.root / 'hook-context.log').read_text()
        self.assertIn('GIT_DIR=' + str(self.linked_git), context)
        npm = (self.root / 'npm.log').read_text()
        self.assertIn('npm:run docs:typecheck', npm)
        self.assertIn('npm:run docs:build', npm)

        # Exercise more inherited overrides than Git happens to export on this platform.
        # Every target is disposable; a broken sanitizer can damage only this fixture.
        contaminated = {**self.env, 'GIT_DIR': str(self.linked_git), 'GIT_COMMON_DIR': str(self.common),
                        'GIT_WORK_TREE': str(self.linked), 'GIT_INDEX_FILE': str(self.linked_index),
                        'GIT_OBJECT_DIRECTORY': str(self.common / 'objects'),
                        'GIT_ALTERNATE_OBJECT_DIRECTORIES': str(self.common / 'objects'),
                        'GIT_CONFIG': str(self.common / 'config'), 'GIT_CONFIG_COUNT': '1',
                        'GIT_CONFIG_KEY_0': 'core.bare', 'GIT_CONFIG_VALUE_0': 'true', 'GIT_PREFIX': 'poison/'}
        suites = [
            ['node', '--test', 'scripts/docs/prepare_test.mjs'],
            [sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/docs', '-p', '*_test.py'],
            [sys.executable, 'scripts/ci_changes_test.py'],
            [sys.executable, 'scripts/private_tracker_test.py'],
        ]
        for command in suites:
            with self.subTest(command=command):
                checked = subprocess.run(command, cwd=self.linked, env=contaminated, capture_output=True, text=True)
                self.assertEqual(checked.returncode, 0, checked.stdout + checked.stderr)
                self.assertEqual(self.snapshot(), before)


if __name__ == '__main__':
    unittest.main()
