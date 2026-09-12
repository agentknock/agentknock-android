# Repository workflow

- Branch from `master`; use descriptive, flat branch names without `/`.
- Use signed Conventional Commits. Use `feat` for new functionality, `fix` for
  corrections, and `!` for breaking changes. Use types such as `ci`, `build`,
  `docs`, or `chore` when appropriate; do not call tooling changes app features.
- Write PR titles and descriptions in plain language without Conventional
  Commit prefixes.
- Rebase feature branches onto `origin/master`; never merge `master` into them.
  Push rewritten branches with `--force-with-lease`.
- Increase `agentknockVersionCode` over `master` when a PR changes app sources,
  resources, dependencies, build inputs, or `version.txt` (including release PRs).
  Documentation, tests, Play metadata, and repository tooling may keep the code;
  never decrease it. `scripts/version.py` defines the path classification and
  treats unfamiliar paths as app inputs. Run `python3 scripts/version.py bump`
  after fetching and rebasing; it leaves the code unchanged when no bump is needed.
- Open PRs and merge with true merge commits. Never commit directly to `master`,
  squash PRs, or rebase-merge PRs.
- Release by merging the Release Please PR. Let it maintain `version.txt` and
  `CHANGELOG.md`; do not create release tags manually.

## Code Review Rules

- Do not attempt to check commit signatures during code review.

## Intermittent CI failures

- Before restarting a failed job, read and update `intermittent-failures.local.md`
  in the repository root. This log is local and ignored by Git; create it if absent.
- Record the UTC date, PR/commit, run and job links, failed attempt, exact test or
  error, evidence and suspected cause, and reason for restarting. Update the entry
  with the rerun attempt and result afterwards. Distinguish confirmed causes from
  hypotheses and infrastructure failures from app or test failures.
- Match failures across PRs and runs by the failing test/assertion or error, not
  just the job name. A passing rerun does not resolve a recurring failure or reset
  its history. If the same failure needs a second restart, fix it instead of using
  another unchanged rerun to make CI green. Record the fix and its validation.

## Test value

- Every test needs a concrete regression it would catch and a reason that
  regression matters: user behavior, data integrity, security, compatibility, or
  a real platform/protocol/tool contract. Explain non-obvious constraints and
  fixture origins in the test.
- Do not preserve implementation details, cosmetic choices, or test-owned copies
  of production behavior as assertions. Remove redundant cases when another test
  protects the same consequence; keep overlap only when it adds useful evidence.
