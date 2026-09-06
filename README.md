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

## Screen previews

Render the production Compose screens locally without an emulator:

```sh
./preview-ui secret-detail --variant dark  # one screen, one variant
./preview-ui --variant light               # normal phone/light across screens
./preview-ui secret-detail                 # all variants of one screen
./preview-ui                               # complete catalog
./preview-ui --list                        # available screens and their variants
```

Open `app/build/reports/ui-previews/index.html` for the searchable gallery.
The gallery starts on the screen and variant from the last command. Its filters
can show other cached previews; each image displays its last render time and an
exact rerender command. Rendering one variant preserves the other variants of
that screen as well as all unrelated screens. Missing images are labelled explicitly.
The command fails if rendering fails or any requested preview is absent from the results.

Variant names describe the configurations defined in the catalog: `light` and
`dark` are 360 × 800 dp phones at normal text size; `large-text` and `tablet` are
the additional configurations listed for their respective screens. No variant is
preferred by default. Omitting `--variant` renders all variants of the selected screens.
Filtering happens before rendering, so unselected variants do not generate images.

Previews live in `app/src/screenshotTest/kotlin/dev/agentknock/preview/` and call the
production composables with synthetic data. The catalog covers setup, navigation,
requests, clients, secrets, editors, settings, billing, audit history, and important
empty, error, pending, and confirmation states, including views below the fold.
See [the preview guide](app/src/screenshotTest/README.md) for coverage and extension instructions.

To serve just the generated gallery over the network:

```sh
python3 -m http.server 4242 --bind 0.0.0.0 --directory app/build/reports/ui-previews
```

The script uses the project's Nix development shell, fixed palettes, and UTC timestamps.
The running app still uses dynamic colors by default. PNGs and the gallery are generated
and ignored by Git. Android system prompts, keyboard behavior, interactions, and dynamic
colors still require emulator or device verification.

## Internal releases

Play publishing credentials and the upload keystore live outside the repository
under `~/.local/share/agentknock-android/`. The keystore password is stored in
Agentknock as `agentknock-android-upload-passphrase`, in the `KEYSTORE_PASSWORD`
environment variable. `publish-internal` builds an unsigned bundle, requests the
password through the paired CLI for a direct JDK `jarsigner` invocation, verifies
the signed bundle, and uploads it. Gradle never receives the password. The signed
bundle is kept in a temporary directory that is removed when the command exits;
its SHA-256 is printed before uploading.
To publish a completed release to the internal testing track:

1. Increment `agentknockVersionCode` in `app/build.gradle.kts`.
2. Update `app/src/main/play/release-notes/en-US/internal.txt`.
3. If the Room schema changed, add the next linear migration and its migration
   test before publishing.
4. Commit the release changes.
5. Run `./publish-internal`.

Release builds made directly with Gradle are unsigned.

The release name is derived from the application version and version code.
Room schema 1 is the compatibility baseline for releases using the current
app-signing key. Every subsequently published schema is retained permanently,
and published builds must never use a destructive migration fallback.

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
