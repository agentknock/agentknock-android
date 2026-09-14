#!/usr/bin/env python3
"""Add original distribution notices to the resolved AboutLibraries catalogue."""

import argparse
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile


NOTICE_NAME = re.compile(r"^(licen[cs]e|notice|copyright)(?:$|[._-])", re.IGNORECASE)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def text_content(data, source):
    text = data.decode("utf-8")
    if not text.strip():
        raise ValueError(f"Empty license or notice: {source}")
    return text


def archive_notices(path):
    """Read original files, including AAR classes.jar and Google notice bundles."""
    originals = []
    bundled = []

    def scan(archive, prefix=""):
        names = set(archive.namelist())
        for name in sorted(names):
            entry = PurePosixPath(name)
            if entry.name in ("third_party_licenses.json", "third_party_licenses.txt"):
                metadata_name = str(entry.with_name("third_party_licenses.json"))
                text_name = str(entry.with_name("third_party_licenses.txt"))
                if metadata_name not in names or text_name not in names:
                    raise ValueError(f"Incomplete Google license bundle: {path}!{prefix}{name}")
                if name != metadata_name:
                    continue
                metadata = json.loads(archive.read(name))
                contents = archive.read(text_name)
                for component, span in metadata.items():
                    start, length = span["start"], span["length"]
                    # Google's offsets count UTF-8 bytes, not Unicode characters.
                    if type(start) is not int or type(length) is not int or (
                        start < 0 or length <= 0 or start + length > len(contents)
                    ):
                        raise ValueError(f"Invalid Google license span: {path}!{name}: {component}")
                    bundled.append((component, text_content(
                        contents[start:start + length], f"{path}!{name}: {component}"
                    )))
            elif entry.suffix.lower() == ".jar":
                with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                    scan(nested, f"{prefix}{name}!")
            elif NOTICE_NAME.match(entry.name) and entry.suffix.lower() not in (
                ".class", ".kotlin_module"
            ) and not name.endswith("/"):
                originals.append((f"{prefix}{name}", text_content(
                    archive.read(name), f"{path}!{prefix}{name}"
                )))

    with zipfile.ZipFile(path) as archive:
        scan(archive)
    return originals, bundled


def license_record(licenses, name, content, url=None, spdx_id=None):
    identifier = hashlib.sha256(f"{name}\0{content}".encode()).hexdigest()
    record = {"name": name, "content": content, "hash": identifier}
    if url:
        record["url"] = url
    if spdx_id:
        record["spdxId"] = spdx_id
    licenses[identifier] = record
    return identifier


def component_library(identifier, name, references, description, url=None, version=None, source_url=None):
    result = {
        "uniqueId": identifier,
        "name": name,
        "description": description,
        "developers": [],
        "licenses": sorted(references),
    }
    if url:
        result["website"] = url
    if version:
        result["artifactVersion"] = version
    if source_url:
        result["scm"] = {"url": source_url}
    return result


