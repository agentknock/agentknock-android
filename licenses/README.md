# Dependency license inputs

The app's license catalogue combines resolved runtime dependency metadata,
original notices from dependency archives, and the supplemental notices here.
These files cover bundled code and data that Maven metadata does not describe
fully. They are build inputs, not a second dependency inventory.

AboutLibraries collects dependency metadata during each Android variant's build.
`scripts/dependency_licenses.py` filters that catalogue against the resolved
runtime archives, imports their original notices (including Google's embedded
notice bundles), and adds the supplements. It runs without fetching license
texts from the network. The resulting `aboutlibraries.json` is generated under
`app/build/`, packaged as a raw resource, and read locally by the app.

Every build fails for missing runtime metadata, missing license text, malformed
embedded notice bundles, or a stale supplement. CI's existing release check
also compares the generated catalogue with the bytes inside the minified APK
and, for Play, the AAB. To check an update without compiling the app, run:

```sh
./gradlew :app:prepareLibraryDefinitionsFossRelease :app:prepareLibraryDefinitionsPlayRelease
```

These checks cannot discover undisclosed embedded components. The agent making
a dependency update must inspect upstream license changes in that same PR;
there is no separate recurring human review step.

`standard-texts.json` maps exact AboutLibraries license identifiers to local
copies of their terms. Apache 2.0 reuses the repository's `LICENSE-APACHE`.
`ASDKL` uses `google-sdk.txt`, the Android SDK agreement from Google's official
[terms page](https://developer.android.com/studio/terms), dated April 28, 2026.
The text was extracted from that page's `sdk-terms` element; HTML formatting was
removed, with the wording and agreement date retained. This mapping describes
dependencies whose metadata names that agreement, not all Google libraries.

## Supplemental entries

`supplements.json` is an array with these fields:

- `id`: stable identifier for the additional component.
- `name`: its displayed name.
- `artifacts`: exact, unversioned `group:artifact` coordinates that contain it.
  An empty array includes the entry in every variant.
- `auditedVersions`: each matching coordinate's audited version. A dependency
  update must verify its bundled code and data before changing this value.
- `license`: displayed license name.
- `licenseFile`: repository-relative path containing the full original terms.
- `noticeFile`: optional path containing accompanying original notices.
- `licenseIds`: optional exact AboutLibraries identifiers whose missing base
  license text this entry supplies, only for its matching artifacts.
- `url`: component website.
- `sourceUrl`: source location corresponding to the bundled component.
- `version`: displayed version of the bundled component.

The build rejects a targeted dependency version that differs from
`auditedVersions`. The agent updating that dependency should inspect the new
artifact and corresponding upstream source, update any changed notices and
source links, then record the new audited version. Do not change the version
field merely to make the check pass. A removed dependency no longer needs its
supplements; confirm both release variants before removing their inputs.

## Provenance

Audited against the FOSS and Play release runtime dependency graphs on
September 14, 2026.

### BIP-39 English word list

- Source revision: `bitcoin/bips` commit
  `ce1862ac6bcffa1dd20aad858380e51e66e949ea`.
- The existing `app/src/main/assets/licenses/bip39.txt` already carries the
  authors, MIT permission notice and pinned word-list link. Reuse that file.
- When changing `app/src/main/res/raw/pairing_address_words.txt`, check the
  [specification](https://github.com/bitcoin/bips/blob/ce1862ac6bcffa1dd20aad858380e51e66e949ea/bip-0039.mediawiki)
  and [word list](https://github.com/bitcoin/bips/blob/ce1862ac6bcffa1dd20aad858380e51e66e949ea/bip-0039/english.txt).

### ThreeTen code in Kotlin

- Artifact: `org.jetbrains.kotlin:kotlin-stdlib:2.4.10`.
- `kotlin-threeten.txt` is an unchanged copy of Kotlin's
  [ThreeTen license](https://raw.githubusercontent.com/JetBrains/kotlin/refs/tags/v2.4.10/license/third_party/threetenbp_license.txt),
  including Stephen Colebourne and Michael Nascimento Santos's copyright.
- Kotlin's [license inventory](https://github.com/JetBrains/kotlin/blob/v2.4.10/license/README.md)
  identifies this code in `libraries/stdlib/src/kotlin/time`. Inspect that
  inventory and the stdlib source archive on Kotlin updates.
- Kotlin's top-level `license/NOTICE.txt` explicitly applies to the compiler
  distribution; it is not used as a notice for the runtime stdlib.

### Protocol Buffers in AndroidX DataStore

- Artifact: `androidx.datastore:datastore-preferences-external-protobuf:1.1.7`
  (Play variant), containing Protocol Buffers Java Lite 4.28.2. The resolved
  JAR's relocated `RuntimeVersion` constants identify `4.28.2`.
- `datastore-protobuf.txt` is an unchanged copy of the
  [Protocol Buffers v28.2 license](https://raw.githubusercontent.com/protocolbuffers/protobuf/v28.2/LICENSE),
  including Google's copyright and its generated-code clarification.
- AndroidX's [repackaging configuration](https://github.com/androidx/androidx/blob/2d71e6c2c489947a51d0835622a9f997f7412eeb/datastore/datastore-preferences-external-protobuf/build.gradle)
  relocates the protobuf classes; its source JAR does not supply the original
  source or notice. On updates, identify the actual repackaged protobuf version
  from the artifact and matching AndroidX build configuration.
- `licenseIds` supplies this original notice for this artifact's BSD-3-Clause
  metadata. Do not use Google's copyright as a generic BSD license notice for
  other dependencies.

### Unicode IDNA data in OkHttp

- Artifact: `com.squareup.okhttp3:okhttp-android:5.4.0`.
- Its [IDNA mapping input](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp-idna-mapping-table/src/main/resources/okhttp3/internal/idna/IdnaMappingTable.txt)
  identifies Unicode 15.1.0 and the original 2023 Unicode copyright.
  `okhttp-unicode-notice.txt` preserves the input's opening comment block.
- `unicode.txt` is an unchanged copy of the
  [Unicode License v3](https://www.unicode.org/license.txt), retrieved on the
  audit date. The input links to Unicode's
  [terms of use](https://www.unicode.org/terms_of_use.html), which apply that
  license to Unicode data files and software. The original input notice is
  retained separately from the current license's 1991–2026 copyright.
- On OkHttp updates inspect both the input and
  [IDNA generator](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp-idna-mapping-table/src/main/kotlin/okhttp3/internal/idn/GenerateIdnaMappingTableCode.kt).

### Public Suffix List in OkHttp

- Artifact: `com.squareup.okhttp3:okhttp-android:5.4.0`.
- Its bundled `assets/PublicSuffixDatabase.list` corresponds to the
  [source snapshot](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/jvmTest/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat)
  dated `2024-11-26_09-06-27_UTC`, Public Suffix List commit
  `bf32ba1ae6228a185ebf297d636f1c6541149c45`.
- Applying OkHttp's sorted-rule encoding to that snapshot reproduced the
  bundled binary byte for byte. The `sourceUrl` points to that exact snapshot,
  rather than the continually changing list.
- `okhttp-public-suffix-list-notice.txt` preserves OkHttp's
  [NOTICE](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/jvmTest/resources/okhttp3/internal/publicsuffix/NOTICE)
  and the source snapshot's opening notice and version comments.
  `mpl-2.0.txt` is the full, unchanged
  [MPL 2.0 text](https://www.mozilla.org/media/MPL/2.0/index.txt).
- On updates verify the shipped list against the new source snapshot and
  [generator](https://github.com/square/okhttp/blob/parent-5.4.0/okhttp/src/jvmTest/kotlin/okhttp3/internal/publicsuffix/PublicSuffixListGenerator.kt),
  and update both its source URL and displayed version.

## Notices obtained directly from archives

Bouncy Castle `org.bouncycastle:bcprov-jdk18on:1.85.2` includes its original MIT
license in `META-INF/LICENSE.md`, with the 2000–2026 Legion of the Bouncy Castle
copyright. Preserve that artifact-specific text through archive extraction;
there is no separate static copy to maintain here. The same principle applies
to other original license and NOTICE files found in runtime archives.
