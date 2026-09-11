"""Convert the matching Release Please changelog section into Play release notes."""

import html
import re


def changelog_entries(changelog, version):
    headings = list(re.finditer(r"^## (?:\[([^\]]+)\]\([^\n]*\)|(\S+))[^\n]*$", changelog, re.M))
    matching = [index for index, heading in enumerate(headings)
                if (heading[1] or heading[2]) == version]
    if len(matching) != 1:
        raise ValueError(f"Expected exactly one changelog section for {version}")
    index = matching[0]
    end = headings[index + 1].start() if index + 1 < len(headings) else len(changelog)
    section = changelog[headings[index].end():end]
    entries = []
    breaking = False
    for line in section.splitlines():
        if line.startswith("### "):
            breaking = "BREAKING" in line.upper()
        elif match := re.match(r"^[*-]\s+(.+)$", line):
            entries.append((breaking, match[1]))
        elif line.strip():
            if not line[0].isspace() or not entries:
                raise ValueError(f"Unexpected changelog content for {version}: {line}")
            flag, text = entries[-1]
            entries[-1] = (flag, f"{text} {line.strip()}")
    result = []
    for breaking, text in entries:
        # Release Please appends commit and pull-request links to each entry.
        text = re.sub(r"\(\[(?:[0-9a-f]{7,40}|#\d+)\]\(https://github\.com/[^)]+/(?:commit|issues|pull)/[^)]+\)\)",
                      "", text)
        text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
        text = re.sub(r"`([^`]+)`|\*\*([^*]+)\*\*", lambda match: match[1] or match[2], text)
        text = " ".join(html.unescape(text).split())
        if not text:
            raise ValueError(f"Empty changelog entry for {version}")
        if breaking:
            text = f"Breaking change: {text}"
        if text not in result:
            result.append(text)
    if not result:
        raise ValueError(f"No changelog entries for {version}")
    return result


def play_release_notes(changelog, version, repo):
    bullets = [f"• {entry}" for entry in changelog_entries(changelog, version)]
    notes = "\n".join(bullets)
    if len(notes) <= 500:
        return notes
    footer = f"Full release notes: https://github.com/{repo}/releases/tag/v{version}"
    if len(footer) > 500:
        raise ValueError("Release notes URL exceeds Play's 500-character limit")
    selected = []
    for bullet in bullets:
        if len("\n".join([*selected, bullet]) + "\n\n" + footer) > 500:
            break
        selected.append(bullet)
    return "\n\n".join(filter(None, ("\n".join(selected), footer)))
