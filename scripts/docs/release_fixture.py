#!/usr/bin/env python3
"""Build and browser-check a synthetic tagged site in a disposable Git repository."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
env = {key: value for key, value in os.environ.items() if not key.startswith(('GIT_', 'DOCS_'))}
env.update(GIT_AUTHOR_NAME='Fixture', GIT_AUTHOR_EMAIL='fixture@example.invalid',
           GIT_COMMITTER_NAME='Fixture', GIT_COMMITTER_EMAIL='fixture@example.invalid',
           GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, DOCS_TEST_RELEASE_TAG='v9.9.9')


def run(args, cwd):
    subprocess.run(args, cwd=cwd, env=env, check=True)


with tempfile.TemporaryDirectory(prefix='census-tagged-site-test-') as temporary:
    fixture = Path(temporary)
    shutil.copytree(ROOT / 'docs', fixture / 'docs', ignore=shutil.ignore_patterns('cache', 'dist', 'badges', 'example-cbom', 'site-evidence.json'))
    shutil.copytree(ROOT / 'scripts/docs', fixture / 'scripts/docs', ignore=shutil.ignore_patterns('__pycache__'))
    for filename in ['package.json', 'package-lock.json']:
        shutil.copyfile(ROOT / filename, fixture / filename)
    (fixture / 'node_modules').symlink_to(ROOT / 'node_modules', target_is_directory=True)
    sentinel = fixture / 'docs/getting-started/installation.md'
    sentinel.write_text(sentinel.read_text() + '\nTagged release fixture content.\n')
    run(['git', 'init', '-q'], fixture)
    run(['git', 'add', 'docs'], fixture)
    run(['git', 'commit', '-qm', 'Synthetic tagged documentation fixture; never published'], fixture)
    run(['git', 'tag', env['DOCS_TEST_RELEASE_TAG']], fixture)
    run(['git', 'tag', 'v9.9.10-SNAPSHOT'], fixture)
    sentinel.write_text(sentinel.read_text().replace('Tagged release fixture content.', 'Latest-only fixture content.'))
    run(['git', 'commit', '-qam', 'Synthetic untagged content change'], fixture)
    run(['npm', 'run', 'docs:build'], fixture)
    run(['npm', 'run', 'docs:test', '--', 'versions.spec.ts'], fixture)
