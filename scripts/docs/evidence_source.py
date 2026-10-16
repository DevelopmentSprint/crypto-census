#!/usr/bin/env python3
"""Select a trusted CI evidence producer without relabeling its measured source."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ci_changes import code_equivalent, commit, git

REPOSITORY = 'DevelopmentSprint/crypto-census'
WORKFLOW = '.github/workflows/ci.yml'
REFRESH = 'No reusable live CI evidence was found. Run CI with workflow_dispatch on develop (gh workflow run ci.yml --ref develop), wait for its full build, then retry publication.'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def api(endpoint):
    # Fixed host and repository. Never follow API URLs supplied by run/artifact metadata.
    return json.loads(subprocess.check_output(['gh', 'api', '--hostname', 'github.com', endpoint], text=True))


def positive(value):
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def valid_run(run, workflow_id):
    return (positive(run.get('id')) and positive(run.get('run_attempt')) and
            run.get('workflow_id') == workflow_id and run.get('path') == WORKFLOW and
            run.get('repository', {}).get('full_name') == REPOSITORY and
            run.get('head_repository', {}).get('full_name') == REPOSITORY and
            run.get('head_branch') == 'develop' and run.get('event') in ('push', 'workflow_dispatch') and
            run.get('status') == 'completed' and run.get('conclusion') == 'success' and
            run.get('html_url') == f"https://github.com/{REPOSITORY}/actions/runs/{run['id']}")


def live_artifacts(items, run, now):
    found = {}
    for name in ('docs-coverage', 'docs-cbom'):
        matches = [item for item in items if item.get('name') == name]
        if len(matches) != 1:
            return None
        item = matches[0]
        origin = item.get('workflow_run', {})
        try:
            expires = datetime.fromisoformat(item['expires_at'].replace('Z', '+00:00'))
        except (KeyError, ValueError, TypeError):
            return None
        if (item.get('expired') is not False or not positive(item.get('id')) or expires <= now or
                origin.get('id') != run['id'] or origin.get('head_sha') != run.get('head_sha') or
                origin.get('head_branch') != 'develop'):
            return None
        found[name] = item['id']
    return found


def paginated(fetch, endpoint, key, limit=10):
    for page in range(1, limit + 1):
        separator = '&' if '?' in endpoint else '?'
        response = fetch(f'{endpoint}{separator}per_page=100&page={page}')
        items = response[key]
        require(isinstance(items, list), 'Invalid GitHub API page')
        yield from items
        if len(items) < 100:
            return
    raise ValueError('CI evidence search exceeded its page limit. ' + REFRESH)


def select(site_sha, triggering_run_id, fetch=api, equivalent=code_equivalent, now=None):
    require(re.fullmatch(r'[0-9a-f]{40}', site_sha or ''), 'Invalid site source SHA')
    require(re.fullmatch(r'[1-9][0-9]*', str(triggering_run_id)), 'Invalid triggering CI run')
    now = now or datetime.now(timezone.utc)
    root = f'repos/{REPOSITORY}'
    workflow = fetch(f'{root}/actions/workflows/ci.yml')
    require(workflow.get('path') == WORKFLOW and positive(workflow.get('id')), 'Unexpected CI workflow identity')
    current = fetch(f'{root}/actions/runs/{triggering_run_id}')
    require(valid_run(current, workflow['id']) and current.get('head_sha') == site_sha,
            'Trigger must be successful original-repository develop CI for the exact site source')

    def candidate(run):
        if not valid_run(run, workflow['id']):
            return None
        source = run.get('head_sha')
        try:
            if not equivalent(source, site_sha):
                return None
        except (ValueError, OSError, subprocess.CalledProcessError):
            return None
        artifacts = list(paginated(fetch, f"{root}/actions/runs/{run['id']}/artifacts", 'artifacts'))
        ids = live_artifacts(artifacts, run, now)
        if not ids:
            return None
        return {'schemaVersion': 1, 'repository': REPOSITORY, 'siteSourceSha': site_sha,
                'evidenceSourceSha': source, 'runId': str(run['id']), 'runAttempt': str(run['run_attempt']),
                'runUrl': run['html_url'], 'reused': source != site_sha,
                'coverageArtifactId': str(ids['docs-coverage']), 'cbomArtifactId': str(ids['docs-cbom'])}

    exact = candidate(current)
    if exact:
        return exact
    runs = paginated(fetch, f"{root}/actions/workflows/{workflow['id']}/runs?branch=develop&status=success", 'workflow_runs')
    for summary in runs:
        if not positive(summary.get('id')) or summary['id'] >= current['id']:
            continue
        # Fetch the canonical run record, not merely the list entry supplied by a search.
        run = fetch(f"{root}/actions/runs/{summary['id']}")
        result = candidate(run)
        if result:
            return result
    raise ValueError(REFRESH)


def verify(env):
    site = env.get('DOCS_SOURCE_SHA')
    source = env.get('DOCS_EVIDENCE_SHA') or site
    proof_path = env.get('DOCS_EVIDENCE_PROOF')
    if source == site and not proof_path:
        return  # Existing exact-source validators still validate both payloads.
    require(proof_path, 'Reused evidence requires the verified producer selection record')
    proof = json.loads(Path(proof_path).read_text())
    fields = {'schemaVersion', 'repository', 'siteSourceSha', 'evidenceSourceSha', 'runId', 'runAttempt',
              'runUrl', 'reused', 'coverageArtifactId', 'cbomArtifactId'}
    require(isinstance(proof, dict) and set(proof) == fields, 'Unexpected evidence selection record fields')
    require(proof.get('schemaVersion') == 1 and proof.get('repository') == REPOSITORY and
            proof.get('siteSourceSha') == site and proof.get('evidenceSourceSha') == source and
            proof.get('runId') == env.get('DOCS_RUN_ID') and
            proof.get('runAttempt') == env.get('DOCS_RUN_ATTEMPT') and
            proof.get('runUrl') == env.get('DOCS_RUN_URL') and proof.get('reused') is (source != site),
            'Evidence selection record does not match the site and producer identity')
    for field in ('runId', 'runAttempt', 'coverageArtifactId', 'cbomArtifactId'):
        require(isinstance(proof[field], str) and re.fullmatch(r'[1-9][0-9]*', proof[field]), 'Invalid evidence selection identity')
    require(proof['runUrl'] == f"https://github.com/{REPOSITORY}/actions/runs/{proof['runId']}", 'Unexpected producer URL')
    require(git('rev-parse', 'HEAD').decode().strip() == commit(site), 'Site checkout does not match DOCS_SOURCE_SHA')
    require(code_equivalent(source, site), 'Evidence source is not an ancestor with only documentation changes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('select', 'verify'))
    args = parser.parse_args()
    if args.command == 'verify':
        verify(os.environ)
        return
    require(os.environ.get('GITHUB_REPOSITORY') == REPOSITORY, 'Unexpected publishing repository')
    site = os.environ.get('DOCS_SOURCE_SHA')
    require(git('rev-parse', 'HEAD').decode().strip() == commit(site), 'Site checkout differs from triggering source')
    result = select(site, os.environ.get('DOCS_TRIGGER_RUN_ID'))
    Path('.docs-evidence.json').write_text(json.dumps(result, indent=2) + '\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in (('sha', result['evidenceSourceSha']), ('run_id', result['runId']),
                           ('run_attempt', result['runAttempt']), ('run_url', result['runUrl']),
                           ('coverage_id', result['coverageArtifactId']), ('cbom_id', result['cbomArtifactId'])):
            output.write(f'{key}={value}\n')
    print(f"Site {site}: using original evidence from {result['evidenceSourceSha']} (CI run {result['runId']}).")


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, TypeError, subprocess.CalledProcessError) as error:
        sys.exit(f'Evidence selection rejected: {error}')
