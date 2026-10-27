#!/usr/bin/env python3
"""Guard public snapshot publication and prepare a narrowly transformed reactor."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from central import CI_JOBS, NS, REPOSITORY, command, load_run, release_version, require

MODULE_DIRS = ('census-annotations', 'census-core', 'census-assessment', 'census-agent', 'census-delivery', 'census-cli',
               'census-maven-plugin', 'census-demo', 'census-app-servers')


def validate_context(event, repository, ref, expected_sha, actual_sha):
    require(event == 'workflow_dispatch' and repository == REPOSITORY,
            'Only a manual dispatch in the original repository may publish snapshots')
    require(ref == 'refs/heads/develop', 'Snapshots may only originate on develop')
    require(re.fullmatch(r'[0-9a-f]{40}', expected_sha) and expected_sha == actual_sha,
            'Expected SHA must match the exact selected develop commit')


def reactor_versions(directory, expected):
    """Check every project and parent, including nonpublished reactor fixtures."""
    root = Path(directory)
    declared = ET.parse(root / 'pom.xml').getroot().findall('.//m:modules/m:module', NS)
    require({module.text for module in declared} == set(MODULE_DIRS), 'Unexpected reactor modules')
    for relative in ('pom.xml', *(f'{module}/pom.xml' for module in MODULE_DIRS)):
        project = ET.parse(root / relative).getroot()
        parent = project.find('m:parent', NS)
        version = project.findtext('m:version', namespaces=NS)
        if relative == 'pom.xml':
            require(version == expected, 'Unexpected reactor root version')
        else:
            require(parent is not None and parent.findtext('m:version', namespaces=NS) == expected,
                    f'Unexpected parent version in {relative}')
            require(parent.findtext('m:groupId', namespaces=NS) == 'com.developmentsprint'
                    and parent.findtext('m:artifactId', namespaces=NS) == 'census-parent',
                    f'Unexpected parent coordinates in {relative}')
            require(version is None or version == expected, f'Unexpected project version in {relative}')


def prepare(directory):
    root = Path(directory)
    stable = release_version(root / 'pom.xml')
    reactor_versions(root, stable)
    snapshot = stable + '-SNAPSHOT'
    updates = {}
    for relative in ('pom.xml', *(f'{module}/pom.xml' for module in MODULE_DIRS)):
        path = root / relative
        source = path.read_text()
        # Exactly the root project version or child's parent version; never dependencies.
        pattern = (r'(<artifactId>census-parent</artifactId>\s*<version>)'
                   + re.escape(stable) + r'(</version>)')
        changed, count = re.subn(pattern, lambda m: m[1] + snapshot + m[2], source)
        require(count == 1, f'Expected one reactor version in {relative}')
        if relative == 'pom.xml':
            changed = changed.replace(f'<tag>v{stable}</tag>', '<tag>HEAD</tag>')
        updates[path] = changed
    for path, source in updates.items():
        path.write_text(source)
    reactor_versions(root, snapshot)
    return snapshot


def preflight(args):
    sha = command(['git', 'rev-parse', 'HEAD']).strip()
    validate_context(os.environ.get('GITHUB_EVENT_NAME'), os.environ.get('GITHUB_REPOSITORY'),
                     os.environ.get('GITHUB_REF'), args.expected_sha, sha)
    require(sha == os.environ.get('GITHUB_SHA'), 'Checkout must match workflow source')
    require(not command(['git', 'status', '--porcelain']).strip(), 'Snapshot source must be clean')
    stable = release_version('pom.xml')
    reactor_versions('.', stable)
    record = {'sourceSha': sha, 'sourceTree': command(['git', 'rev-parse', 'HEAD^{tree}']).strip(),
              'sourceBranch': 'develop', 'sourceVersion': stable, 'snapshotVersion': stable + '-SNAPSHOT',
              'ci': load_run(args.ci_run, sha, 'develop', 'ci.yml', CI_JOBS),
              'dependencies': load_run(args.dependency_run, sha, 'develop', 'dependency-check.yml', {'dependency-check'})}
    Path(args.output).write_text(json.dumps(record, indent=2) + '\n')
    print(f"Snapshot source verified: {sha} -> {record['snapshotVersion']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='action', required=True)
    check = sub.add_parser('preflight')
    for key in ('expected-sha', 'ci-run', 'dependency-run', 'output'):
        check.add_argument('--' + key, required=True)
    prepare_parser = sub.add_parser('prepare')
    prepare_parser.add_argument('--record')
    verify = sub.add_parser('verify')
    verify.add_argument('--version', required=True)
    args = parser.parse_args()
    try:
        if args.action == 'preflight':
            preflight(args)
        elif args.action == 'prepare':
            version = prepare('.')
            if args.record:
                record_path = Path(args.record)
                record = json.loads(record_path.read_text())
                require(record['snapshotVersion'] == version, 'Prepared version must match source evidence')
                record['preparedPoms'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                                          for p in [Path('pom.xml'), *(Path(m) / 'pom.xml' for m in MODULE_DIRS)]}
                record_path.write_text(json.dumps(record, indent=2) + '\n')
            print(version)
        else:
            require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT', args.version), 'Snapshot version required')
            reactor_versions('.', args.version)
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, f'Snapshot check failed: {error}\n')


if __name__ == '__main__':
    main()
