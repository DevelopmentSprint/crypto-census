"""Prove the pre-push hook isolates test suites from the repository Git exports to hooks.

Git exports GIT_DIR and related variables to hooks, most visibly when pushing from a linked
worktree. A test that creates a scratch repository with plain ``git`` calls would otherwise
commit into, and reconfigure, the repository being pushed. Everything here runs in a
disposable repository; no real tracker data or project history is touched.
"""
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]

RECORDING_TEST = textwrap.dedent("""\
    import json
    import os
    import unittest


    class RecordEnvironmentTest(unittest.TestCase):
        def test_record(self):
            names = [name for name in os.environ if name.startswith("GIT_")]
            with open(os.environ["CENSUS_HOOK_RECORD"], "w") as handle:
                json.dump({name: os.environ[name] for name in names}, handle)
    """)


class HookEnvironmentTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="census-hook-env-")
        self.addCleanup(directory.cleanup)
        self.base = Path(directory.name)
        self.repo = self.base / "repo"
        self.record = self.base / "record.json"
        self.env = os.environ.copy()
        for name in list(self.env):
            if name.startswith("GIT_"):
                del self.env[name]
        self.env["CENSUS_HOOK_RECORD"] = str(self.record)
        self.env["PATH"] = f"{self.stub_tools()}{os.pathsep}{self.env['PATH']}"

        self.repo.mkdir()
        self.git(self.repo, "init", "-q", "-b", "develop")
        self.git(self.repo, "config", "user.name", "Synthetic Hook Test")
        self.git(self.repo, "config", "user.email", "hook@example.invalid")
        self.git(self.repo, "config", "commit.gpgsign", "false")
        self.git(self.repo, "config", "core.hooksPath", ".githooks")
        (self.repo / ".githooks").mkdir()
        shutil.copy(ROOT / ".githooks/pre-push", self.repo / ".githooks")
        (self.repo / "scripts/docs").mkdir(parents=True)
        shutil.copy(ROOT / "scripts/check-private-tracker.sh", self.repo / "scripts")
        shutil.copy(ROOT / ".gitignore", self.repo)
        # The classification itself is covered by ci_changes_test.py; force the docs path here.
        (self.repo / "scripts/ci_changes.py").write_text("print('docs-only')\n")
        (self.repo / "scripts/coverage_badges_test.py").write_text("")
        (self.repo / "scripts/docs/record_test.py").write_text(RECORDING_TEST)
        self.git(self.repo, "add", "-A")
        self.git(self.repo, "commit", "-qm", "synthetic hook fixture")
        self.git(self.repo, "init", "-q", "--bare", str(self.base / "remote.git"))
        self.git(self.repo, "remote", "add", "origin", str(self.base / "remote.git"))
        self.git(self.repo, "push", "-q", "--no-verify", "origin", "develop")
        self.git(self.repo, "fetch", "-q", "origin")

    def stub_tools(self):
        """npm and node stand in for the documentation toolchain; only the Python suite matters."""
        tools = self.base / "bin"
        tools.mkdir()
        for name in ("npm", "node"):
            tool = tools / name
            tool.write_text("#!/bin/sh\nexit 0\n")
            tool.chmod(tool.stat().st_mode | stat.S_IXUSR)
        return tools

    def git(self, cwd, *args):
        return subprocess.run(["git", *args], cwd=cwd, env=self.env, capture_output=True,
                              check=True, text=True).stdout.strip()

    def test_docs_suites_do_not_inherit_the_pushing_repository(self):
        worktree = self.base / "worktree"
        self.git(self.repo, "worktree", "add", "-q", "-b", "topic", str(worktree))
        (worktree / "README.md").write_text("synthetic documentation change\n")
        self.git(worktree, "add", "README.md")
        self.git(worktree, "commit", "-qm", "synthetic documentation change")

        push = subprocess.run(["git", "push", "origin", "topic"], cwd=worktree, env=self.env,
                              capture_output=True, text=True)

        self.assertEqual(push.returncode, 0, push.stderr)
        self.assertIn("documentation-only", push.stdout + push.stderr)
        recorded = json.loads(self.record.read_text())
        leaked = sorted(set(recorded) & set(self.local_env_vars()))
        self.assertEqual(leaked, [], f"pre-push exported repository variables to tests: {recorded}")

    def local_env_vars(self):
        return self.git(self.repo, "rev-parse", "--local-env-vars").split()


if __name__ == "__main__":
    unittest.main()
