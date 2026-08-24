# Agentknock for Android

Developer secrets on your phone, provided only to approved commands.

The app is built with Kotlin and Jetpack Compose.

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
3. If the Room schema changed, add the next linear migration and its migration
   test before publishing.
4. Run `./publish-internal`.

The release name is derived from the application version and version code.
Room schema 6 is the compatibility baseline. Published schemas from version 6
onward are retained permanently, and published builds must never use a
destructive migration fallback.
