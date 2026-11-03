#!/usr/bin/env python3
"""Offline publication-boundary regressions; all synthetic evidence stays in temp dirs."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import example_cbom as cbom

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from git_test_env import isolate_git_for_test
SHA = "a" * 40
RUN = "9cf641d1-5d70-4377-876d-ea742cb4e42a"


class PublicationTest(unittest.TestCase):
    def setUp(self):
        isolate_git_for_test(self)
        self.temp = tempfile.TemporaryDirectory(prefix="census-example-cbom-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.raw, self.package, self.site = [self.root / name for name in ("raw", "package", "site")]
        self.raw.mkdir()
        self.producer = {"GITHUB_REPOSITORY": cbom.REPOSITORY, "GITHUB_SHA": SHA,
                         "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2",
                         "GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/develop", "CENSUS_RUN_ID": RUN}
        self.consumer = {"DOCS_CBOM_DIR": str(self.package), "DOCS_REQUIRE_CBOM": "true",
                         "DOCS_REPOSITORY": cbom.REPOSITORY, "DOCS_SOURCE_SHA": SHA,
                         "DOCS_RUN_ID": "123", "DOCS_RUN_ATTEMPT": "2",
                         "DOCS_RUN_URL": f"https://github.com/{cbom.REPOSITORY}/actions/runs/123"}
        self.evidence = {"bomFormat": "CycloneDX", "specVersion": "1.6",
                         "metadata": {"component": {"name": "census-demo"}, "properties": [
                             {"name": "cryptocensus:project", "value": f"{cbom.MODULE}:0.1.0"}]},
                         "components": [{"type": "cryptographic-asset", "name": "Synthetic test algorithm"}]}
        self.manifest = {"schema": "census.maven-evidence/1", "revision": "1", "artifact": "evidence.json",
                         "descriptor": {"schema": "census.maven-run/1", "module": cbom.MODULE,
                                        "version": "0.1.0", "runId": RUN, "moduleRunId": RUN,
                                        "preparedAt": "2026-10-13T22:13:08.623036315Z", "reports": "."},
                         "finalizedAt": "2026-10-13T22:13:08.623036315Z", "health": "healthy",
                         "testOutcome": "passed", "policyOutcome": "passed", "policy": {"failOn": "broken"},
                         "inputs": {"cbom-synthetic.json": "b" * 64}}
        self.save()

    def save(self):
        # Deliberate whitespace: byte-for-byte preservation is part of the contract.
        data = (json.dumps(self.evidence, indent=3) + "  \n").encode()
        self.manifest["sha256"] = hashlib.sha256(data).hexdigest()
        (self.raw / "evidence.json").write_bytes(data)
        (self.raw / "manifest.json").write_bytes(cbom.encode(self.manifest))

    def pack(self):
        cbom.package(self.raw, self.package, self.producer)

    def reject_pack(self):
        with self.assertRaises((ValueError, OSError, TypeError)):
            self.pack()
        self.assertFalse(self.package.exists())

    def test_exact_bytes_and_manifest_hash_retained_through_both_stages(self):
        self.pack()
        cbom.prepare(self.site, self.consumer)
        for name in ("evidence.json", "manifest.json"):
            self.assertEqual((self.raw / name).read_bytes(), (self.site / name).read_bytes())
        self.assertEqual(set(p.name for p in self.site.iterdir()), cbom.FILES)
        metadata = cbom.decode((self.site / "index.json").read_bytes())
        self.assertEqual(metadata["downloads"]["manifest"]["sha256"], cbom.digest((self.raw / "manifest.json").read_bytes()))
        self.assertEqual(metadata["collection"]["runId"], RUN)

    def test_reused_cbom_prepare_preserves_original_bytes_and_rejects_runtime_difference(self):
        previous = Path.cwd()
        os.chdir(self.root)
        try:
            def git(*args):
                return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'Synthetic Test')
            git('config', 'user.email', 'test@example.invalid')
            Path('runtime.java').write_text('baseline runtime')
            git('add', 'runtime.java')
            git('commit', '-qm', 'synthetic producer')
            producer = git('rev-parse', 'HEAD')
            self.producer['GITHUB_SHA'] = producer
            self.pack()
            Path('docs').mkdir()
            Path('docs/page.md').write_text('updated documentation')
            git('add', 'docs/page.md')
            git('commit', '-qm', 'synthetic site change')
            site = git('rev-parse', 'HEAD')
            proof = {'schemaVersion': 1, 'repository': cbom.REPOSITORY, 'siteSourceSha': site,
                     'evidenceSourceSha': producer, 'runId': '123', 'runAttempt': '2',
                     'runUrl': self.consumer['DOCS_RUN_URL'], 'reused': True,
                     'coverageArtifactId': '1000', 'cbomArtifactId': '1001'}
            proof_file = self.root / 'selection.json'
            proof_file.write_text(json.dumps(proof))
            consumer = {**self.consumer, 'DOCS_SOURCE_SHA': site, 'DOCS_EVIDENCE_SHA': producer,
                        'DOCS_EVIDENCE_PROOF': str(proof_file)}
            cbom.prepare(self.site, consumer)
            for name in cbom.FILES:
                self.assertEqual((self.site / name).read_bytes(), (self.package / name).read_bytes())
            index = cbom.decode((self.site / 'index.json').read_bytes())
            self.assertEqual(index['sourceSha'], producer)
            self.assertNotEqual(index['sourceSha'], site)
            self.assertEqual(index['runAttempt'], '2')
            Path('runtime.java').write_text('changed runtime')
            git('add', 'runtime.java')
            git('commit', '-qm', 'synthetic runtime change')
            changed = git('rev-parse', 'HEAD')
            proof_file.write_text(json.dumps({**proof, 'siteSourceSha': changed}))
            with self.assertRaisesRegex(ValueError, 'only documentation'):
                cbom.prepare(self.site, {**consumer, 'DOCS_SOURCE_SHA': changed})
        finally:
            os.chdir(previous)

    def test_missing_production_artifact_fails_closed(self):
        with self.assertRaises(ValueError):
            cbom.prepare(self.site, {"DOCS_REQUIRE_CBOM": "true"})
        self.assertFalse(self.site.exists())

    def test_local_preview_is_explicitly_unavailable_and_clears_previous_example(self):
        self.pack()
        cbom.prepare(self.site, self.consumer)
        cbom.prepare(self.site, {})
        self.assertEqual(set(p.name for p in self.site.iterdir()), {"index.json"})
        metadata = cbom.decode((self.site / "index.json").read_bytes())
        self.assertEqual(metadata["status"], "unavailable")
        self.assertNotIn("sourceSha", metadata)

    def test_missing_or_different_source_run_attempt_repository_url_rejected(self):
        self.pack()
        for key in ("DOCS_SOURCE_SHA", "DOCS_RUN_ID", "DOCS_RUN_ATTEMPT", "DOCS_REPOSITORY", "DOCS_RUN_URL"):
            for value in ("", "wrong"):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    cbom.prepare(self.site, {**self.consumer, key: value})
        for key, value in (("DOCS_SOURCE_SHA", "c" * 40), ("DOCS_RUN_ID", "124"), ("DOCS_RUN_ATTEMPT", "1")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                cbom.prepare(self.site, {**self.consumer, key: value})
        self.assertFalse(self.site.exists())

    def test_partial_rerun_retains_actual_successful_producer_attempt(self):
        self.pack()
        cbom.prepare(self.site, {**self.consumer, "DOCS_RUN_ATTEMPT": "3"})
        metadata = cbom.decode((self.site / "index.json").read_bytes())
        self.assertEqual(metadata["runAttempt"], "2")
        self.assertEqual((self.site / "index.json").read_bytes(), (self.package / "index.json").read_bytes())

    def test_future_and_invalid_producer_attempt_rejected(self):
        self.pack()
        file = self.package / "index.json"
        original = cbom.decode(file.read_bytes())
        for attempt in ("3", "0", "-1", "invalid", 2, None):
            file.write_bytes(cbom.encode({**original, "runAttempt": attempt}))
            with self.subTest(attempt=attempt), self.assertRaises(ValueError):
                cbom.prepare(self.site, self.consumer)

    def test_index_paths_and_digest_cannot_be_changed(self):
        self.pack()
        index_file = self.package / "index.json"
        original = cbom.decode(index_file.read_bytes())
        for field, value in (("path", "../private.json"), ("path", "https://evil.invalid/evidence.json"),
                             ("sha256", "0" * 64)):
            changed = copy.deepcopy(original)
            changed["downloads"]["evidence"][field] = value
            index_file.write_bytes(cbom.encode(changed))
            with self.assertRaises(ValueError):
                cbom.prepare(self.site, self.consumer)

    def test_changed_evidence_or_manifest_rejected(self):
        self.pack()
        for name in ("evidence.json", "manifest.json"):
            file = self.package / name
            original = file.read_bytes()
            file.write_bytes(original + b" ")
            with self.subTest(name=name), self.assertRaises(ValueError):
                cbom.prepare(self.site, self.consumer)
            file.write_bytes(original)

    def test_private_or_extra_file_never_published(self):
        for name in ("run.json", ".beads", ".dolt", "private-export.json", "jvm-report.json"):
            file = self.raw / name
            file.write_text("private synthetic sentinel")
            self.reject_pack()
            file.unlink()
        self.pack()
        (self.package / "private-export.json").write_text("private synthetic sentinel")
        with self.assertRaises(ValueError):
            cbom.prepare(self.site, self.consumer)
        self.assertFalse(self.site.exists())

    def test_symlink_inputs_and_outputs_rejected(self):
        outside = self.root / "outside.json"
        for name in ("evidence.json", "manifest.json"):
            file = self.raw / name
            data = file.read_bytes()
            file.unlink()
            outside.write_bytes(data)
            file.symlink_to(outside)
            self.reject_pack()
            file.unlink()
            file.write_bytes(data)
        alias = self.root / "alias"
        alias.symlink_to(self.raw, target_is_directory=True)
        with self.assertRaises(ValueError):
            cbom.package(alias, self.package, self.producer)
        self.package.symlink_to(self.raw, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.pack()
        self.assertTrue((self.raw / "evidence.json").exists())

    def test_missing_files_rejected(self):
        file = self.raw / "manifest.json"
        file.unlink()
        self.reject_pack()
        self.save()
        self.pack()
        (self.package / "index.json").unlink()
        with self.assertRaises(ValueError):
            cbom.prepare(self.site, self.consumer)

    def test_manifest_traversal_identity_outcomes_and_digest_rejected(self):
        base = copy.deepcopy(self.manifest)
        changes = [("artifact", "../../private.json"), ("sha256", "d" * 64), ("revision", "2"),
                   ("testOutcome", "failed"), ("health", "incomplete"), ("policyOutcome", "failed"),
                   ("inputs", {"../cbom-private.json": "c" * 64})]
        for key, value in changes:
            self.manifest = {**base, key: value}
            (self.raw / "manifest.json").write_bytes(cbom.encode(self.manifest))
            with self.subTest(key=key):
                self.reject_pack()
        self.manifest = copy.deepcopy(base)
        self.manifest["descriptor"]["module"] = "other:workload"
        self.save()
        self.reject_pack()

    def test_workload_and_empty_inventory_rejected(self):
        self.evidence["metadata"]["component"]["name"] = "different-application"
        self.save()
        self.reject_pack()
        self.evidence["metadata"]["component"]["name"] = "census-demo"
        self.evidence["components"] = []
        self.save()
        self.reject_pack()

    def test_producer_only_trusted_push_and_explicit_current_maven_run(self):
        for key, value in (("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_REF", "refs/heads/main"),
                           ("GITHUB_REPOSITORY", "a/fork"), ("CENSUS_RUN_ID", ""),
                           ("CENSUS_RUN_ID", "28447294-d026-4331-a7a5-a9b91797a589")):
            original = self.producer[key]
            self.producer[key] = value
            with self.subTest(key=key):
                self.reject_pack()
            self.producer[key] = original

    def test_manual_full_develop_ci_can_package_fresh_evidence(self):
        self.producer['GITHUB_EVENT_NAME'] = 'workflow_dispatch'
        self.pack()
        self.assertEqual(cbom.decode((self.package / 'index.json').read_bytes())['sourceSha'], SHA)

    def test_duplicate_json_keys_and_malformed_documents_rejected(self):
        file = self.raw / "manifest.json"
        file.write_text('{"schema":"census.maven-evidence/1","schema":"other"}')
        self.reject_pack()
        file.write_text('{')
        self.reject_pack()

    def test_unknown_output_data_is_never_deleted(self):
        self.site.mkdir()
        private = self.site / "private-local-data.json"
        private.write_text("local sentinel")
        with self.assertRaises(ValueError):
            cbom.prepare(self.site, {})
        self.assertEqual(private.read_text(), "local sentinel")

    def test_cli_production_failure_has_no_success_exit(self):
        env = {**os.environ, "DOCS_REQUIRE_CBOM": "true", "DOCS_CBOM_DIR": ""}
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/docs/example_cbom.py'), 'prepare'],
                                cwd=self.root, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("artifact is required", result.stderr)

    def test_workflow_publication_boundaries(self):
        # These checks protect security-relevant wiring, not incidental YAML layout.
        docs = (ROOT / ".github/workflows/docs.yml").read_text()
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("github.event.workflow_run.conclusion == 'success'", docs)
        build_condition = docs.split('    if: >-')[1].split('    runs-on:')[0]
        self.assertIn("github.event_name == 'pull_request' ||", build_condition)
        self.assertIn("(github.repository == 'DevelopmentSprint/crypto-census' &&", build_condition)
        self.assertIn("github.event.workflow_run.event == 'push'", docs)
        self.assertIn("github.event.workflow_run.head_branch == 'develop'", docs)
        self.assertIn("github.event.workflow_run.head_repository.full_name == github.repository", docs)
        self.assertGreaterEqual(docs.count('git/ref/heads/develop'), 2)
        download = docs.split('- name: Download verified producer example CBOM')[1].split('- name:')[0]
        self.assertIn("run-id: ${{ steps.evidence.outputs.run_id }}", download)
        self.assertIn("artifact-ids: ${{ steps.evidence.outputs.cbom_id }}", download)
        self.assertIn("DOCS_RUN_ATTEMPT: ${{ steps.evidence.outputs.run_attempt }}", docs)
        self.assertIn("DOCS_REQUIRE_CBOM: ${{ github.event_name == 'workflow_run' }}", docs)
        self.assertIn("DOCS_SOURCE_SHA: ${{ github.event.workflow_run.head_sha }}", docs)
        self.assertIn("DOCS_EVIDENCE_SHA: ${{ steps.evidence.outputs.sha }}", docs)
        self.assertIn("run: python3 scripts/docs/evidence_source.py select", docs)
        upload = ci.split('- name: Upload exact public example evidence')[1].split('- name:')[0]
        self.assertIn("(github.event_name == 'push' || github.event_name == 'workflow_dispatch') && github.ref == 'refs/heads/develop'", upload)
        self.assertIn("github.repository == 'DevelopmentSprint/crypto-census'", upload)
        package = ci.split('- name: Package public example from this trusted build')[1].split('- name:')[0]
        self.assertIn("github.repository == 'DevelopmentSprint/crypto-census'", package)
        self.assertIn("overwrite: true", upload)
        paths = [line.strip() for line in upload.splitlines() if '${{ runner.temp }}' in line]
        self.assertEqual(paths, [f'${{{{ runner.temp }}}}/docs-cbom/{name}' for name in ('index.json', 'evidence.json', 'manifest.json')])
        self.assertIn("python3 scripts/docs/example_cbom_test.py", ci)
        self.assertIn("python3 -m unittest discover -s scripts/docs", docs)


if __name__ == '__main__':
    unittest.main()
