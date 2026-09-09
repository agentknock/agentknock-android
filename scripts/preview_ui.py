#!/usr/bin/env python3
"""Render production Compose previews and maintain a local, searchable image gallery."""
import argparse
from datetime import datetime, timezone
import hashlib
import html
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/screenshotTest/kotlin/dev/agentknock/preview"
REPORT = ROOT / "app/build/reports/ui-previews"
RESULTS = ROOT / "app/build/test-results/updateDebugScreenshotTest"


def catalog():
    screens = {}
    for source in sorted(SOURCE.glob("*.kt")):
        for match in re.finditer(r"@PreviewTest\b(.*?)\bfun (\w+)\(", source.read_text(), re.S):
            annotation = re.search(r"@Preview\((.*?)\)", match[1], re.S)
            if annotation is None:
                raise ValueError(f"{match[2]} needs one explicit @Preview annotation")
            fields = dict(re.findall(r'(name|group)\s*=\s*"([^"]+)"', annotation[1]))
            name, variant = fields["group"], fields["name"]
            screen = screens.setdefault(name, {"source": source, "variants": {}})
            if variant in screen["variants"]:
                raise ValueError(f"Duplicate preview: {name} / {variant}")
            screen["variants"][variant] = f"dev.agentknock.preview.{source.stem}Kt.{match[2]}"
    return dict(sorted(screens.items()))


def variant_slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower())


def select_options(values, selected, all_label):
    return f'<option value="">{all_label}</option>' + "".join(
        f'<option value="{html.escape(value, quote=True)}"' +
        (' selected' if value == selected else '') + f'>{html.escape(value)}</option>'
        for value in values
    )


def write_gallery(entries, screens, selected_screen=None, selected_variant=None):
    comparisons = (
        ("before", "comparison.html", "Original → latest"),
        ("previous", "previous-pass.html", "Previous pass → latest"),
    )
    comparison_links = []
    for directory, filename, label in comparisons:
        if (REPORT / directory / "screens.json").exists():
            write_review_gallery(entries, screens, directory, filename)
            comparison_links.append(f'<a href="{filename}">{label}</a>')
        else:
            (REPORT / filename).unlink(missing_ok=True)
    comparison_navigation = ('<p>' + ' · '.join(comparison_links) + '</p>') if comparison_links else ''
    cards = []
    for name, screen in screens.items():
        previews = {entry["variant"]: entry for entry in entries.get(name, [])}
        for variant in screen["variants"]:
            title = html.escape(f'{name} · {variant}')
            command = html.escape(f'./preview-ui {name} --variant {variant_slug(variant)}')
            card = (f'<article data-search="{title.lower()}" data-screen="{html.escape(name, quote=True)}" '
                    f'data-variant="{html.escape(variant, quote=True)}"><h2>{title}</h2>')
            entry = previews.get(variant)
            if entry:
                digest = hashlib.sha256((REPORT / entry["image"]).read_bytes()).hexdigest()
                image = html.escape(f'{entry["image"]}?v={digest[:16]}', quote=True)
                card += (f'<a href="{image}" target="_blank"><img loading="lazy" src="{image}" alt="{title}"></a>'
                         f'<p><code>{command}</code></p><p>{entry["rendered_at"]}</p>')
            else:
                card += f'<p>Not rendered yet. Run <code>{command}</code></p>'
            # A repository-relative source label works both on disk and through the gallery-only server.
            card += f'<p>{html.escape(str(screen["source"].relative_to(ROOT)))}</p></article>'
            cards.append(card)
    variants = list(dict.fromkeys(variant for screen in screens.values() for variant in screen["variants"]))
    controls = (select_options(screens, selected_screen, "All screens"),
                select_options(variants, selected_variant, "All variants"))
    page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Agentknock UI previews</title><style>
