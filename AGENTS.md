# Repository workflow

- Branch from `master`; use descriptive, flat branch names without `/`.
- Use signed Conventional Commits. Use `feat` for new functionality, `fix` for
  corrections, and `!` for breaking changes. Use types such as `ci`, `build`,
  `docs`, or `chore` when appropriate; do not call tooling changes app features.
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
