#!/usr/bin/env python3
"""Compose Latest with tagged release Markdown, rebuilt using the current trusted renderer."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
RELEASE = re.compile(r'v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(?:[0-9A-Za-z-]+\.)*[0-9A-Za-z-]+)?')
PRIVATE = {'.beads', '.beadroad', '.dolt', 'dolt', 'embeddeddolt', 'proxieddb', '.beads-credential-key'}


def git_env():
    # Hook Git variables must not redirect an explicit disposable/source repository.
    return {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', *args], cwd=cwd, env=git_env()).decode().strip()


def is_release(tag):
    if not RELEASE.fullmatch(tag) or 'snapshot' in tag.lower():
        return False
    prerelease = tag.partition('-')[2]
    return all(not (part.isdigit() and len(part) > 1 and part.startswith('0')) for part in prerelease.split('.'))


def release_order(version):
    numeric, _, prerelease = version['tag'].removeprefix('v').partition('-')
    identifiers = tuple((0, int(part)) if part.isdigit() else (1, part) for part in prerelease.split('.'))
    return (*map(int, numeric.split('.')), not prerelease, identifiers)


def inventory(cwd=ROOT):
    versions = []
    for tag in git('tag', '--list', cwd=cwd).splitlines():
        if not is_release(tag):
            continue
        sha = git('rev-parse', '--verify', f'refs/tags/{tag}^{{commit}}', cwd=cwd)
        # Only tags with a complete site are eligible. Fail visibly, never invent pages.
        paths = git('ls-tree', '-r', '--name-only', sha, cwd=cwd).splitlines()
        if any(PRIVATE.intersection(PurePosixPath(p).parts) for p in paths):
            raise ValueError(f'{tag}: tagged tree contains private tracker state')
        if 'docs/index.md' not in paths:
            raise ValueError(f'{tag}: release has no documentation site')
        versions.append({'tag': tag, 'sha': sha, 'path': f'/versions/{tag}/'})
    return sorted(versions, key=release_order, reverse=True)


def fingerprint(versions):
    return hashlib.sha256(json.dumps(versions, sort_keys=True).encode()).hexdigest()


def verify(versions, cwd=ROOT):
    if inventory(cwd) != versions:
        raise ValueError('Release tag inventory changed during build; rebuild before publishing')


def run(command, cwd=ROOT, env=None):
    subprocess.run(command, cwd=cwd, env=env, check=True)


def build():
    versions = inventory()
    env = {**os.environ, 'DOCS_VERSIONS': json.dumps(versions)}
    run(['npm', 'run', 'docs:build:single'], env=env)
    output = ROOT / 'docs/.vitepress/dist'
    for version in versions:
        with tempfile.TemporaryDirectory(prefix='census-release-docs-') as temporary:
            checkout = Path(temporary)
            # Export trusted tagged documentation content; use current build tooling.
            # Markdown can include Vue/SSR code: release tags are trusted repository sources.
            records = git('ls-tree', '-rz', version['sha'], '--', 'docs').split('\0')
            for record in filter(None, records):
                metadata, filename = record.split('\t', 1)
                mode, kind, object_id = metadata.split()
                path = PurePosixPath(filename)
                if mode != '100644' or kind != 'blob' or not path.is_relative_to('docs') or '..' in path.parts:
                    raise ValueError(f"{version['tag']}: nonregular documentation file: {filename}")
                if '.vitepress' in path.parts:
                    continue
                destination = checkout / filename
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(subprocess.check_output(['git', 'cat-file', 'blob', object_id], cwd=ROOT, env=git_env()))
            shutil.copytree(ROOT / 'docs/.vitepress', checkout / 'docs/.vitepress', ignore=shutil.ignore_patterns('dist', 'cache'))
            shutil.copytree(ROOT / 'scripts/docs', checkout / 'scripts/docs', ignore=shutil.ignore_patterns('__pycache__'))
            (checkout / 'node_modules').symlink_to(ROOT / 'node_modules', target_is_directory=True)
            # Latest evidence/proofs must never become historical evidence for a tag.
            archive_env = {key: value for key, value in os.environ.items() if not key.startswith('DOCS_')}
            archive_env.update(DOCS_VERSIONS=json.dumps(versions), DOCS_BASE=version['path'],
                               DOCS_RELEASE_TAG=version['tag'], DOCS_RELEASE_SHA=version['sha'])
            run(['node', 'scripts/docs/prepare.mjs'], cwd=checkout, env=archive_env)
            run([str(ROOT / 'node_modules/.bin/vitepress'), 'build', 'docs'], cwd=checkout, env=archive_env)
            # Check each archive against its own base before composing the site.
            run(['python3', 'scripts/docs/check_links.py'], cwd=checkout, env=archive_env)
            shutil.copytree(checkout / 'docs/.vitepress/dist', output / version['path'].strip('/'))
    if sum(file.stat().st_size for file in output.rglob('*') if file.is_file()) >= 1_000_000_000:
        raise ValueError('Combined documentation exceeds the GitHub Pages 1 GB site limit')
    verify(versions)
    (output / 'versions.json').write_text(json.dumps(versions, indent=2) + '\n')
    run(['python3', 'scripts/docs/check_links.py'])
    # Receipt travels as a job output, not a second state/history store.
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
            stream.write(f'tags={fingerprint(versions)}\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['build', 'fingerprint'])
    args = parser.parse_args()
    if args.command == 'build':
        build()
    else:
        print(fingerprint(inventory()))
