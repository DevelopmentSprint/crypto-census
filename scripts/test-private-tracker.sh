#!/usr/bin/env bash
# Synthetic fixtures only: never copy or inspect a real tracker database.
# Every Git object and destination is local to a disposable temporary directory.
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
guard="$script_dir/check-private-tracker.sh"
suite_dir=$(mktemp -d "${TMPDIR:-/tmp}/census-private-regression.XXXXXX")
trap 'rm -rf "$suite_dir"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Do not inherit a calling hook's index, worktree, object store or user hooks/config.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_COMMON_DIR
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT
unset GIT_REPLACE_REF_BASE GIT_NO_REPLACE_OBJECTS GIT_CONFIG GIT_TEMPLATE_DIR
unset GIT_AUTHOR_DATE GIT_COMMITTER_DATE
export GIT_AUTHOR_NAME='Synthetic guard fixture' GIT_COMMITTER_NAME='Synthetic guard fixture'
export GIT_AUTHOR_EMAIL=fixture@example.invalid GIT_COMMITTER_EMAIL=fixture@example.invalid
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_TERMINAL_PROMPT=0 GIT_ALLOW_PROTOCOL=file GIT_DEFAULT_HASH=sha1 LC_ALL=C

passed=0
repositories=0
zero=0000000000000000000000000000000000000000
case_log="$suite_dir/result.log"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  cat "$case_log" >&2
  exit 1
}

accepts() {
  local label="$1"
  shift
  if ! "$@" > "$case_log" 2>&1; then
    fail "$label (expected success)"
  fi
  passed=$((passed + 1))
}

rejects() {
  local label="$1" diagnostic="$2"
  shift 2
  if "$@" > "$case_log" 2>&1; then
    fail "$label (expected rejection)"
  fi
  if ! grep -Fq "$diagnostic" "$case_log"; then
    fail "$label (wrong rejection reason)"
  fi
  passed=$((passed + 1))
}

fresh() {
  repositories=$((repositories + 1))
  repo="$suite_dir/repo-$repositories"
  destination="$suite_dir/destination-$repositories.git"
  git -c init.templateDir= init -q -b main "$repo"
  git -C "$repo" config user.name 'Synthetic guard fixture'
  git -C "$repo" config user.email 'fixture@example.invalid'
  git -C "$repo" config core.hooksPath "$suite_dir/empty-hooks"
  printf 'Public synthetic fixture\n' > "$repo/README.md"
  git -C "$repo" add README.md
  git -C "$repo" commit -qm 'Synthetic baseline'
  baseline=$(git -C "$repo" rev-parse HEAD)
}

stage() {
  mkdir -p "$repo/$(dirname "$1")"
  printf 'Synthetic marker, not tracker state\n' > "$repo/$1"
  git -C "$repo" add -- "$1"
}

commit() {
  git -C "$repo" commit -qm 'Synthetic fixture change'
  tip=$(git -C "$repo" rev-parse HEAD)
}

remote() {
  git -c init.templateDir= init --bare -q "$destination"
  # Seed a local destination by fetching synthetic objects; never run git push.
  git -C "$destination" fetch -q "$repo" "$baseline:refs/heads/main"
  git -C "$repo" remote add origin "$destination"
}

index_check() {
  (cd "$repo" && bash "$guard")
}

push_check() {
  local remote_name="$1" object="$2" ref="${3:-refs/heads/main}"
  printf '%s %s %s %s\n' "$ref" "$object" "$ref" "$zero" |
    (cd "$repo" && bash "$guard" --pre-push "$remote_name")
}

multiple_push_check() {
  {
    printf 'refs/heads/clean %s refs/heads/clean %s\n' "$baseline" "$zero"
    printf 'refs/heads/unsafe %s refs/heads/unsafe %s\n' "$tip" "$zero"
  } | (cd "$repo" && bash "$guard" --pre-push origin)
}

fresh
stage 'docs/.beads-guide.md'
stage 'dolt-not-private/example.txt'
stage 'docs/beads.txt'
accepts 'ordinary and similarly named indexed files' index_check
mkdir -p "$repo/.beads"
printf 'Untracked synthetic marker\n' > "$repo/.beads/untracked"
accepts 'untracked fixture is outside the index check' index_check

for component in .beads .beadroad .dolt dolt embeddeddolt proxieddb .beads-credential-key .beads.gate.lock; do
  fresh
  stage "nested/$component/marker"
  rejects "nested indexed $component" 'index contains forbidden path:' index_check
  fresh
  stage "$component"
  rejects "indexed exact component $component" 'index contains forbidden path:' index_check
done

fresh
stage $'space and\nnewline/.beads/marker'
rejects 'NUL-delimited paths preserve whitespace and newlines' 'index contains forbidden path:' index_check
rm "$repo/space and"$'\n'"newline/.beads/marker"
rejects 'index is checked even when the working file was deleted' 'index contains forbidden path:' index_check

