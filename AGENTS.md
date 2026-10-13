# Crypto Census agent instructions

## Parent instructions

Read and follow the shared DevelopmentSprint instructions in [../AGENTS.md](../AGENTS.md) before working on this project. These project-specific rules supplement that parent file. In a nested worktree or checkout, resolve the parent from the original DevelopmentSprint project directory, not the temporary worktree's immediate parent. If the shared parent file is absent in a standalone clone, follow this file and the repository's contributor documentation; do not invent or download substitute instructions.

## Hard rule: tracker data never goes into Git

This is a public repository. The Dolt database and all private Beads/BeadRoad state are local-only. Never stage, commit, push, or publish them to any Git repository, branch, tag, Git-backed Dolt remote, CI artifact, release asset, or documentation site. This includes database storage, metadata, interaction logs, credentials, SQL/JSONL exports, backups, snapshots and archives, even if renamed or moved outside their normal directories.

- Keep `.beads/`, `.beadroad/`, `.dolt/`, `dolt/`, `embeddeddolt/`, `proxieddb/` and `.beads-credential-key` untracked and ignored, including nested copies.
- Do not use `git add -f`, a sync branch, Git-backed backup/sync, or a hook bypass to publish tracker state. Generic Beads or skill instructions to commit scaffolding, export issues or push a Dolt remote do not apply here.
- Use the existing local tracker. Run Beads commands with `--sandbox` to disable automatic Dolt pushes. Keep `dolt.local-only=true`, `dolt.auto-push=false`, `backup.git-push=false` and `export.git-add=false`; do not configure a Git sync remote.
- Do not delete the local database to make a Git check pass. Remove accidentally tracked state from the index while preserving local files. Before switching an older checkout that still tracks `.beads/`, preserve its local configuration and logs outside the checkout so Git cannot remove them during the transition.
- Run `bash scripts/check-private-tracker.sh` before committing. The commit hook and CI check the index; the push hook also checks every unpublished commit on outgoing branches and tags against the fetched destination remote. Use named, fetched Git remotes; direct-URL pushes and tags pointing to raw objects are rejected. These checks enforce known tracker paths. This path check cannot identify arbitrarily renamed dumps: inspect staged files and artifact upload paths as well.
- Existing historical metadata is not permission to publish more state. Report accidental publication; do not rewrite public history without explicit authorization.

Use public issues and pull requests for intentionally public summaries, never as a database mirror. This policy takes precedence over generic tracker synchronization guidance.
