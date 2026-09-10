# Agentknock for Android

The Android application for [Agentknock](https://agentknock.dev/), built with
Kotlin and Jetpack Compose.

For installation and usage, see [agentknock.dev/docs](https://agentknock.dev/docs/).

## Development

Use JDK 17 and the Android SDK with Platform 37, Build-Tools 36.0.0, and
Platform-Tools installed and SDK licenses accepted. Set `JAVA_HOME` to the JDK
and `ANDROID_HOME` to the SDK, or configure `sdk.dir` in `local.properties`.
Android Studio should use the same Gradle JDK.

Before committing, run:

```sh
./gradlew check
```

This verifies Kotlin formatting, builds both debug APKs, runs unit tests and
Android Lint for both flavors, compiles preview sources, and rejects Firebase,
Google Play Services, or Billing dependencies in either FOSS runtime. It does
not render screenshots or run release optimization or device tests. Gradle
reuses unchanged outputs, so there is no need to run `clean` first.

Kotlin sources and Gradle Kotlin scripts use ktfmt's four-space Kotlin style
through Spotless. Both tools are version-pinned. Apply formatting before running
checks:

```sh
./gradlew spotlessApply
./gradlew check
```

`check` reports formatting differences without changing source files. Formatting
does not require an IDE plugin or a separately installed formatter.

The APKs are written to `app/build/outputs/apk/{foss,play}/debug/`. To build just
FOSS, use `./gradlew :app:assembleFossDebug`. On Windows, use `gradlew.bat`.
Device tests require an emulator or connected device:

```sh
./gradlew :app:connectedFossDebugAndroidTest
```

The `foss` flavor has no Firebase or Google Play Billing dependencies. Keep the
app in the foreground to receive requests; background push delivery is not yet
supported. Existing AI access and activation links work without in-app purchases.
Use `Play` instead of `Foss` in task names for the Google Play flavor, which adds
FCM and Play Billing. Both flavors share the application ID and local storage;
installing one over the other requires matching signing keys.

Nix is optional. On Linux x86-64, `nix develop` supplies the JDK, SDK, Python,
and Pillow; `nix develop .#emulator` also includes an emulator and system image.
Local builds and tests do not require publishing credentials.

## Continuous integration

Pull requests and pushes to `master` run repository checks plus independent Play
and FOSS jobs for debug builds and JVM tests, Android lint, and minified releases.
The debug jobs also compile preview sources and check Room schema exports.
Instrumentation runs on API 26 and 37 as soon as each distribution's debug job
finishes. Separate API 26 and 37 jobs install the minified FOSS APK or
device-specific APKs from the Play bundle and assert that the welcome screen
appears. Release startup checks use disposable signing keys. A signing job then signs the
validated release artifacts with a new temporary key and verifies both signatures.
PR runs do not publish releases.

Full lint covers app and test sources in each debug flavor. There are no separate
release source sets; release builds additionally run release-critical lint.
Preview galleries remain a local design tool: CI has no approved images to
compare against, so it compiles previews and relies on instrumentation for UI
behavior instead of rendering unchecked pictures.

The required `CI passed` check covers every build, test, and signing check. PRs
also require Conventional Commits, a branch rebased onto current `master`, and a
version code greater than `master`. Debug APKs are retained for three days;
unsigned and test-signed release artifacts are retained for 30 days. Failures appear
in job logs, including Android errors and crashes for failed device checks.
GitHub runners use their Android SDK directly. Gradle caches are scoped by job
and distribution, and pull requests can reuse their own caches across updates.
JVM tests and debug APKs share one build to avoid compiling the app twice.
PR release builds use the build's `unverified` source revision to reuse compilation
and optimization outputs. Builds on `master` embed their full Git commit and
attest the signed artifacts. Release Please maintains the semantic version and
changelog PR; merging it publishes a GitHub prerelease after CI passes. These
artifacts currently use temporary test keys. No workflow calls Google Play.
See the [release workflow](docs/releasing.md) before changing signing or publishing.

For the repository checks locally, install actionlint and shellcheck and run
`scripts/check-repository`, or run it in `nix develop .#ci`. The optional `ci-emulator-26` and `ci-emulator-37`
Nix shells also supply the image and tools for `scripts/check-device`. Pass
`foss instrumentation` or `foss release` as arguments, substituting `play` for
the Play distribution. Build the corresponding artifacts first.

## Screen previews

The preview helper requires Bash and Python 3.10 or newer:

```sh
./preview-ui secret-detail --variant dark
```

Previews default to `play`. Add `--distribution foss` for FOSS. Open
`app/build/reports/ui-previews/index.html` for Play or its `foss/index.html`
subdirectory for FOSS. The
[preview guide](app/src/screenshotTest/README.md) covers available screens,
comparisons, adding previews, and exporting Play Store screenshots.

## Repository documentation

- [Device-relay protocol](docs/device-relay-protocol.md): connections, delivery,
  recovery, and device HTTP endpoints.
- [Releasing](docs/releasing.md): signing, internal releases, and Play metadata.

## Contribute

Contributions start with GitHub issues; pull requests can provide prototypes
or reproductions. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution
policy and [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## License

Agentknock for Android is licensed under the [MIT License](LICENSE-MIT) or the
[Apache License 2.0](LICENSE-APACHE), at your option (`MIT OR Apache-2.0`).
