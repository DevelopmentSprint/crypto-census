#!/usr/bin/env python3
"""One conservative documentation-only boundary for CI, hooks and evidence reuse."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

SHA = re.compile(r"[0-9a-f]{40}")


def git(*args):
    return subprocess.check_output(["git", *args], stderr=subprocess.DEVNULL)


def commit(value):
    if not isinstance(value, str) or not SHA.fullmatch(value) or value == '0' * 40:
        raise ValueError('Missing or invalid commit SHA')
    git('cat-file', '-e', value + '^{commit}')
    return value


def docs_path(path):
    # Bytes are deliberate: Git paths can contain newlines or invalid UTF-8.
    return path in (b'package.json', b'package-lock.json') or path.startswith((b'docs/', b'scripts/docs/'))


def changed_paths(base, head):
    # No rename detection: both old (deleted) and new (added) names must qualify.
    result = git('diff', '--name-only', '--no-renames', '-z', commit(base), commit(head), '--')
    return result.split(b'\0')[:-1]


def safe_diff(base, head):
    if not all(docs_path(p) for p in changed_paths(base, head)):
        return False
    # A path allowlist does not authorize symlink or submodule substitutions.
    records = git('diff', '--raw', '--no-renames', '-z', commit(base), commit(head), '--').split(b'\0')
    for record in records[0:-1:2]:
        modes = record.split()[:2]
        if len(modes) != 2 or modes[0].lstrip(b':') not in (b'000000', b'100644', b'100755') or modes[1] not in (b'000000', b'100644', b'100755'):
            return False
    return True


def code_equivalent(base, head):
    commit(base)
    commit(head)
    git('merge-base', '--is-ancestor', base, head)
    return safe_diff(base, head)


def history_docs_only(base, head):
    commit(base)
    commit(head)
    git('merge-base', '--is-ancestor', base, head)
    commits = git('rev-list', f'{base}..{head}').decode().splitlines()
    return bool(commits) and commits_docs_only(commits) and code_equivalent(base, head)


def commits_docs_only(commits):
    for revision in commits:
        parents = git('rev-list', '--parents', '-n', '1', commit(revision)).decode().split()[1:]
        if not parents:  # An initial commit has no verified prior code baseline.
            return False
        for parent in parents:
            if not safe_diff(parent, revision):
                return False
    return True


def classify_event(event, payload, head):
    if event == 'workflow_dispatch' or event == 'schedule':
        return 'full'
    if event == 'push':
        if payload.get('after') != head:
            return 'full'
        return 'docs-only' if history_docs_only(payload.get('before'), head) else 'full'
    if event == 'pull_request':
        pr = payload.get('pull_request', {})
        base, source = commit(pr.get('base', {}).get('sha')), commit(pr.get('head', {}).get('sha'))
        # The checked-out synthetic merge is authoritative, including base-branch changes.
        return 'docs-only' if history_docs_only(base, commit(head)) and code_equivalent(source, head) else 'full'
    return 'full'


def classify_push(remote, lines):
    if len(lines) != 1:
        return 'full'
    if remote not in git('remote').decode().splitlines():
        return 'full'
    refs = git('for-each-ref', '--format=%(refname)', f'refs/remotes/{remote}/').splitlines()
    if not refs or git('status', '--porcelain', '--untracked-files=no'):
        return 'full'
    checked_out = git('rev-parse', 'HEAD').decode().strip()
    revisions = set()
    for line in lines:
        values = line.split()
        if len(values) != 4:
            return 'full'
        local_ref, local, remote_ref, previous = values
        if remote_ref != local_ref:
            return 'full'
        if not SHA.fullmatch(local) or not SHA.fullmatch(previous):
            return 'full'
        if local == '0' * 40:
            continue
        if not local_ref.startswith(('refs/heads/feature/', 'refs/heads/integration/')) and local_ref not in ('refs/heads/develop', 'refs/heads/main'):
            return 'full'
        if commit(local) != checked_out:
            return 'full'  # The hook can only validate this checkout's content.
        args = ['rev-list', local, '--not']
        if previous != '0' * 40:
            git('merge-base', '--is-ancestor', commit(previous), local)
            args.append(previous)
        args.append(f'--remotes={remote}')
        revisions.update(git(*args).decode().splitlines())
    return 'docs-only' if revisions and commits_docs_only(revisions) else 'full'


def required(results):
    classify = results.get('classify', {})
    mode = classify.get('outputs', {}).get('mode')
    if classify.get('result') != 'success' or mode not in ('full', 'docs-only'):
        return False
    expected = 'success' if mode == 'full' else 'skipped'
    return all(results.get(job, {}).get('result') == expected for job in ('build', 'dogfood', 'app-servers', 'private-tracker', 'advisory-demo', 'guided-report'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('event')
    push = sub.add_parser('pre-push')
    push.add_argument('remote')
    sub.add_parser('required')
    args = parser.parse_args()
    if args.command == 'required':
        if not required(json.loads(os.environ['CI_RESULTS'])):
            sys.exit('Required CI failed: classifier or expected Java job outcomes did not pass.')
        print('Required CI passed: all expected outcomes match the classified scope.')
        return
    try:
        if args.command == 'pre-push':
            mode = classify_push(args.remote, sys.stdin.read().splitlines())
        else:
            payload = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
            mode = classify_event(os.environ['GITHUB_EVENT_NAME'], payload, os.environ.get('GITHUB_SHA'))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, TypeError):
        print('Scope could not be verified from complete Git history; requiring the full build.', file=sys.stderr)
        mode = 'full'  # Ambiguity never grants permission to skip Java checks.
    print(mode)
    if args.command == 'event' and os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write(f'mode={mode}\n')


if __name__ == '__main__':
    main()
