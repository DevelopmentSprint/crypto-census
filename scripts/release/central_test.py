#!/usr/bin/env python3
"""Release boundary regressions; all signing tests use disposable keys, never release credentials."""

import copy
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import central


SHA = "a" * 40
FINGERPRINT = "B" * 40


class ProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.run = {"id": 42, "repository": {"full_name": central.REPOSITORY},
                    "head_repository": {"full_name": central.REPOSITORY},
                    "path": ".github/workflows/ci.yml", "head_sha": SHA,
                    "head_branch": "release/0.1.0", "event": "push", "status": "completed",
                    "conclusion": "success", "run_attempt": 2}
        self.jobs = [{"name": name, "status": "completed", "conclusion": "success", "run_attempt": 2}
                     for name in central.CI_JOBS]

    def validate(self):
        central.validate_run(self.run, self.jobs, SHA, "release/0.1.0", "ci.yml", central.CI_JOBS)

    def test_accepts_exact_successful_full_run(self):
        self.validate()

    def test_manual_original_release_branches_only(self):
        for ref in ("refs/heads/main", "refs/heads/release/0.1.0"):
            central.validate_context("workflow_dispatch", central.REPOSITORY, ref, SHA, SHA)
        for event, repo, ref, expected, actual in (
            ("push", central.REPOSITORY, "refs/heads/main", SHA, SHA),
            ("workflow_dispatch", "fork/repo", "refs/heads/main", SHA, SHA),
            ("workflow_dispatch", central.REPOSITORY, "refs/heads/develop", SHA, SHA),
            ("workflow_dispatch", central.REPOSITORY, "refs/tags/v0.1.0", SHA, SHA),
            ("workflow_dispatch", central.REPOSITORY, "refs/heads/release/a/b", SHA, SHA),
            ("workflow_dispatch", central.REPOSITORY, "refs/heads/main", "a" * 7, SHA),
            ("workflow_dispatch", central.REPOSITORY, "refs/heads/main", SHA, "b" * 40),
        ):
            with self.subTest(ref=ref, event=event), self.assertRaises(ValueError):
                central.validate_context(event, repo, ref, expected, actual)

    def test_rejects_wrong_source_workflow_origin_event_and_state(self):
        original = copy.deepcopy(self.run)
        for key, value in (("head_sha", "b" * 40), ("head_branch", "develop"),
                           ("path", ".github/workflows/other.yml"),
                           ("event", "pull_request"), ("event", "schedule"),
                           ("conclusion", "failure"), ("status", "in_progress"),
                           ("repository", {"full_name": "fork/repo"}),
                           ("head_repository", {"full_name": "fork/repo"})):
            self.run = {**original, key: value}
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.validate()

    def test_docs_only_or_skipped_failed_cancelled_missing_duplicate_stale_jobs_fail(self):
        original = copy.deepcopy(self.jobs)
        for conclusion in ("skipped", "failure", "cancelled", None):
            self.jobs = copy.deepcopy(original)
            self.jobs[0]["conclusion"] = conclusion
            with self.subTest(conclusion=conclusion), self.assertRaises(ValueError):
                self.validate()
        for jobs in (original[1:], original + [original[0]],
                     [{**j, "run_attempt": 1} for j in original]):
            self.jobs = jobs
            with self.assertRaises(ValueError):
                self.validate()

    def test_dependency_job_must_execute(self):
        self.run["path"] = ".github/workflows/dependency-check.yml"
        with self.assertRaises(ValueError):
            central.validate_run(self.run, [{"name": "dependency-check", "conclusion": "skipped"}],
                                 SHA, "release/0.1.0", "dependency-check.yml", {"dependency-check"})

    def test_attempt_specific_jobs_and_rerun_race(self):
        responses = [self.run, {"jobs": self.jobs, "total_count": len(self.jobs)}, self.run]
        with patch.object(central, "api", side_effect=responses) as api:
            result = central.load_run("42", SHA, "release/0.1.0", "ci.yml", central.CI_JOBS)
            self.assertEqual(result["runAttempt"], 2)
            self.assertIn("/attempts/2/jobs?", api.call_args_list[1].args[0])
        responses[-1] = {**self.run, "run_attempt": 3, "conclusion": None}
        with patch.object(central, "api", side_effect=responses), self.assertRaises(ValueError):
            central.load_run("42", SHA, "release/0.1.0", "ci.yml", central.CI_JOBS)

    def test_manual_publication_configuration_is_required(self):
        pom = Path(__file__).resolve().parents[2] / "pom.xml"
        # Ordinary develop CI must also pass after the next SNAPSHOT version bump.
        candidate = central.ET.parse(pom)
        candidate.find("m:version", central.NS).text = "1.2.3"
        candidate.find("m:scm/m:tag", central.NS).text = "v1.2.3"
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "pom.xml"
            candidate.write(path)
            self.assertEqual(central.release_version(path), "1.2.3")
            prefix = ("m:profiles/m:profile[m:id='release']/m:build/m:plugins/"
                      "m:plugin[m:artifactId='central-publishing-maven-plugin']/m:configuration/")
            for selector, value in ((prefix + "m:autoPublish", "true"),
                                    (prefix + "m:waitUntil", "published"),
                                    ("m:version", "1.2.4-SNAPSHOT")):
                mutated = copy.deepcopy(candidate)
                mutated.find(selector, central.NS).text = value
                mutated.write(path)
                with self.subTest(value=value), self.assertRaises(ValueError):
                    central.release_version(path)


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.files = {}
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/LICENSE", "Example license")
        for name in central.expected_artifacts("0.1.0"):
            self.files[name] = jar.getvalue() if name.endswith(".jar") else b"<project/>"
            self.files[name + ".asc"] = b"test signature"
            self.files[name + ".sha256"] = hashlib.sha256(self.files[name]).hexdigest().encode()

    def test_context_modules_are_required_published_artifacts(self):
        for module in ('census-annotations', 'census-assessment'):
            stem = f'com/developmentsprint/{module}/0.1.0/{module}-0.1.0'
            for suffix in ('.pom', '.jar', '-sources.jar', '-javadoc.jar'):
                with self.subTest(module=module, suffix=suffix):
                    self.assertIn(stem + suffix, central.expected_artifacts('0.1.0'))
                    missing = dict(self.files)
                    del missing[stem + suffix]
                    with self.assertRaises(ValueError):
                        self.inspect(missing)

    def inspect(self, files=None, verify=None):
        bundle = io.BytesIO()
        with zipfile.ZipFile(bundle, "w") as archive:
            for name, data in (files or self.files).items():
                archive.writestr(name, data)
        bundle.seek(0)
        return central.inspect_bundle(bundle, "0.1.0", FINGERPRINT, verify or (lambda *_: None))

    def test_exact_30_artifacts_are_verified_and_hashed(self):
        with patch.object(central, "gpg_verify") as verify:
            result = self.inspect(verify=verify)
        self.assertEqual(len(result), 30)
        self.assertEqual(verify.call_count, 30)
        self.assertEqual(len({r["path"] for r in result}), 30)

    def test_rejects_extra_paths_dumps_and_demo_artifacts(self):
        for name in (".beads/dump.sql", "renamed-backup.zip", "../secret", "settings.xml",
                     "com/developmentsprint/census-demo/0.1.0/census-demo-0.1.0.jar"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.inspect({**self.files, name: b"private"})

    def test_rejects_missing_signature_and_checksum_mismatch(self):
        files = dict(self.files)
        del files[next(n for n in files if n.endswith(".asc"))]
        with self.assertRaises(ValueError):
            self.inspect(files)
        files = dict(self.files)
        files[next(n for n in files if n.endswith(".sha256"))] = b"0" * 64
        with self.assertRaises(ValueError):
            self.inspect(files)

    def test_rejects_private_tracker_inside_jar(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("nested/.dolt/data", "private")
        files = dict(self.files)
        files[next(n for n in files if n.endswith(".jar"))] = jar.getvalue()
        with self.assertRaisesRegex(ValueError, "Private tracker"):
            self.inspect(files)

    def test_signature_failure_blocks_evidence(self):
        with patch.object(central, "gpg_verify", side_effect=ValueError("bad signature")) as verify:
            with self.assertRaisesRegex(ValueError, "bad signature"):
                self.inspect(verify=verify)


@unittest.skipUnless(shutil.which("gpg"), "GnuPG required for disposable signing integration test")
class SigningTest(unittest.TestCase):
    def test_protected_export_import_sign_and_verify(self):
        # macOS's default temp path can exceed the GPG agent Unix socket limit.
        with tempfile.TemporaryDirectory(prefix="cr-", dir="/tmp" if sys.platform == "darwin" else None) as temp:
            root = Path(temp)
            source, destination = root / "source", root / "destination"
            source.mkdir(mode=0o700)
            # Public, disposable test credential only; never used for organization signing.
            phrase = "disposable-test-passphrase"
            env = {**os.environ, "GNUPGHOME": str(source)}
            def gpg(*args, data=None):
                result = subprocess.run(["gpg", "--batch", "--pinentry-mode", "loopback", *args],
                                        input=data, env=env, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr.decode())
                return result.stdout
            try:
                gpg("--passphrase-fd", "0", "--quick-generate-key", "Disposable Release Test <test@example.invalid>",
                    "rsa2048", "cert,sign", "1d", data=(phrase + "\n").encode())
                fingerprint = next(line.split(":")[9] for line in gpg("--with-colons", "--list-secret-keys").decode().splitlines()
                                   if line.startswith("fpr:"))
                key = gpg("--passphrase-fd", "0", "--armor", "--export-secret-keys", fingerprint,
                          data=(phrase + "\n").encode()).decode()
                env.update({"GNUPGHOME": str(destination), "GPG_PRIVATE_KEY": key,
                            "MAVEN_GPG_PASSPHRASE": phrase, "GPG_FINGERPRINT": fingerprint})
                result = subprocess.run([sys.executable, str(Path(__file__).with_name("signing.py"))],
                                        env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn(phrase, result.stdout + result.stderr)
                self.assertNotIn("BEGIN PGP PRIVATE KEY", result.stdout + result.stderr)
                self.assertIn(fingerprint, result.stdout)
                payload = destination / "signing-check.txt"
                with patch.dict(os.environ, {"GNUPGHOME": str(destination)}):
                    central.gpg_verify(payload.read_bytes(), Path(str(payload) + ".asc").read_bytes(), fingerprint)
                    with self.assertRaises(ValueError):
                        central.gpg_verify(b"tampered", Path(str(payload) + ".asc").read_bytes(), fingerprint)
                    with self.assertRaises(ValueError):
                        central.gpg_verify(payload.read_bytes(), Path(str(payload) + ".asc").read_bytes(), "0" * 40)
            finally:
                for home in (source, destination):
                    subprocess.run(["gpgconf", "--homedir", str(home), "--kill", "all"], capture_output=True)


if __name__ == "__main__":
    unittest.main()