def generate(catalogue, artifacts, supplements, root):
    """Use only shipped artifacts; retain original copyright and NOTICE text."""
    root = Path(root)
    standard_texts = read_json(root / "licenses/standard-texts.json")
    source_libraries = {library["uniqueId"]: library for library in catalogue["libraries"]}
    licenses = deepcopy(catalogue["licenses"])
    runtime = {}
    for artifact in artifacts:
        group, name, version = artifact["coordinate"].split(":")
        identifier = f"{group}:{name}"
        if identifier in runtime and runtime[identifier][0]["coordinate"] != artifact["coordinate"]:
            raise ValueError(f"Multiple runtime versions of {identifier}")
        runtime.setdefault(identifier, []).append(artifact)

    additions = []
    replacement_texts = {}
    for supplement in supplements:
        parents = sorted(set(supplement["artifacts"]) & runtime.keys())
        if supplement["artifacts"] and not parents:
            continue
        for parent in parents:
            version = runtime[parent][0]["coordinate"].rsplit(":", 1)[1]
            if supplement["auditedVersions"].get(parent) != version:
                raise ValueError(f"Re-audit {supplement['id']} for {parent}:{version}")
        content = text_content((root / supplement["licenseFile"]).read_bytes(), supplement["licenseFile"])
        references = {license_record(licenses, supplement["license"], content, supplement.get("url"))}
        if supplement.get("noticeFile"):
            notice = text_content((root / supplement["noticeFile"]).read_bytes(), supplement["noticeFile"])
            references.add(license_record(licenses, "Copyright and notices", notice))
        for parent in parents:
            for identifier in supplement.get("licenseIds", []):
                replacement_texts[parent, identifier] = content
        description = "Included in " + ", ".join(runtime[parent][0]["coordinate"] for parent in parents) if parents else "Included in Agentknock."
        additions.append(component_library(
            f"supplement:{supplement['id']}", supplement["name"], references,
            description, supplement.get("url"), supplement.get("version"), supplement.get("sourceUrl"),
        ))

    libraries = []
    components = {}
    for identifier, files in sorted(runtime.items()):
        coordinate = files[0]["coordinate"]
        if identifier not in source_libraries:
            raise ValueError(f"Runtime dependency missing from catalogue: {coordinate}")
        library = deepcopy(source_libraries[identifier])
        library["name"] = library.get("name") or identifier
        library["artifactVersion"] = coordinate.rsplit(":", 1)[1]
        original_files, bundled = [], []
        for path in sorted({artifact["path"] for artifact in files}):
            originals, components_in_archive = archive_notices(path)
            original_files.extend(originals)
            bundled.extend(components_in_archive)
        original_licenses = [content for name, content in original_files
                             if PurePosixPath(name.rsplit("!", 1)[-1]).name.lower().startswith(("license", "licence"))]
        references = set()
        for reference in library.get("licenses", []):
            if reference not in licenses:
                raise ValueError(f"Unknown license {reference} for {coordinate}")
            record = licenses[reference]
            if not (record.get("content") or "").strip():
                identifiers = (reference, record.get("spdxId"))
                content = next((replacement_texts[identifier, key] for key in identifiers
                                if (identifier, key) in replacement_texts), None)
                standard = next((standard_texts[key] for key in identifiers if key in standard_texts), None)
                if content is None and standard is not None:
                    content = text_content((root / standard).read_bytes(), standard)
                if content is None and original_licenses:
                    content = "\n\n".join(original_licenses)
                if content is None:
                    raise ValueError(f"Missing full license text for {coordinate}: {record['name']}")
                # Never add one artifact's copyright to a shared POM license record.
                reference = license_record(licenses, record["name"], content, record.get("url"), record.get("spdxId"))
            references.add(reference)
        seen_contents = {licenses[reference]["content"] for reference in references}
        for name, content in original_files:
            if content not in seen_contents:
                references.add(license_record(licenses, "Copyright and notices", content))
                seen_contents.add(content)
        if not library.get("licenses") and not original_licenses:
            raise ValueError(f"No licenses for runtime dependency: {coordinate}")
        library["licenses"] = sorted(references)
        libraries.append(library)
        for name, content in bundled:
            key = (name, content)
            components.setdefault(key, set()).add(coordinate)

    for (name, content), parents in sorted(components.items()):
        reference = license_record(licenses, f"{name} — bundled license and notices", content)
        additions.append(component_library(
            f"bundled:{reference}", name, [reference],
            "Notice supplied by " + ", ".join(sorted(parents)),
        ))
    libraries.extend(additions)
    used_licenses = {reference for library in libraries for reference in library["licenses"]}
    return {
        "libraries": sorted(libraries, key=lambda library: (library["name"].casefold(), library["uniqueId"])),
        "licenses": {key: licenses[key] for key in sorted(used_licenses)},
    }


def verify_archive(catalogue_path, archive_path):
    expected = Path(catalogue_path).read_bytes()
    with zipfile.ZipFile(archive_path) as archive:
        # AAPT2 shortens resource paths in optimized releases (e.g. res/M7.json).
        # Compare the actual resource payload, independently of its generated name.
        for entry in archive.infolist():
            if (entry.filename.startswith(("res/", "base/res/"))
                    and entry.file_size == len(expected) and archive.read(entry) == expected):
                return
    raise ValueError(f"Packaged dependency notices are missing or differ from generated catalogue: {archive_path}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generation = commands.add_parser("generate")
    for name in ("catalogue", "artifacts", "supplements", "root", "output"):
        generation.add_argument(f"--{name}", type=Path, required=True)
    verification = commands.add_parser("verify")
    verification.add_argument("--catalogue", type=Path, required=True)
    verification.add_argument("--archive", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        if arguments.command == "verify":
            verify_archive(arguments.catalogue, arguments.archive)
        else:
            result = generate(read_json(arguments.catalogue), read_json(arguments.artifacts),
                              read_json(arguments.supplements), arguments.root)
            arguments.output.parent.mkdir(parents=True, exist_ok=True)
            arguments.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Dependency licenses: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
