#!/usr/bin/env python3
"""Verify producer selection from API records and real Git equivalence, offline."""
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import evidence_source as source

SHA = 'a' * 40
OLDER = 'b' * 40
NOW = datetime(2026, 10, 16, tzinfo=timezone.utc)


def run(number, sha=SHA):
    return {'id': number, 'run_attempt': 1, 'workflow_id': 10, 'path': source.WORKFLOW,
            'repository': {'full_name': source.REPOSITORY}, 'head_repository': {'full_name': source.REPOSITORY},
            'head_branch': 'develop', 'head_sha': sha, 'event': 'push', 'status': 'completed', 'conclusion': 'success',
            'html_url': f'https://github.com/{source.REPOSITORY}/actions/runs/{number}'}


def artifacts(producer):
    return [{'id': producer['id'] * 10 + n, 'name': name, 'expired': False, 'expires_at': '2026-11-16T00:00:00Z',
             'workflow_run': {'id': producer['id'], 'head_sha': producer['head_sha'], 'head_branch': 'develop'}}
            for n, name in enumerate(('docs-coverage', 'docs-cbom'))]


class ProducerTest(unittest.TestCase):
    def setUp(self):
        self.current, self.old = run(200), run(100, OLDER)
        self.runs = {200: self.current, 100: self.old}
        self.files = {200: artifacts(self.current), 100: artifacts(self.old)}
        self.calls = []

    def fetch(self, endpoint):
        self.calls.append(endpoint)
        root = f'repos/{source.REPOSITORY}/actions'
        if endpoint == root + '/workflows/ci.yml':
            return {'id': 10, 'path': source.WORKFLOW}
        if endpoint.startswith(root + '/workflows/10/runs?'):
            return {'workflow_runs': list(self.runs.values())}
        if '/artifacts?' in endpoint:
            number = int(endpoint.split('/runs/')[1].split('/')[0])
            return {'artifacts': self.files.get(number, [])}
        if endpoint.startswith(root + '/runs/'):
            return self.runs[int(endpoint.rsplit('/', 1)[1])]
        raise AssertionError(endpoint)

    def select(self, equivalent=lambda base, head: True):
        return source.select(SHA, '200', self.fetch, equivalent, NOW)

    def test_exact_current_producer_preferred(self):
        result = self.select()
        self.assertEqual(result['runId'], '200')
        self.assertFalse(result['reused'])
        self.assertFalse(any('/workflows/10/runs?' in call for call in self.calls))

    def test_prior_ancestor_keeps_its_source_run_and_attempt(self):
        self.files[200] = []
        self.old['run_attempt'] = 3
        result = self.select(lambda base, head: base == OLDER and head == SHA)
        self.assertEqual(result['evidenceSourceSha'], OLDER)
        self.assertEqual(result['siteSourceSha'], SHA)
        self.assertEqual(result['runId'], '100')
        self.assertEqual(result['runAttempt'], '3')
        self.assertEqual(result['coverageArtifactId'], '1000')
        self.assertTrue(result['reused'])

    def test_nonancestor_or_runtime_difference_cannot_reuse(self):
        self.files[200] = []
        with self.assertRaisesRegex(ValueError, 'workflow_dispatch'):
            self.select(lambda base, head: False)

    def test_invalid_trigger_source_repository_workflow_or_result_rejected(self):
        for key, value in (('head_sha', OLDER), ('event', 'pull_request'), ('conclusion', 'failure'),
                           ('status', 'in_progress'), ('head_branch', 'feature/fork'), ('workflow_id', 11),
                           ('repository', {'full_name': 'other/repository'}),
                           ('head_repository', {'full_name': 'other/fork'}),
                           ('path', '.github/workflows/other.yml'), ('html_url', 'https://evil.invalid/run')):
            original = self.current[key]
            self.current[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.select()
            self.current[key] = original

    def test_untrusted_older_candidates_are_ignored(self):
        self.files[200] = []
        for key, value in (('event', 'pull_request'), ('conclusion', 'failure'), ('status', 'in_progress'),
                           ('head_branch', 'main'), ('workflow_id', 11), ('head_repository', {'full_name': 'fork/repo'}),
                           ('path', source.WORKFLOW + '@refs/heads/other'), ('run_attempt', 0)):
            original = self.old[key]
            self.old[key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'No reusable'):
                self.select()
            self.old[key] = original

    def test_manual_full_develop_run_can_produce_and_trigger(self):
        self.current['event'] = 'workflow_dispatch'
        self.assertEqual(self.select()['runId'], '200')

    def test_both_unique_unexpired_matching_artifacts_are_required(self):
        self.files[200] = []
        original = copy.deepcopy(self.files[100])
        mutations = [[], original[:1], original + original[:1]]
        for field, value in (('expired', True), ('expires_at', '2026-10-15T00:00:00Z'), ('id', 0),
                             ('workflow_run', {'id': 999, 'head_sha': OLDER, 'head_branch': 'develop'}),
                             ('workflow_run', {'id': 100, 'head_sha': SHA, 'head_branch': 'develop'})):
            changed = copy.deepcopy(original)
            changed[0][field] = value
            mutations.append(changed)
        for mutation in mutations:
            self.files[100] = mutation
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, 'No reusable'):
                self.select()

    def test_no_fallback_to_later_run_or_redirected_url(self):
        self.files[200] = []
        self.runs.pop(100)
        self.runs[201] = run(201, OLDER)
        self.files[201] = artifacts(self.runs[201])
        with self.assertRaisesRegex(ValueError, 'No reusable'):
            self.select()
        self.assertFalse(any('/runs/201/' in call for call in self.calls))

    def test_search_stops_on_first_usable_page(self):
        self.files[200] = []
        def fetch(endpoint):
            if '/workflows/10/runs?' in endpoint:
                self.assertIn('page=1', endpoint)
                return {'workflow_runs': [self.old] * 100}
            return self.fetch(endpoint)
        self.assertEqual(source.select(SHA, '200', fetch, lambda base, head: True, NOW)['runId'], '100')

    def test_api_failure_is_not_a_fake_success_or_silent_skip(self):
        with self.assertRaises(OSError):
            source.select(SHA, '200', lambda endpoint: (_ for _ in ()).throw(OSError('API unavailable')), now=NOW)


class EquivalenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='census-evidence-source-')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        old = Path.cwd()
        os.chdir(self.path)
        self.addCleanup(os.chdir, old)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Synthetic Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.base = self.save('runtime.java', 'code')
        self.site = self.save('docs/page.md', 'documentation')
        self.proof = {'schemaVersion': 1, 'repository': source.REPOSITORY, 'siteSourceSha': self.site,
                      'evidenceSourceSha': self.base, 'runId': '100', 'runAttempt': '1',
                      'runUrl': f'https://github.com/{source.REPOSITORY}/actions/runs/100', 'reused': True,
                      'coverageArtifactId': '1000', 'cbomArtifactId': '1001'}
        self.file = self.path / 'selection.json'
        self.file.write_text(json.dumps(self.proof))
        self.env = {'DOCS_SOURCE_SHA': self.site, 'DOCS_EVIDENCE_SHA': self.base, 'DOCS_RUN_ID': '100',
                    'DOCS_RUN_ATTEMPT': '1', 'DOCS_RUN_URL': self.proof['runUrl'], 'DOCS_EVIDENCE_PROOF': str(self.file)}

    def git(self, *args):
        return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL).decode().strip()

    def save(self, name, content):
        file = Path(name)
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        self.git('add', name)
        self.git('commit', '-qm', 'synthetic fixture')
        return self.git('rev-parse', 'HEAD')

    def test_reused_evidence_has_verified_actual_git_ancestry_and_allowlisted_diff(self):
        source.verify(self.env)
        self.assertTrue(source.code_equivalent(self.base, self.site))

    def test_runtime_change_and_forged_receipt_cannot_bypass_git_check(self):
        changed = self.save('runtime.java', 'different runtime')
        self.proof['siteSourceSha'] = changed
        self.file.write_text(json.dumps(self.proof))
        with self.assertRaisesRegex(ValueError, 'only documentation'):
            source.verify({**self.env, 'DOCS_SOURCE_SHA': changed})

    def test_wrong_or_missing_selection_record_rejected(self):
        for key in ('DOCS_SOURCE_SHA', 'DOCS_RUN_ID', 'DOCS_RUN_ATTEMPT', 'DOCS_RUN_URL', 'DOCS_EVIDENCE_PROOF'):
            with self.subTest(key=key), self.assertRaises((ValueError, OSError)):
                source.verify({**self.env, key: ''})

    def test_nonancestor_is_not_code_equivalent(self):
        self.git('checkout', '-qb', 'other', self.base)
        sibling = self.save('docs/other.md', 'sibling documentation')
        with self.assertRaises(subprocess.CalledProcessError):
            source.code_equivalent(sibling, self.site)


if __name__ == '__main__':
    unittest.main()
