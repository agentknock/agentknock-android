# Agentknock for Android

The Android application for [Agentknock](https://agentknock.dev/), built with
Kotlin and Jetpack Compose.

For installation and usage, see [agentknock.dev/docs](https://agentknock.dev/docs/).

## Development

Use JDK 17 and the Android SDK with Platform 37, Build-Tools 36.0.0, and
Platform-Tools installed and SDK licenses accepted. Set `JAVA_HOME` to the JDK
and `ANDROID_HOME` to the SDK, or configure `sdk.dir` in `local.properties`.
Android Studio should use the same Gradle JDK.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. On Windows, use
`gradlew.bat`. Device tests require an emulator or connected device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Nix is optional. On Linux x86-64, `nix develop` supplies the JDK, SDK, Python,
and Pillow; `nix develop .#emulator` also includes an emulator and system image.
Local builds and tests do not require publishing credentials.

## Screen previews

The preview helper requires Bash and Python 3.10 or newer:

```sh
./preview-ui secret-detail --variant dark
```

Open `app/build/reports/ui-previews/index.html` for the gallery. The
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
