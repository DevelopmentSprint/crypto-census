#!/usr/bin/env python3
"""Exercise the actual Git boundary, outgoing history and aggregate outcomes offline."""
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest
import ci_changes as changes

ROOT = Path(__file__).resolve().parents[1]


class ChangesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='census-ci-changes-')
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.old = Path.cwd()
        os.chdir(self.repo)
        self.addCleanup(os.chdir, self.old)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.base = self.save('runtime.java', 'initial runtime')
        self.git('remote', 'add', 'origin', str(self.repo / 'unused-remote'))
        self.git('update-ref', 'refs/remotes/origin/develop', self.base)

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL).decode().strip()

    def save(self, name, text='changed'):
        file = Path(name)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)
        self.git('add', '--', name)
        self.git('commit', '-qm', 'synthetic test change')
        return self.git('rev-parse', 'HEAD')

    def classify(self, head):
        return changes.classify_event('push', {'before': self.base, 'after': head}, head)

    def test_doc_content_and_dependencies_qualify(self):
        for name in ('docs/a.md', 'docs/.vitepress/theme/a.vue', 'scripts/docs/test.py', 'package.json', 'package-lock.json'):
            head = self.save(name)
            self.assertEqual(self.classify(head), 'docs-only')

    def test_readme_runtime_unknown_and_controls_require_full(self):
        for name in ('README.md', 'runtime.java', 'pom.xml', 'scripts/ci_changes.py', '.github/workflows/docs.yml',
                     '.githooks/pre-push', 'AGENTS.md', 'scripts/coverage_badges.py', 'unknown.txt', 'docs-secret.txt'):
            self.git('reset', '--hard', self.base)
            head = self.save(name)
            with self.subTest(name=name):
                self.assertEqual(self.classify(head), 'full')

    def test_nul_safe_paths(self):
        head = self.save('docs/space and\nnewline.md')
        self.assertEqual(self.classify(head), 'docs-only')
        head = self.save('runtime\ndocs/fake.md')
        self.assertEqual(self.classify(head), 'full')

    def test_rename_checks_source_and_destination(self):
        Path('docs').mkdir(exist_ok=True)
        self.git('mv', 'runtime.java', 'docs/moved.md')
        self.git('commit', '-qm', 'move runtime to docs')
        self.assertEqual(self.classify(self.git('rev-parse', 'HEAD')), 'full')
        self.git('reset', '--hard', self.base)
        base = self.save('docs/a.md')
        self.git('mv', 'docs/a.md', 'runtime.txt')
        self.git('commit', '-qm', 'move out of docs')
        self.assertFalse(changes.history_docs_only(base, self.git('rev-parse', 'HEAD')))

    def test_symlink_and_submodule_changes_under_docs_force_full(self):
        Path('docs').mkdir()
        Path('docs/link').symlink_to('../runtime.java')
        self.git('add', 'docs/link')
        self.git('commit', '-qm', 'symlink doc')
        self.assertEqual(self.classify(self.git('rev-parse', 'HEAD')), 'full')
        self.git('reset', '--hard', self.base)
        self.git('update-index', '--add', '--cacheinfo', '160000', self.base, 'docs/submodule')
        self.git('commit', '-qm', 'synthetic submodule')
        self.assertEqual(self.classify(self.git('rev-parse', 'HEAD')), 'full')

    def test_deletions_are_checked(self):
        self.git('rm', 'runtime.java')
        self.git('commit', '-qm', 'delete runtime')
        self.assertEqual(self.classify(self.git('rev-parse', 'HEAD')), 'full')
        self.git('reset', '--hard', self.base)
        base = self.save('docs/a.md')
        self.git('rm', 'docs/a.md')
        self.git('commit', '-qm', 'delete doc')
        self.assertTrue(changes.history_docs_only(base, self.git('rev-parse', 'HEAD')))

    def test_runtime_edit_then_revert_is_not_docs_only_history(self):
        self.save('runtime.java', 'temporary runtime')
        head = self.save('runtime.java', 'initial runtime')
        self.assertEqual(self.classify(head), 'full')

    def test_pr_merge_with_docs_only_and_mixed_changes(self):
        source = self.save('docs/a.md')
        self.git('checkout', '-qb', 'base', self.base)
        self.git('merge', '--no-ff', source, '-m', 'merge doc')
        merge = self.git('rev-parse', 'HEAD')
        pr = {'pull_request': {'base': {'sha': self.base}, 'head': {'sha': source}}}
        self.assertEqual(changes.classify_event('pull_request', pr, merge), 'docs-only')
        head = self.save('runtime.java', 'runtime')
        self.assertEqual(changes.classify_event('pull_request', pr, head), 'full')

    def test_invalid_event_history_falls_back_to_full_cli(self):
        for base in (None, '', '0' * 40, 'f' * 40, '--all'):
            event = self.repo / 'event.json'
            event.write_text(json.dumps({'before': base, 'after': self.base}))
            result = subprocess.run(['python3', str(ROOT / 'scripts/ci_changes.py'), 'event'],
                                    env={**os.environ, 'GITHUB_EVENT_PATH': str(event), 'GITHUB_EVENT_NAME': 'push',
                                         'GITHUB_SHA': self.base, 'GITHUB_OUTPUT': ''}, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), 'full')

    def test_schedule_and_manual_always_full(self):
        for event in ('workflow_dispatch', 'schedule', 'unknown'):
            self.assertEqual(changes.classify_event(event, {}, self.base), 'full')

    def test_new_feature_push_checks_all_outgoing_history(self):
        head = self.save('docs/a.md')
        line = f'refs/heads/feature/docs {head} refs/heads/feature/docs {"0" * 40}'
        self.assertEqual(changes.classify_push('origin', [line]), 'docs-only')
        self.save('runtime.java', 'temporary')
        head = self.save('runtime.java', 'initial runtime')
        self.assertEqual(changes.classify_push('origin', [line.replace(line.split()[1], head)]), 'full')

    def test_multi_ref_dirty_missing_remote_and_nonhead_push_fall_back_full(self):
        head = self.save('docs/a.md')
        line = f'refs/heads/feature/docs {head} refs/heads/feature/docs {self.base}'
        self.assertEqual(changes.classify_push('origin', [line]), 'docs-only')
        self.assertEqual(changes.classify_push('unknown', [line]), 'full')
        self.assertEqual(changes.classify_push('origin', [line, line]), 'full')
        self.assertEqual(changes.classify_push('origin', [line.replace('refs/heads/feature/docs ' + self.base, 'refs/tags/docs ' + self.base)]), 'full')
        self.assertEqual(changes.classify_push('origin', [line, line.replace(head, self.base)]), 'full')
        self.assertEqual(changes.classify_push('origin', [line.replace('feature/docs', 'release/1')]), 'full')
        Path('runtime.java').write_text('dirty')
        self.assertEqual(changes.classify_push('origin', [line]), 'full')

    def test_force_update_uncertainty_falls_back_full(self):
        head = self.save('docs/a.md')
        self.git('checkout', '-qb', 'other', self.base)
        other = self.save('docs/b.md')
        self.git('checkout', '--detach', head)
        line = f'refs/heads/feature/docs {head} refs/heads/feature/docs {other}'
        # Direct API raises the Git ancestry error; CLI catches it and requests full.
        with self.assertRaises(subprocess.CalledProcessError):
            changes.classify_push('origin', [line])
        result = subprocess.run(['python3', str(ROOT / 'scripts/ci_changes.py'), 'pre-push', 'origin'],
                                input=line + '\n', capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'full')

    def test_actual_hook_selects_docs_checks_or_maven_and_always_checks_private_state(self):
        for name in ('scripts/ci_changes.py', 'scripts/check-private-tracker.sh'):
            file = Path(name)
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes((ROOT / name).read_bytes())
        Path('mvnw').write_text('#!/bin/sh\necho "maven:$*" >> "$CHECK_LOG"\n')
        Path('mvnw').chmod(0o755)
        self.git('add', 'scripts', 'mvnw')
        self.git('commit', '-qm', 'test hook tools baseline')
        baseline = self.git('rev-parse', 'HEAD')
        remote = self.repo / 'hook-remote.git'
        self.git('init', '--bare', str(remote))
        self.git('remote', 'set-url', 'origin', str(remote))
        self.git('push', 'origin', baseline + ':refs/heads/develop')
        self.git('fetch', 'origin')
        bin_dir = self.repo / 'fake-bin'
        bin_dir.mkdir()
        for name in ('npm', 'node'):
            file = bin_dir / name
            file.write_text('#!/bin/sh\necho "' + name + ':$*" >> "$CHECK_LOG"\n')
            file.chmod(0o755)
        python = bin_dir / 'python3'
        python.write_text('#!/bin/sh\nif [ "$1" = scripts/ci_changes.py ]; then exec "' + sys.executable + '" "$@"; fi\necho "python:$*" >> "$CHECK_LOG"\n')
        python.chmod(0o755)
        log = self.repo / 'checks.log'
        env = {**os.environ, 'PATH': str(bin_dir) + os.pathsep + os.environ['PATH'], 'CHECK_LOG': str(log)}
        def push(head):
            return subprocess.run(['bash', str(ROOT / '.githooks/pre-push'), 'origin'],
                                  input=f'refs/heads/feature/docs {head} refs/heads/feature/docs {baseline}\n',
                                  env=env, capture_output=True, text=True)
        docs = self.save('docs/page.md')
        result = push(docs)
        self.assertEqual(result.returncode, 0, result.stderr)
        checks = log.read_text()
        self.assertIn('npm:run docs:typecheck', checks)
        self.assertIn('npm:run docs:build', checks)
        self.assertNotIn('maven:', checks)
        log.unlink()
        original_python = python.read_text()
        for body, diagnostic in (('echo docs-only; exit 1', 'classification failed'),
                                 ('echo unexpected; exit 0', 'invalid classification result')):
            with self.subTest(classifier=body):
                python.write_text('#!/bin/sh\n' + body + '\n')
                result = push(docs)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(diagnostic, result.stderr)
                self.assertEqual(log.read_text().strip(), 'maven:-B -q verify')
                log.unlink()
        # A minimal real-tool PATH proves that missing Python does not block Java-only users.
        python.unlink()
        for command in ('bash', 'git', 'mktemp', 'cat', 'rm', 'grep'):
            (bin_dir / command).symlink_to(shutil.which(command))
        original_path = env['PATH']
        env['PATH'] = str(bin_dir)
        result = push(docs)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Python 3 is unavailable', result.stderr)
        self.assertEqual(log.read_text().strip(), 'maven:-B -q verify')
        log.unlink()
        env['PATH'] = original_path
        python.write_text(original_python)
        python.chmod(0o755)
        runtime = self.save('runtime.java', 'new runtime')
        result = push(runtime)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(log.read_text().strip(), 'maven:-B -q verify')
        log.unlink()
        Path('.beads').mkdir()
        Path('.beads/private.json').write_text('synthetic private sentinel')
        self.git('add', '.beads/private.json')
        result = push(runtime)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('forbidden path', result.stderr)
        self.assertFalse(log.exists())

    def test_gate_rejects_every_wrong_required_result(self):
        for mode, expected in (('full', 'success'), ('docs-only', 'skipped')):
            valid = {'classify': {'result': 'success', 'outputs': {'mode': mode}},
                     **{key: {'result': expected} for key in ('build', 'dogfood', 'app-servers', 'private-tracker', 'advisory-demo', 'guided-report')}}
            self.assertTrue(changes.required(valid))
            for job in ('build', 'dogfood', 'app-servers', 'private-tracker', 'advisory-demo', 'guided-report'):
                for outcome in ('success', 'skipped', 'failure', 'cancelled', None):
                    if outcome == expected:
                        continue
                    altered = {**valid, job: {'result': outcome}}
                    self.assertFalse(changes.required(altered), (mode, job, outcome))
            for outcome in ('skipped', 'failure', 'cancelled', None):
                self.assertFalse(changes.required({**valid, 'classify': {'result': outcome, 'outputs': {'mode': mode}}}))
            self.assertFalse(changes.required({**valid, 'classify': {'result': 'success', 'outputs': {'mode': 'unknown'}}}))

    def test_workflows_start_and_gate_all_java_jobs_without_path_filters(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('  workflow_dispatch:', ci)
        self.assertNotIn('paths-ignore:', ci)
        self.assertIn('name: Required CI', ci)
        self.assertIn('needs: [classify, build, dogfood, app-servers, private-tracker, advisory-demo, guided-report]', ci)
        self.assertIn('if: always()', ci.split('  required:')[1])
        for job in ('build', 'dogfood', 'app-servers', 'early-access'):
            segment = ci.split(f'  {job}:')[1].split('    name:')[0]
            self.assertIn('needs: classify', segment)
            self.assertIn("if: needs.classify.outputs.mode == 'full'", segment)
        dependency = (ROOT / '.github/workflows/dependency-check.yml').read_text()
        self.assertIn("if: needs.classify.outputs.mode == 'full'", dependency)
        hook = (ROOT / '.githooks/pre-push').read_text()
        self.assertLess(hook.index('check-private-tracker.sh'), hook.index('ci_changes.py'))
        self.assertEqual(hook.count('< "$refs"'), 2)


if __name__ == '__main__':
    unittest.main()