*{box-sizing:border-box}body{margin:0;background:#eff1f4;color:#20262e;font:15px system-ui,sans-serif}
header{position:sticky;top:0;background:#fff;padding:16px 24px;border-bottom:1px solid #cdd3dc;z-index:1}
h1{font-size:22px;margin:0 0 8px}header p{margin:6px 0;color:#536071}input,select{font:inherit;padding:8px;border:1px solid #aeb7c4;border-radius:6px}
input{width:min(420px,65vw)}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:24px;padding:24px;align-items:start}
article{background:#fff;padding:12px;border-radius:10px;min-width:0}article[hidden]{display:none}h2{font-size:15px;margin:0 0 12px}
img{display:block;width:100%;height:auto;border:1px solid #d5dae1}article p{font-size:12px;color:#596477;overflow-wrap:anywhere}a{color:#2457a6}code{font-size:12px}
</style><header><h1>Agentknock UI previews</h1><p>Production Compose screens with synthetic fixtures. Click an image for full resolution. Timestamps show each screen's last render.</p>
''' + comparison_navigation + '''
<input id="search" type="search" placeholder="Filter screens…" aria-label="Filter screens">
<select id="screen" aria-label="Screen">''' + controls[0] + '''</select>
<select id="variant" aria-label="Variant">''' + controls[1] + '''</select>
<p id="count"></p></header><main>''' + "\n".join(cards) + '''</main><script>
const cards=[...document.querySelectorAll('article')],search=document.querySelector('#search'),screen=document.querySelector('#screen'),variant=document.querySelector('#variant');
function filter(){let count=0;for(const card of cards){card.hidden=!card.dataset.search.includes(search.value.toLowerCase())||(screen.value&&card.dataset.screen!==screen.value)||(variant.value&&card.dataset.variant!==variant.value);if(!card.hidden)count++;}document.querySelector('#count').textContent=count+' previews shown';}
search.addEventListener('input',filter);screen.addEventListener('change',filter);variant.addEventListener('change',filter);filter();
</script></html>'''
    (REPORT / "catalog.html").write_text(page)
    (REPORT / "index.html").write_text(page)


def write_review_gallery(entries, screens, baseline_directory="before", output_filename="comparison.html"):
    baseline_path = REPORT / baseline_directory
    baseline = json.loads((baseline_path / "screens.json").read_text())
    original = baseline_directory == "before"
    baseline_label = "Original baseline" if original else "Previous design pass"
    comparison_label = "Original → latest" if original else "Previous pass → latest"
    if original:
        revision = (baseline_path / "revision.txt").read_text().strip()
        baseline_description = f'Originals are preserved from <code>{html.escape(revision[:7])}</code>.'
    else:
        baseline_description = html.escape((baseline_path / "description.txt").read_text().strip())
    comparison_links = []
    for directory, filename, label in (
        ("before", "comparison.html", "Original → latest"),
        ("previous", "previous-pass.html", "Previous pass → latest"),
    ):
        if (REPORT / directory / "screens.json").exists():
            current = ' aria-current="page"' if filename == output_filename else ''
            comparison_links.append(f'<a href="{filename}"{current}>{label}</a>')
    selection = REPORT / "review-screens.json"
    names = json.loads(selection.read_text()) if selection.exists() else list(baseline)

    def dark_entry(manifest, name):
        return next((entry for entry in manifest.get(name, []) if entry["variant"] == "Dark"), None)

    def image_hash(entry, directory):
        path = directory / entry["image"] if entry else None
        return hashlib.sha256(path.read_bytes()).hexdigest() if path and path.is_file() else None

    def image_panel(name, label, entry, digest, prefix=""):
        panel = f'<section><h3>{label}</h3>'
        if digest:
            source = html.escape(prefix + entry["image"], quote=True)
            rendered_at = datetime.fromisoformat(entry["rendered_at"].replace(" UTC", "+00:00"))
            rendered_label = rendered_at.astimezone(timezone.utc).strftime("%b %-d, %H:%M UTC")
            # The content hash prevents a browser from reusing an older focused render.
            source += f'?v={digest[:16]}'
            panel += (f'<p>Rendered {html.escape(rendered_label)}</p>'
                      f'<a href="{source}" target="_blank" rel="noopener" '
                      f'aria-label="Open {html.escape(name, quote=True)} {label.lower()} at full size">'
                      f'<img loading="lazy" src="{source}" alt="{html.escape(name, quote=True)} · {label} · Dark"></a>'
                      f'<p><a href="{source}" target="_blank" rel="noopener">Open full-size image</a></p>')
        else:
            panel += '<p class="placeholder">No image available.</p>'
        return panel + '</section>'

    cards = []
    for name in names:
        if name not in screens or "Dark" not in screens[name]["variants"]:
            raise ValueError(f"Review screen has no Dark preview: {name}")
        before, after = dark_entry(baseline, name), dark_entry(entries, name)
        before_hash = image_hash(before, baseline_path)
        after_hash = image_hash(after, REPORT)
        if not before_hash:
            state, status = "pending", "Missing baseline image"
        elif not after_hash:
            state, status = "pending", "Pending render"
        elif before_hash != after_hash:
            state, status = "changed", "Changed image"
        elif before["rendered_at"] == after["rendered_at"]:
            state, status = "pending", "Pending rerender · cached baseline image"
        else:
            state, status = "unchanged", "Rerendered · image unchanged"
        escaped_name = html.escape(name, quote=True)
        cards.append(
            f'<article data-screen="{escaped_name}" data-state="{state}">'
            f'<h2>{escaped_name} <span class="status {state}">{status}</span></h2>'
            '<div class="comparison">' + image_panel(name, baseline_label, before, before_hash, baseline_directory + "/")
            + image_panel(name, "Latest render", after, after_hash) + '</div>'
            f'<p class="command"><code>./preview-ui {escaped_name} --variant dark</code></p></article>'
        )
    page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Agentknock · ''' + comparison_label + '''</title><style>
*{box-sizing:border-box}body{margin:0;background:#111418;color:#edf0f3;font:16px system-ui,sans-serif}
header{padding:24px;max-width:1100px;margin:auto}h1{font-size:26px;margin:0 0 12px}p{line-height:1.5;color:#aeb8c4}
a{color:#a6caff}code{overflow-wrap:anywhere}input,select{font:inherit;padding:10px;background:#222831;color:inherit;border:1px solid #657181;border-radius:6px;max-width:100%}
.controls{display:flex;gap:12px;flex-wrap:wrap}.controls input{width:300px}main{max-width:1100px;margin:auto;padding:0 24px 40px}
.comparison-links{display:flex;gap:12px;flex-wrap:wrap;margin:16px 0}.comparison-links a{padding:10px 14px;border:1px solid #657181;border-radius:6px;text-decoration:none}.comparison-links a[aria-current=page]{background:#283d55;border-color:#a6caff;color:#fff}
article{border-top:1px solid #434d59;padding:24px 0 32px}article[hidden]{display:none}h2{display:flex;align-items:center;gap:12px;flex-wrap:wrap;font-size:21px;margin:0 0 20px}
.status{font-size:13px;font-weight:500;border:1px solid #657181;padding:5px 9px;border-radius:6px}.changed{color:#a9e9c5}.pending{color:#f2ce91}
.comparison{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:28px;max-width:868px;margin:auto}
section{min-width:0}h3{font-size:17px;margin:0}section p{font-size:13px;margin:8px 0 12px}
img{display:block;width:100%;height:auto;border:1px solid #434d59}section>a{display:block}.placeholder{padding:48px 16px;border:1px dashed #657181}
.command{font-size:13px;max-width:868px;margin:16px auto 0}
@media(max-width:640px){header{padding:20px 16px}main{padding:0 16px 24px}.comparison{grid-template-columns:1fr;max-width:420px;gap:24px}}
</style><header><h1>Dark phone review</h1>
<nav class="comparison-links" aria-label="Choose comparison">''' + ''.join(comparison_links) + '''</nav>
<p>''' + baseline_label + ''' compared with the latest renders. Dark mode, phone size.</p>
<details><summary>About this comparison</summary>
<p>''' + baseline_description + '''
Each image shows its own render time.</p>
<p>Changed and unchanged compare image contents. Pending means an image is missing or has not been rerendered since the baseline. Render times do not certify current source coverage.</p></details>
<p><a href="catalog.html">Browse all screens and variants</a></p>
<div class="controls"><input id="search" type="search" placeholder="Filter review screens…" aria-label="Filter review screens">
<select id="screen" aria-label="Review screen">''' + select_options(names, None, "All review screens") + '''</select></div>
<p id="count" aria-live="polite"></p></header><main>''' + '\n'.join(cards) + '''</main><script>
const cards=[...document.querySelectorAll('article')],search=document.querySelector('#search'),screen=document.querySelector('#screen');
function filter(){let count=0;for(const card of cards){card.hidden=!card.dataset.screen.toLowerCase().includes(search.value.toLowerCase())||(screen.value&&card.dataset.screen!==screen.value);if(!card.hidden)count++;}document.querySelector('#count').textContent=count+' review screens shown';}
search.addEventListener('input',filter);screen.addEventListener('change',filter);filter();
</script></html>'''
    (REPORT / output_filename).write_text(page)


def main():
    screens = catalog()
    variants = {variant_slug(variant): variant for screen in screens.values() for variant in screen["variants"]}
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("screens", nargs="*", metavar="SCREEN", help="Render only these screens (all their theme variants). Omit to render everything; use --list for names.")
    parser.add_argument("--variant", choices=["all", *sorted(variants)], default="all",
                        help="Render only this named variant. Omit to render all variants.")
    parser.add_argument("--list", action="store_true", help="List screen names without running Gradle.")
    parser.add_argument("--gallery-only", action="store_true", help="Rebuild gallery pages from cached images without running Gradle.")
    args = parser.parse_args()
    unknown = set(args.screens) - screens.keys()
    if unknown:
        parser.error(f'Unknown screens: {", ".join(sorted(unknown))}; use --list for names')
    if args.list:
        for name, screen in screens.items():
            print(f'{name}: {", ".join(map(variant_slug, screen["variants"]))}')
        return
    if args.gallery_only:
        write_gallery(json.loads((REPORT / "screens.json").read_text()), screens)
        print(f"Gallery: {REPORT / 'index.html'}")
        return
    selected_variant = None if args.variant == "all" else variants[args.variant]
    selected = {(name, variant): method for name, screen in screens.items()
                for variant, method in screen["variants"].items()
                if (not args.screens or name in args.screens)
                and (selected_variant is None or variant == selected_variant)}
    if not selected:
        parser.error(f'No selected screens have the {args.variant} variant')
    missing = set(args.screens) - {name for name, _ in selected}
    if missing:
        parser.error(f'Screens without the {args.variant} variant: {", ".join(sorted(missing))}')
    command = ["nix", "develop", "-c", "./gradlew", ":app:updateDebugScreenshotTest", "--rerun"]
    if args.screens or selected_variant:
        for method in selected.values():
            command += ["--tests", method]
    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = (env.get("JAVA_TOOL_OPTIONS", "") + " -Duser.timezone=UTC").strip()
    started = time.monotonic()
    subprocess.run(command, cwd=ROOT, env=env, check=True)
    elapsed = time.monotonic() - started
    rendered = {}
    by_method = {method: pair for pair, method in selected.items()}
    completed = set()
    stamp = datetime.now(timezone.utc).isoformat(timespec="microseconds")
    REPORT.mkdir(parents=True, exist_ok=True)
    for result in sorted(RESULTS.glob("TEST-*.xml")):
        suite = ET.parse(result).getroot()
        for case in suite.findall("testcase"):
            properties = {p.attrib["name"]: p.attrib["value"] for p in case.findall("properties/property")}
            method = properties.get("PreviewScreenshot.methodName")
            if method is None:
                raise RuntimeError(f"Missing screenshot result metadata: {case.attrib}")
            pair = by_method[f'{case.attrib["classname"]}.{method}']
            name, expected_variant = pair
            if case.find("failure") is not None or case.find("skipped") is not None:
                raise RuntimeError(f"Preview did not render: {name}")
            variant = properties["PreviewScreenshot.previewName"]
            if variant != expected_variant or pair in completed:
                raise RuntimeError(f"Unexpected or repeated preview variant: {name} / {variant}")
            completed.add(pair)
            filename = f'{name}-{re.sub(r"[^a-z0-9]+", "-", variant.lower())}.png'
            destination = REPORT / "images" / filename
            destination.parent.mkdir(exist_ok=True)
            shutil.copyfile(ROOT / properties["PreviewScreenshot.refImagePath"], destination)
            rendered.setdefault(name, []).append({"variant": variant, "image": f"images/{filename}", "rendered_at": stamp})
    if completed != set(selected):
        raise RuntimeError(f"Missing rendered previews: {sorted(set(selected) - completed)}")
    manifest = REPORT / "screens.json"
    entries = json.loads(manifest.read_text()) if manifest.exists() else {}
    entries = {name: [entry for entry in previews if entry["variant"] in screens[name]["variants"]]
               for name, previews in entries.items() if name in screens}
    for name, previews in rendered.items():
        merged = {entry["variant"]: entry for entry in entries.get(name, [])}
        merged.update({entry["variant"]: entry for entry in previews})
        entries[name] = list(merged.values())
    manifest.write_text(json.dumps(entries, indent=2) + "\n")
    write_gallery(entries, screens, args.screens[0] if len(args.screens) == 1 else None, selected_variant)
    count = sum(map(len, rendered.values()))
    print(f"\nRendered {len(rendered)} screen(s), {count} images in {elapsed:.1f}s (including Nix startup).")
    print(f"Gallery: {REPORT / 'index.html'}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
