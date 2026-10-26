#!/usr/bin/env bash
# Reject private tracker paths in the index or unpublished outgoing commit trees.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
paths_file=$(mktemp "${TMPDIR:-/tmp}/census-private-paths.XXXXXX")
commits_file=$(mktemp "${TMPDIR:-/tmp}/census-private-commits.XXXXXX")
trap 'rm -f "$paths_file" "$commits_file"' EXIT

private_path() {
  case "/$1/" in
    */.beads/*|*/.beadroad/*|*/.dolt/*|*/dolt/*|*/embeddeddolt/*|*/proxieddb/*|*/.beads-credential-key/*|*/.beads.gate.lock/*) return 0 ;;
    *) return 1 ;;
  esac
}

check_paths() {
  local label="$1" path failed=0
  while IFS= read -r -d '' path; do
    if private_path "$path"; then
      printf 'private-tracker: %s contains forbidden path: %s\n' "$label" "$path" >&2
      failed=1
    fi
  done
  return "$failed"
}

if [ "${1:-}" != "--pre-push" ]; then
  if [ "$#" -ne 0 ]; then
    echo 'Usage: check-private-tracker.sh [--pre-push named-remote]' >&2
    exit 2
  fi
  git ls-files --cached -z > "$paths_file"
  check_paths index < "$paths_file"
  exit
fi

remote="${2:-}"
if [ "$#" -ne 2 ] || [ -z "$remote" ] || ! git remote get-url "$remote" >/dev/null 2>&1; then
  echo 'private-tracker: pushes require a named Git remote, not a direct URL.' >&2
  exit 1
fi
# Fetch a dedicated snapshot of all destination heads/tags; never trust stale local tracking refs.
namespace="refs/private-tracker-destination/$remote"
git check-ref-format "$namespace/heads/check" >/dev/null
git fetch --quiet --no-tags --prune "$remote" "+refs/heads/*:$namespace/heads/*" "+refs/tags/*:$namespace/tags/*"
exclusions=()
while IFS= read -r oid; do
  if git rev-parse --verify "$oid^{commit}" >/dev/null 2>&1; then
    exclusions+=("$(git rev-parse "$oid^{commit}")")
  fi
done < <(git for-each-ref --format='%(objectname)' "$namespace/")

zero=0000000000000000000000000000000000000000
while read -r local_ref local_oid remote_ref remote_oid; do
  [ "$local_oid" = "$zero" ] && continue
  if ! tip=$(git rev-parse --verify "$local_oid^{commit}" 2>/dev/null); then
    echo 'private-tracker: outgoing refs must point to commits (possibly through annotated tags).' >&2
    exit 1
  fi
  range=("$tip")
  if [ "${#exclusions[@]}" -gt 0 ]; then
    range+=(--not "${exclusions[@]}")
  fi
  git rev-list "${range[@]}" > "$commits_file"
  while IFS= read -r commit; do
    git ls-tree -r -z --name-only "$commit" > "$paths_file"
    check_paths "outgoing commit $commit" < "$paths_file"
  done < "$commits_file"
done
