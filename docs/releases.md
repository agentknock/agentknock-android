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

Google Play internal testing receives successful builds from each merge to
`master`. These builds can share a version name while their Android version codes
increase. Publishing a versioned GitHub release promotes that release's exact
bundle to the Alpha closed-testing track after its internal upload succeeds.
Promotion reuses the uploaded bundle and Android version code. Google Play may
review the release before making it available to closed testers.

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
