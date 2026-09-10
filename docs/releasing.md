# Releases and Google Play publishing

Run the commands below from the repository root. Publishing requires maintainer
credentials; building and testing locally does not.

## Versioning and pull requests

`version.txt` contains the semantic version maintained by Release Please.
`agentknockVersionCode` in `app/build.gradle.kts` is the independent Android
counter. Every PR, including tooling and release PRs, must increase that counter
over current `master`. After fetching and rebasing, run:

```sh
python3 scripts/version.py bump
```

The helper is idempotent: it sets the code to at least `origin/master + 1` and
preserves a deliberately larger value. Commit the result. If another PR merges
first, rebase, resolve the counter conflict, and run the helper again. Required
checks reject reused codes and branches containing merge commits; strict branch
rules require the branch to stay current before merging.

Use signed Conventional Commits (`feat:`, `fix:`, `ci:`, `docs:`, etc.). Mark a
breaking change with `!` or a `BREAKING CHANGE:` footer. Merge PRs with true merge
commits, retaining the original signed commits. Do not squash or rebase-merge.
Release Please accumulates release-worthy changes into a PR updating `version.txt`,
`CHANGELOG.md`, and its manifest. Automation adds the Android counter bump through
GitHub's signed commit API. It explicitly dispatches CI on the updated branch,
using the built-in token without needing a GitHub App key. This dispatch runs
the same commit, ancestry, and version checks as ordinary PR CI.

The initial manifest starts at `0.2.0`; the bootstrap SHA excludes historical
commits from the first generated changelog. Tooling-only changes do not force a
new semantic release.

## GitHub releases: temporary signing

Production credentials are deliberately not configured. After CI passes on
`master`, a signing job generates a fresh random key to sign the FOSS APK and Play
bundle. It verifies both signatures and discards the key. These artifacts cannot
update the official app or an installation signed by another run. The bundle is
never uploaded to Play.
All public releases are marked as prereleases and explicitly say they are
**test-signed**. Configure real signing before distributing supported app updates.

The pipeline follows the CLI repository's draft-and-publish sequence:

1. On pushes to `master`, Release Please creates a draft release and tag for a
   merged release PR, and maintains the next release PR.
2. The same workflow builds and checks both distributions. Master builds embed
   the source commit. After all CI checks pass, CI signs those exact unsigned
   artifacts with a temporary key; it does not rebuild them for publication.
3. CI attests the signed files, checksum manifest subjects, and version metadata.
   Artifacts and the attestation bundle are retained in Actions for 30 days.
4. After all required checks succeed, publishing checks that the version tag
   points to this build's commit, verifies checksums and artifact attestations,
   attaches assets to the draft, and publishes it as an immutable prerelease.

Ordinary merges retain their test-signed Actions artifacts without creating a
semantic release. PRs skip final signing and attestations; their unsigned release
artifacts and debug APKs are retained for three days for downstream test jobs and
retries. Master CI runs are not canceled by later pushes. Release management and
publication each queue concurrent jobs instead of replacing
pending work. The current actionlint release needs a narrow exception for
GitHub's supported `concurrency.queue` property.

If publishing fails, rerun the failed jobs while the run's artifacts are retained.
Draft assets may be replaced on retry. Published releases are left intact;
immutable assets and tags cannot be replaced. A mismatched tag or changed version
without a release fails publication instead of publishing the wrong build.

Repository settings mirror the CLI: merge commits only, PR-title merge subjects,
automatic merged-branch deletion, automatic merge available after checks, SHA-pinned
Actions, read-only default workflow permissions, and immutable releases. The
`Protect master` ruleset requires signatures, PRs, resolved review threads, and
an up-to-date `CI passed` result. The `release` environment accepts protected
branches. GitHub's combined permission for Actions to create or approve PRs is
enabled for Release Please to create PRs; no workflow approves reviews.

## Local Play publishing

Automatic Play publishing and production CI key setup are deferred. The existing
local `publish-internal` helper remains available for maintainer use. Do not run
it as part of testing this workflow.

Local Play credentials and the upload keystore live outside the repository under
`~/.local/share/agentknock-android/`. The password is stored in Agentknock as
`agentknock-android-upload-passphrase`, in the `KEYSTORE_PASSWORD` environment
variable. `publish-internal` builds an unsigned bundle, requests the password for
a direct JDK `jarsigner` invocation, verifies it, and uploads to internal testing.
Gradle never receives the password. The temporary signed bundle is removed on
exit and its SHA-256 is printed before uploading.

The helper requires the configured JDK and Android SDK, Bash, Git, `sha256sum`,
and the Agentknock CLI. Build inputs must be committed. Before publishing, update
`app/src/main/play/release-notes/en-US/internal.txt` and ensure the version code
has not already been uploaded. Closed testing and production promotions remain
manual and should reuse an existing artifact.

Room schema 1 is the compatibility baseline for releases using the current
app-signing key. Every subsequently published schema is retained permanently.
Schema changes require a linear migration and migration test; published builds
must never use a destructive migration fallback.

## Subscription catalog

Google Play subscription definitions are stored in `app/src/main/play/subscriptions/`.
Edit `agentknock_subscription.json` to change the listing, base plan settings, or
the prices and availability in `basePlans[].regionalConfigs`. Its companion
`.metadata.json` records the Google Play regions version used by those prices.

Run `./publish-subscriptions` to upload the catalog through Gradle Play Publisher.
It uses the existing `play-publisher.json` credential and requires no signing key
or keystore password. It uploads the files on every invocation, even if Gradle
previously published the same local files. Use `./publish-subscriptions --dry-run`
to inspect the Gradle task graph without uploading.

Catalog changes apply to the app across all release tracks. Existing subscribers
retain their current prices unless separately migrated. Base plan activation and
offers are managed separately. The output-only `state` field is omitted from
the local catalog because uploading it cannot activate or deactivate a plan.

## Listing screenshots

The published phone screenshots are committed under
`app/src/main/play/listings/en-GB/graphics/phone-screenshots/`. See the
[preview guide](../app/src/screenshotTest/README.md#play-listing-screenshots)
for rendering and export instructions.
