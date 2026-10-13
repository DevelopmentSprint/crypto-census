"""Exercise the public-repository boundary with disposable Git indexes, never real tracker data."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PrivateTrackerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="census-private-tracker-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = os.environ.copy()
        for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR"):
            self.env.pop(name, None)
        self.git("init", "-q")
        self.git("config", "core.hooksPath", ".githooks")
        self.git("config", "user.name", "Synthetic Guard Test")
        self.git("config", "user.email", "guard@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("remote", "add", "origin", "https://example.invalid/never-contacted.git")
        (self.root / "scripts").mkdir()
        shutil.copy(ROOT / "scripts/check-private-tracker.sh", self.root / "scripts")
        shutil.copy(ROOT / ".gitignore", self.root)

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, env=self.env,
                              capture_output=True, check=True)

    def stage(self, path):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text("synthetic fixture; no real tracker data\n")
        self.git("add", "-f", "--", path)

    def guard(self):
        return subprocess.run(["bash", "scripts/check-private-tracker.sh"],
                              cwd=self.root, env=self.env, capture_output=True)

    def test_normal_source_and_policy_files_pass(self):
        for path in ("README.md", "AGENTS.md", "scripts/dolt-policy.md"):
            self.stage(path)
        self.assertEqual(self.guard().returncode, 0)

    def test_forced_private_paths_fail_and_untracking_preserves_files(self):
        for path in (".beads/config.yaml", ".beads/dolt/database/chunk", ".beadroad/session.json",
                     ".dolt/noms/manifest", "dolt/data", "embeddeddolt/data", "proxieddb/data",
                     ".beads-credential-key", "nested/.beads/export.jsonl",
                     "nested folder/.dolt/a\nchunk"):
            with self.subTest(path=path):
                self.stage(path)
                self.assertEqual(self.guard().returncode, 1)
                self.git("rm", "--cached", "--", path)
                self.assertTrue((self.root / path).is_file())
                self.assertEqual(self.guard().returncode, 0)

    def test_ignore_rules_cover_local_and_nested_state(self):
        for path in (".beads/config.yaml", ".beadroad/state", ".dolt/data", "dolt/data",
                     "embeddeddolt/data", "proxieddb/data", ".beads-credential-key",
                     "nested/.beads/export.jsonl", "nested/.dolt/data"):
            with self.subTest(path=path):
                self.git("check-ignore", "--no-index", "--", path)

    def commit(self):
        self.git("commit", "-qm", "synthetic boundary fixture")
        return self.git("rev-parse", "HEAD").stdout.decode().strip()

    def push_guard(self, sha, remote_sha="0" * 40, ref="refs/heads/topic", remote="origin"):
        return subprocess.run(["bash", "scripts/check-private-tracker.sh", "--pre-push", remote],
                              cwd=self.root, env=self.env, capture_output=True,
                              input=f"{ref} {sha} {ref} {remote_sha}\n".encode())

    def test_outgoing_history_blocks_removed_private_data_on_branch_or_tag(self):
        self.stage("README.md")
        public = self.commit()
        self.git("update-ref", "refs/remotes/origin/develop", public)
        self.stage(".beads/synthetic.jsonl")
        self.commit()
        self.git("rm", "--cached", "--", ".beads/synthetic.jsonl")
        deleted_tip = self.commit()
        self.assertEqual(self.guard().returncode, 0)
        self.assertNotEqual(self.push_guard(deleted_tip, public).returncode, 0)
        # Current index is unrelated to the branch/tag being pushed.
        self.git("checkout", "--detach", public)
        self.assertNotEqual(self.push_guard(deleted_tip).returncode, 0)
        self.git("-c", "tag.gpgsign=false", "tag", "-a", "synthetic", "-m", "test", deleted_tip)
        tag = self.git("rev-parse", "refs/tags/synthetic").stdout.decode().strip()
        self.assertNotEqual(self.push_guard(tag, ref="refs/tags/synthetic").returncode, 0)

    def test_existing_public_metadata_does_not_require_history_rewrite(self):
        self.stage(".beads/config.yaml")
        public = self.commit()
        self.git("update-ref", "refs/remotes/origin/develop", public)
        self.git("rm", "--cached", "--", ".beads/config.yaml")
        clean = self.commit()
        self.assertEqual(self.push_guard(clean, public).returncode, 0)
        self.assertEqual(self.push_guard(clean).returncode, 0)
        self.assertTrue((self.root / ".beads/config.yaml").is_file())
        self.assertEqual(self.push_guard("0" * 40, public).returncode, 0)

    def test_unknown_remote_history_and_raw_object_tags_fail_closed(self):
        self.stage("README.md")
        tip = self.commit()
        self.assertNotEqual(self.push_guard(tip, "1" * 40).returncode, 0)
        self.assertNotEqual(self.push_guard(tip, remote="https://example.invalid/direct.git").returncode, 0)
        blob = self.git("hash-object", "-w", "README.md").stdout.decode().strip()
        self.assertNotEqual(self.push_guard(blob, ref="refs/tags/raw").returncode, 0)

    def test_hooks_reject_private_data_before_fast_path_or_build(self):
        self.stage(".beads/interactions.jsonl")
        self.stage("README.md")
        for hook in ("pre-commit", "pre-push"):
            with self.subTest(hook=hook):
                result = subprocess.run(["bash", str(ROOT / ".githooks" / hook)],
                                        cwd=self.root, env=self.env, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b"Private tracker state", result.stderr)
                self.assertNotIn(b"static analysis", result.stdout)
                self.assertNotIn(b"full build", result.stdout)


if __name__ == "__main__":
    unittest.main()
