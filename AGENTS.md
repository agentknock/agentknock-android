# Repository workflow

- Branch from `master`; use descriptive, flat branch names without `/`.
- Use signed Conventional Commits. Use `feat` for new functionality, `fix` for
  corrections, and `!` for breaking changes. Use types such as `ci`, `build`,
  `docs`, or `chore` when appropriate; do not call tooling changes app features.
- Rebase feature branches onto `origin/master`; never merge `master` into them.
  Push rewritten branches with `--force-with-lease`.
- Every PR must increase `agentknockVersionCode` over `master`, including release
  PRs. Run `python3 scripts/version.py bump` after fetching and rebasing.
- Open PRs and merge with true merge commits. Never commit directly to `master`,
  squash PRs, or rebase-merge PRs.
- Release by merging the Release Please PR. Let it maintain `version.txt` and
  `CHANGELOG.md`; do not create release tags manually.
- Signing verification tests use temporary keys. Release artifacts are signed
  with the production Google Cloud HSM keys; CI publishes successful `master`
  builds to Google Play internal testing. Local builds and tests need no
  publishing credentials. Do not add production credentials or call Google Play
  publishing APIs manually without an explicit request.
