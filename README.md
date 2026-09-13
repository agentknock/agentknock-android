# Agentknock for Android

The Android application for [Agentknock](https://agentknock.dev/), built with
Kotlin and Jetpack Compose.

For setup and usage, see [agentknock.dev/docs](https://agentknock.dev/docs/) and
the [Agentknock CLI repository](https://github.com/agentknock/agentknock-cli).

> Early release · [Share feedback](mailto:agentknock@fulldisclosure.fi)

## Installation

Requires Android 8.0 or newer. Install from
[Google Play](https://play.google.com/store/apps/details?id=dev.agentknock), or
download an APK from [GitHub releases](https://github.com/agentknock/agentknock-android/releases).

| Variant | Features |
| --- | --- |
| Play | Firebase background push delivery and Google Play Billing. Available from Google Play or as an `agentknock-play-*.apk` download. |
| FOSS | No Firebase or Google Play Billing dependencies. Keep the app in the foreground to receive requests. Download `agentknock-foss-*.apk`. |

Existing AI access and activation links work in both variants.

To install a downloaded APK, open it on your Android device and allow the browser
or file manager to install apps when prompted. The `.aab` file is for store
distribution and cannot be installed directly.

Both variants share the same app identity and data, so they cannot be installed
side by side. Updating or switching variants requires a compatible signing
certificate and version code. See [release artifacts](docs/releases.md) for
signature verification and update details.

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

Use `Play` instead of `Foss` in task names to build the Google Play flavor.
See [Installation](#installation) for the differences between variants.

Nix is optional. On Linux x86-64, `nix develop` supplies the JDK, SDK, Python,
and Pillow; `nix develop .#emulator` also includes an emulator and system image.
Local builds and tests do not require publishing credentials.

## Continuous integration

CI checks formatting, builds both variants, runs JVM tests and Android lint,
and checks Room schema exports. Instrumentation and release startup tests run
on Android API 26 and 37. Release startup checks cover both APK variants and
device-specific APKs generated from the Play bundle. Signing checks use
throwaway keys to exercise signature verification without publishing credentials.

The required `CI passed` check covers every build and test. Failure logs include
Android errors and crashes for device checks. Preview sources are compiled in CI;
visual review uses the local preview gallery described below.

To run repository checks locally, install actionlint and shellcheck and run
`scripts/check-repository`, or use `nix develop .#ci`. The optional
`ci-emulator-26` and `ci-emulator-37` Nix shells supply the emulator images and
tools for `scripts/check-device`. Pass `foss instrumentation` or `foss release`,
substituting `play` for the Play variant. Build the corresponding artifacts first.

See [release artifacts](docs/releases.md) for build variants, signature
verification, and building unsigned release APKs.

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
- [Release artifacts](docs/releases.md): choosing builds and verifying downloads.

## Contribute

Contributions start with GitHub issues; pull requests can provide prototypes
or reproductions. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the contribution
policy and [SECURITY.md](SECURITY.md) for private vulnerability reporting.

## License

Agentknock for Android is licensed under the [MIT License](LICENSE-MIT) or the
[Apache License 2.0](LICENSE-APACHE), at your option (`MIT OR Apache-2.0`).

The bundled BIP-39 English word list retains its
[MIT license and upstream attribution](app/src/main/assets/licenses/bip39.txt).
This notice is included in both APK variants and the Play bundle.
