# Release artifacts

## Choosing a build

| Artifact | Use |
| --- | --- |
| `agentknock-foss-VERSION-CODE.apk` | Install the variant without Firebase or Google Play Billing. |
| `agentknock-play-VERSION-CODE.apk` | Install the variant with Firebase messaging and Google Play Billing. |
| `agentknock-play-VERSION-CODE.aab` | Android App Bundle for store distribution; not directly installable. |

The FOSS variant currently receives requests while the app is in the foreground.
Existing AI access and activation links work without in-app purchases. The Play
variant adds background push delivery through Google services.

Google Play internal testing receives successful builds when a merge to `master`
increases the Android version code. These builds can share a version name while
their Android version codes increase. Publishing a versioned GitHub release
promotes that release's exact bundle to the Alpha closed-testing track after its
internal upload succeeds.
Promotion reuses the uploaded bundle and Android version code. Google Play may
review the release before making it available to closed testers.

PRs that change app sources, resources, dependencies, build configuration, or
`version.txt` must increase the code. Documentation, test fixtures, Play listing
metadata, and repository tooling can keep the existing code.
The exclusions are defined in `scripts/version.py`; unfamiliar paths require a
bump. Build setup and the distribution build workflow count as app inputs.
This is a conservative check of changed input paths, not a comparison of compiled
bytes: even a comment-only change to production source requires a bump.

After fetching and rebasing onto `origin/master`, run
`python3 scripts/version.py bump`. It considers committed, staged, unstaged, and
untracked files (excluding ignored files), and does nothing when a bump is not
needed. A deliberate code increase is also allowed to request a fresh build.
Release Please still bumps automatically because changing the version name
changes the app.

Every PR and master merge still runs the full CI checks. Merges that keep the
code skip official signing, artifact publication, and Play promotion. This avoids
re-uploading a rebuilt bundle with an already used code: the embedded source
revision changes on every merge, even when the app inputs are unchanged.
The next app release includes the intervening repository changes in its source
history. To retry a failed publication, rerun the original merge's workflow.

## Play Store listing

Play metadata lives in `app/src/main/play`. The root files `contact-email.txt`,
`contact-website.txt`, and `default-language.txt` define the public support
contact details and default listing language. GPP also supports an optional
`contact-phone.txt`; it is absent because no support phone number is configured.

The localized listing text, icon, feature graphic, and ordered screenshots
live under `listings/<language>`. To add a promotional video, put its YouTube URL
in `listings/<language>/video-url.txt` (currently `en-GB`). Leave that file absent
until a video is available. These are native GPP metadata files and are uploaded
by the existing listing task.

Review these files in the PR; use
`scripts/update_play_screenshots.py` to prepare screenshots before committing.
Publication uploads the committed assets without generating new screenshots.

After each successful CI run for a push to `master`, the listing job compares
that push's before and after `app/src/main/play` trees, including the root contact
and language files. It publishes only when they differ,
independently of the Android version code and app-artifact publication. Listing
publication shares the `google-play-publishing` lock with uploads and promotions.
Older runs skip listings superseded on master, including when rerun.

GitHub authenticates as the release environment's
`GCP_PLAY_PUBLISHING_SERVICE_ACCOUNT` through workload identity federation. The
account needs **Manage store presence** for `dev.agentknock` in Play Console.
The generated Application Default Credentials file is temporary and is removed
by the authentication action at the end of the job; no local service-account
key is needed for listing publication.

Gradle Play Publisher 4.1.1 uploads with `--no-commit` and validates the edit.
`scripts/publish-play-listing.py` then commits that edit using
`changesInReviewBehavior=ERROR_IF_IN_REVIEW`. GPP's own commit does not set this
protection, so the wrapper uses the API directly for this final step. If a Play
review is active, the job fails instead of cancelling it. Rerun the failed job
after that review completes; it checks the original push and current master
again. When upgrading GPP, verify its preserved edit file and marker contract.

API commits may submit changes for review. [Managed publishing](https://support.google.com/googleplay/android-developer/answer/9859654?hl=en-GB)
holds approved listing changes until they are published from Play Console; it
does not itself prevent submission for review. The workflow does not change that
Console setting.

Production subscription products, base plans, prices, and offers belong outside
this app repository. Their configuration and the local subscription publishing
helper have been removed from version control. This does not change the products
configured in Google Play. The app's billing integration and its test fixtures
remain here. Local `publish-internal` still uses its existing Play credential;
moving subscription configuration does not retire that separate upload command.

Semantic-version releases use the matching Release Please changelog entries as
English Play release notes for both internal testing and Alpha. Notes are
converted to plain text; longer notes keep complete bullets and link to the full
GitHub release within Play's 500-character limit. Ordinary internal builds omit
release notes.

Both variants use the package name `dev.agentknock` and share app data. They
cannot be installed side by side. Updating or switching variants requires a
compatible signing certificate and version code; an older release generally
cannot replace a newer installation.

Releases explicitly labeled **test-signed** use temporary certificates and cannot
update the official app. Debug builds and locally signed builds also have their
own signing identities. Check the release notes before installing a prerelease.

## Verifying downloads

Both officially signed APKs use the public certificate in
[app-signing-certificate.pem](../signing/app-signing-certificate.pem). Its SHA-256
fingerprint is:

```text
EB:B2:3B:E7:98:B9:5D:89:06:03:88:32:FC:14:1A:A2:AA:B3:41:31:56:AD:3F:6A:CA:F0:DA:7A:67:AD:FE:5C
```

Use `apksigner` from the Android SDK Build-Tools to verify an APK's signature and
display its certificate. Replace the filename below with the downloaded APK:

```sh
apksigner verify --verbose --print-certs agentknock-foss-VERSION-CODE.apk
```

Compare the reported certificate SHA-256 digest with the fingerprint above.
The digest may be printed in lowercase without colons.

The Play AAB uses a separate
[upload certificate](../signing/play-upload-certificate.pem). Google Play verifies
that upload signature, then signs the APKs it delivers with the app-signing key.

Releases include `SHA256SUMS` for file integrity and `version.json` with the app
version, Android version code, source commit, and both certificate fingerprints.
After downloading the files listed in the checksum manifest into one directory,
check them with:

```sh
sha256sum --check SHA256SUMS
```

GitHub artifact attestations associate the files with their source commit and
build workflow. Verify that an attestation comes from this repository's release
signing workflow with the GitHub CLI:

```sh
gh attestation verify agentknock-foss-VERSION-CODE.apk \
  --repo agentknock/agentknock-android \
  --signer-workflow agentknock/agentknock-android/.github/workflows/sign-release.yml
```

## Building release artifacts

With the [development prerequisites](../README.md#development) installed, run:

```sh
./gradlew :app:assembleFossRelease :app:assemblePlayRelease :app:bundlePlayRelease
```

Unsigned APKs appear under `app/build/outputs/apk/{foss,play}/release/`; the Play
bundle appears under `app/build/outputs/bundle/playRelease/`. Building requires
no publishing credentials. Sign your own APKs before installation, or use the
debug build tasks in the README for development. A local build does not carry
the official signing identity.
