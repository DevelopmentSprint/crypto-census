#!/usr/bin/env bash
# Check the index and, from pre-push, every newly published commit on every outgoing ref.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
paths=$(mktemp "${TMPDIR:-/tmp}/census-tracker-paths.XXXXXX")
commits=$(mktemp "${TMPDIR:-/tmp}/census-tracker-commits.XXXXXX")
trap 'rm -f "$paths" "$commits"' EXIT

check_paths() {
  while IFS= read -r -d '' path; do
    case "/$path/" in
      */.beads/* | */.beadroad/* | */.dolt/* | */dolt/* | */embeddeddolt/* | */proxieddb/* | */.beads-credential-key/)
        echo "Private tracker state is staged, tracked, or present in outgoing history. Keep it local-only; see AGENTS.md." >&2
        return 1
        ;;
    esac
  done < "$paths"
}

git ls-files --cached -z > "$paths"
check_paths
if [ "$#" -eq 0 ]; then
  exit 0
fi
if [ "$#" -ne 2 ] || [ "$1" != "--pre-push" ]; then
  echo "Usage: check-private-tracker.sh [--pre-push remote-name]" >&2
  exit 2
fi
remote=$2
# A fetched named remote establishes which legacy history was already published.
# Direct URL pushes fail closed rather than guessing that another remote is equivalent.
if ! git remote | grep -Fxq -- "$remote"; then
  echo "Tracker push check requires a named remote. Configure and fetch it before pushing." >&2
  exit 1
fi
while read -r local_ref local_sha remote_ref remote_sha extra; do
  if [ -n "${extra:-}" ] || ! [[ "$local_sha" =~ ^[0-9a-f]{40,64}$ ]] || ! [[ "$remote_sha" =~ ^[0-9a-f]{40,64}$ ]]; then
    echo "Malformed pre-push ref input; refusing publication." >&2
    exit 1
  fi
  if [[ "$local_sha" =~ ^0+$ ]]; then
    continue # Deleting a ref publishes no new objects.
  fi
  if ! git cat-file -e "${local_sha}^{commit}" 2>/dev/null; then
    echo "Only refs pointing to commits may be published; cannot validate a raw object tag." >&2
    exit 1
  fi
  if [[ "$remote_sha" =~ ^0+$ ]]; then
    git rev-list "$local_sha" --not --remotes="$remote" > "$commits"
  else
    if ! git cat-file -e "${remote_sha}^{commit}" 2>/dev/null; then
      echo "Fetch the destination remote before pushing so its existing history can be checked." >&2
      exit 1
    fi
    git rev-list "$local_sha" --not "$remote_sha" --remotes="$remote" > "$commits"
  fi
  while IFS= read -r commit; do
    git ls-tree -r --name-only -z "$commit" > "$paths"
    check_paths
  done < "$commits"
done
