# Agentknock for Android

Agentknock is a phone-held credential broker for command-running agents. This
repository contains the Android app.

The app is currently a minimal Kotlin and Jetpack Compose placeholder. Product
behavior will be implemented incrementally against the Agentknock protocol.

## Development

The reproducible development shell provides JDK 17 and the Android SDK on
NixOS:

```console
nix develop
```

Build and check the app through the Gradle wrapper:

```console
./gradlew test lint assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Internal releases

Play publishing credentials and the upload keystore live outside the repository
under `~/.local/share/agentknock-android/`. To publish a completed release to the
internal testing track:

1. Increment `agentknockVersionCode` in `app/build.gradle.kts`.
2. Update `app/src/main/play/release-notes/en-US/internal.txt`.
3. Run `./publish-internal`.

The release name is derived from the application version and version code.
