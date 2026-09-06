#!/usr/bin/env python3
"""Render production Compose previews and maintain a local, searchable image gallery."""
import argparse
from datetime import datetime, timezone
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
                image = html.escape(entry["image"], quote=True)
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
<input id="search" type="search" placeholder="Filter screens…" aria-label="Filter screens">
<select id="screen" aria-label="Screen">''' + controls[0] + '''</select>
<select id="variant" aria-label="Variant">''' + controls[1] + '''</select>
<p id="count"></p></header><main>''' + "\n".join(cards) + '''</main><script>
const cards=[...document.querySelectorAll('article')],search=document.querySelector('#search'),screen=document.querySelector('#screen'),variant=document.querySelector('#variant');
function filter(){let count=0;for(const card of cards){card.hidden=!card.dataset.search.includes(search.value.toLowerCase())||(screen.value&&card.dataset.screen!==screen.value)||(variant.value&&card.dataset.variant!==variant.value);if(!card.hidden)count++;}document.querySelector('#count').textContent=count+' previews shown';}
search.addEventListener('input',filter);screen.addEventListener('change',filter);variant.addEventListener('change',filter);filter();
</script></html>'''
    (REPORT / "index.html").write_text(page)


def main():
    screens = catalog()
    variants = {variant_slug(variant): variant for screen in screens.values() for variant in screen["variants"]}
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("screen", nargs="?", choices=screens, help="Render only this screen (all its theme variants). Omit to render everything.")
    parser.add_argument("--variant", choices=["all", *sorted(variants)], default="all",
                        help="Render only this named variant. Omit to render all variants.")
    parser.add_argument("--list", action="store_true", help="List screen names without running Gradle.")
    args = parser.parse_args()
    if args.list:
        for name, screen in screens.items():
            print(f'{name}: {", ".join(map(variant_slug, screen["variants"]))}')
        return
    selected_variant = None if args.variant == "all" else variants[args.variant]
    selected = {(name, variant): method for name, screen in screens.items()
                for variant, method in screen["variants"].items()
                if (args.screen is None or name == args.screen)
                and (selected_variant is None or variant == selected_variant)}
    if not selected:
        parser.error(f'{args.screen} has no {args.variant} variant; available: '
                     + ", ".join(map(variant_slug, screens[args.screen]["variants"])))
    command = ["nix", "develop", "-c", "./gradlew", ":app:updateDebugScreenshotTest", "--rerun"]
    if args.screen or selected_variant:
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
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
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
    write_gallery(entries, screens, args.screen, selected_variant)
    count = sum(map(len, rendered.values()))
    print(f"\nRendered {len(rendered)} screen(s), {count} images in {elapsed:.1f}s (including Nix startup).")
    print(f"Gallery: {REPORT / 'index.html'}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
