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
