# UI preview catalog

Set up the JDK and Android SDK as described in the root [README](../../../README.md#development).
The preview command requires Bash and Python 3.10 or newer and invokes the Gradle
wrapper directly. Nix is optional.

`./preview-ui --list` lists screens and the variants available for each one.
Use `./preview-ui secret-detail --variant dark` to render exactly that screen and
variant, or `./preview-ui --variant dark` to render that variant across screens.
Pass several screen names to render them in one Gradle run, for example
`./preview-ui requests invocation git-sign --variant dark`.
Without `--variant`, all variants of the selected screens are rendered. There is
no special default theme, device size, or text scale.

Each variant has its own top-level `@PreviewTest` function with one explicit
`@Preview`. The command filters those exact functions before rendering. This is
necessary because the renderer's method filter cannot select an individual
annotation inside a multipreview function. Changed Kotlin sources still compile,
but unselected variants do not render.

Open `app/build/reports/ui-previews/index.html` directly in a browser for the
searchable gallery. A single-screen render selects that screen and variant;
change the dropdowns to inspect other cached images. A partial render preserves
other variants and their timestamps, including variants belonging to the same
screen. Missing variants have placeholders and an exact render command.
`catalog.html` provides the same gallery.
Image links include a content hash so updated renders do not reuse cached images.

Every render rebuilds the gallery pages. `./preview-ui --gallery-only` rebuilds
them from cached images without Gradle. Generated pages, manifests, and images
remain under ignored `app/build/`.

### Optional comparisons

For a new comparison, copy the gallery's `screens.json` and `images/` into
`before/` under the gallery directory before making UI changes. Record the source
revision in `before/revision.txt`. Render the changed screens, then open the
“Original → latest” link in the gallery to view `comparison.html`.
The tool reads the snapshot without modifying it.

Comparisons select the Dark variant. An optional `review-screens.json` containing
an ordered array of screen names limits the comparison; otherwise it includes all
baseline screens. Include only screens with a Dark preview in the baseline or
selection. Use matching dimensions and font scales for both renders.

Images appear side by side on desktop and stack on narrow screens, with links to
full resolution. Each image shows its render time. “Changed” compares PNG bytes;
“unchanged” means a new render produced identical bytes; “pending” means an image
is missing or still has the baseline's bytes and timestamp. Cached images do not
prove the latest source changes were rendered.

To compare another design pass, copy its `screens.json` and `images/` into
`previous/` and describe it in `previous/description.txt`. The gallery then links
to `previous-pass.html` as well. Remove `before/`, `previous/`, and
`review-screens.json` when the comparison is finished, then run
`./preview-ui --gallery-only` to remove the comparison pages and links.
The current gallery remains the landing page throughout.

For network access, run from the repository root:

```sh
python3 -m http.server 4242 --bind 0.0.0.0 --directory app/build/reports/ui-previews
```

This serves only the generated gallery directory. Source locations are labels,
not links outside that directory. No account, emulator, or live app data is
needed. `app/build/` can be deleted and regenerated.

## Coverage

| Source file | Review coverage |
| --- | --- |
| `SetupPreviews.kt` | Welcome, initial setup, address change and conflict, locked/unlock failure, temporary-access confirmation |
| `ClientPreviews.kt` | Clients and secrets with the production navigation shell; empty lists; pending pairing/upload rows; active/suspended client; lower client details; verification codes and pairing failure |
| `SecretDetailPreviews.kt` | Environment and SSH secrets, missing values, access overrides, temporary access, instructions and metadata below the fold, 150% text |
| `EditorPreviews.kt` | Create/edit secret, generate/import/replace SSH key, create/edit environment variable, instruction editor, discard/delete confirmations |
| `RequestPreviews.kt` | Request inbox and empty/offline states; command approval, AI escalation/review in progress, completed and verification failure; Git signing; SSH authentication; environment/SSH uploads |
| `SettingsPreviews.kt` | Settings overview; security and restored keys; backup section; enabled/blocked notifications; app/device information; reset and confirmation; free/active/pending/unavailable billing; audit list/detail/empty and tablet layout |

Most named screens produce both `Light` and `Dark` images. Large-text and tablet
cases have explicit annotations. Lower-page cases use the production screen's
scroll state at the same phone size, not a recreation of the hidden UI. The
catalog covers representative visual states, not every combination of data.

## Adding or changing a preview

1. Use the production composable and fixed synthetic fixtures. Keep database,
   network, billing, authentication, and clipboard operations out of fixtures.
2. Add a zero-argument top-level render function with `@PreviewTest`, one explicit
   `@Preview`, and `@Composable`. Give `@Preview` a `group` identifying the screen
   and a `name` identifying the variant. Match the existing variant's configuration
   when reusing its name. Variant names are automatically exposed as kebab-case CLI
   choices; there is no separate variant registry.
3. Have each variant call the same private screen fixture wrapped in `PreviewScreen`.
   Keep dimensions, font scale, locale, and Android night mode in its `@Preview`.
   For a new configuration, add another named variant entry point.
4. Run `./preview-ui --list`, then render just the screen and variant being changed.
   Inspect its full-resolution image. After shared UI changes, render the broader
   set of affected screens and variants.

Fixtures follow a fictional developer, Maya Chen, working on an orders API.
Secret names identify their purpose and environment (`orders-db-prod`,
`orders-db-staging`, `work-ssh`); client names identify machines. Request summaries,
details, and audit records share the same commands and secret metadata. Example
domains, public key bytes, passwords, and identities are synthetic. Long-name and
long-command previews use plausible deployment and Android release workflows.
Play Store previews use these same fixtures.

Callbacks in previews are inert. The real screens remain responsible for
rendering, and their runtime wrappers still obtain platform state normally.
Settings content receives explicit platform display state so its previews don't
depend on Layoutlib's partial Android services. Theme previews disable dynamic
color, and the command forces UTC. Fixtures use a fixed historical date so
Today/Yesterday labels do not drift between runs. The public SSH key is derived
from fixed public bytes; previews contain no private key.

Pairing fixtures use three words from the production address word list and the
production formatter for twelve-digit comparison codes (`1234 5678 9012`).
Dialog fixtures pass `previewDialogModifier()` to account for floating-window
decor insets missing from Layoutlib. At the catalog's 360 dp phone width, the
API 37 emulator limits dialogs to 320 dp, leaving 20 dp on either side. This
constraint preserves smaller dialogs' natural width. Verify the native window
geometry again when adding dialog previews for another device configuration.

Host rendering is useful for layout, wrapping, hierarchy, and color review. It
is not a substitute for testing navigation, keyboard/IME behavior, Android-owned
permission/authentication dialogs, accessibility interactions, or device-specific
dynamic colors. Those still belong in emulator/device checks.

## Play listing screenshots

Run these commands from the repository root.

The eight phone screenshots show command approval, secrets, Git signing, SSH
authentication, secret access settings, client management, pairing, and audit
history, in that order. `PlayStorePreviews.kt` reuses the production screen
fixtures in dark theme at 1080 × 1920 pixels (432 × 768 dp, normal text size).
These store variants are separate from the standard 360 × 800 dp phone previews.

If Pillow is not already installed, create and activate a Python environment:

```sh
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install Pillow
```

Regenerate and export the listing's 24-bit PNGs with:

```sh
python3 scripts/update_play_screenshots.py
```

The script updates `app/src/main/play/listings/en-GB/graphics/phone-screenshots/`
locally. Review the images before uploading them to Google Play.