fresh
remote
stage public.txt
commit
accepts 'clean unpublished branch with a named local destination' push_check origin "$tip"
accepts 'outgoing branch deletion' push_check origin "$zero"
rejects 'unknown remote name' 'require a named Git remote' push_check missing "$tip"
rejects 'direct filesystem destination' 'require a named Git remote' push_check "$destination" "$tip"
rejects 'direct file URL' 'require a named Git remote' push_check "file://$destination" "$tip"
git -C "$repo" tag -a safe -m 'Synthetic annotated commit tag' "$tip"
accepts 'annotated tag pointing to a clean commit' push_check origin "$(git -C "$repo" rev-parse safe)" refs/tags/safe
blob=$(printf 'Synthetic raw object\n' | git -C "$repo" hash-object -w --stdin)
rejects 'raw blob tag' 'outgoing refs must point to commits' push_check origin "$blob" refs/tags/blob
rejects 'raw tree tag' 'outgoing refs must point to commits' push_check origin "$(git -C "$repo" rev-parse 'HEAD^{tree}')" refs/tags/tree
git -C "$repo" tag -a blob-wrapper -m 'Synthetic annotated blob tag' "$blob"
rejects 'annotated tag wrapping a raw blob' 'outgoing refs must point to commits' push_check origin "$(git -C "$repo" rev-parse blob-wrapper)" refs/tags/blob-wrapper

fresh
remote
stage nested/.beadroad/marker
commit
bad_commit="$tip"
rejects 'new outgoing commit containing a nested private path' 'outgoing commit' push_check origin "$tip"
rejects 'all updates are checked in a multi-ref push' 'outgoing commit' multiple_push_check
git -C "$repo" mv nested/.beadroad/marker renamed-public-looking.txt
commit
accepts 'renamed fixture has a clean current index' index_check
rejects 'renaming a known private path does not hide unpublished history' 'outgoing commit' push_check origin "$tip"
git -C "$repo" tag -a unsafe -m 'Synthetic unsafe commit tag' "$bad_commit"
rejects 'annotated tag to a private commit' 'outgoing commit' push_check origin "$(git -C "$repo" rev-parse unsafe)" refs/tags/unsafe

# Stale ordinary tracking refs and stale dedicated snapshots cannot grant exclusions.
git -C "$repo" update-ref refs/remotes/origin/main "$tip"
git -C "$repo" update-ref refs/private-tracker-destination/origin/heads/stale "$tip"
rejects 'destination is fetched and stale exclusions are pruned' 'outgoing commit' push_check origin "$tip"
if git -C "$repo" show-ref --verify --quiet refs/private-tracker-destination/origin/heads/stale; then
  fail 'stale destination snapshot survived pruning'
fi
# A failed fetch must not fall back to previously populated destination exclusions.
git -C "$repo" update-ref refs/private-tracker-destination/origin/heads/main "$tip"
git -C "$repo" remote set-url origin "$suite_dir/nonexistent-destination"
rejects 'failed destination fetch fails closed' 'does not appear to be a git repository' push_check origin "$tip"

fresh
remote
stage .beads/marker
commit
bad_commit="$tip"
# Replace the bad commit's tree with a synthetic clean commit. Ordinary Git sees it as clean.
replacement=$(printf 'Synthetic clean replacement\n' | git -C "$repo" commit-tree "${baseline}^{tree}" -p "$baseline")
git -C "$repo" replace "$bad_commit" "$replacement"
if [ "$(git -C "$repo" ls-tree -r --name-only HEAD)" != README.md ]; then
  fail 'replacement fixture did not hide the original tree'
fi
rejects 'replacement refs cannot hide private outgoing trees' 'outgoing commit' push_check origin "$bad_commit"

fresh
remote
stage .dolt/marker
commit
git -C "$repo" rm -q .dolt/marker
commit
# Graft the clean tip directly onto the public baseline, hiding its bad parent locally.
git -C "$repo" replace --graft "$tip" "$baseline"
if [ "$(git -C "$repo" rev-list --count HEAD --not "$baseline")" != 1 ]; then
  fail 'replacement fixture did not hide the original ancestry'
fi
rejects 'replacement refs cannot hide private outgoing ancestry' 'outgoing commit' push_check origin "$tip"

fresh
remote
stage .beads/marker
commit
# Synthetic historical state already at the destination is excluded, but a newly
# committed tree containing the same path must still be rejected.
git -C "$destination" fetch -q "$repo" "$tip:refs/heads/main"
git -C "$repo" commit -q --allow-empty -m 'Synthetic new commit retaining forbidden tree'
tip=$(git -C "$repo" rev-parse HEAD)
rejects 'destination history does not permit a new forbidden tree' 'outgoing commit' push_check origin "$tip"
# Remove both new commits by selecting a new branch at the destination's synthetic head.
git -C "$repo" checkout -qb clean-removal HEAD^
git -C "$repo" rm -q .beads/marker
commit
accepts 'fetched destination history is excluded from clean unpublished removal' push_check origin "$tip"

fresh
remote
stage .beads/marker
commit
git -C "$repo" tag -a historical -m 'Synthetic destination-only history' "$tip"
git -C "$destination" fetch -q "$repo" refs/tags/historical:refs/tags/historical
git -C "$repo" rm -q .beads/marker
commit
accepts 'fresh destination annotated tags also establish existing history' push_check origin "$tip"

printf 'private-tracker regression: %s checks passed (%s disposable repositories)\n' "$passed" "$repositories"
