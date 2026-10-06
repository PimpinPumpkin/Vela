#!/usr/bin/env python3
"""Builds the documentation site (served at /Vela/docs/ beside the landing page and the F-Droid repo).

The Markdown stays where it lives in the repository, where GitHub renders it. This script copies
each page into build/docs-src under a clean URL, rewrites every relative link (a link to another
published page points at that page, a link to code or any other file points at GitHub), copies the
images the pages use, and runs MkDocs with site/mkdocs.yml into build/docs-site.

    python3 scripts/build-docs-site.py            # build
    python3 scripts/build-docs-site.py --serve    # build, then serve on http://127.0.0.1:8095/Vela/docs/

Adding a page: add it to PAGES (source -> published path) and to the nav in site/mkdocs.yml.
"""
import os
import posixpath
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "build", "docs-src")
OUT = os.path.join(ROOT, "build", "docs-site")
BLOB = "https://github.com/PimpinPumpkin/Vela/blob/main/"
TREE = "https://github.com/PimpinPumpkin/Vela/tree/main/"
HOME = "https://pimpinpumpkin.github.io/Vela/"

# repository path -> published path (relative to the docs root)
PAGES = {
    "docs/README.md": "index.md",
    "docs/FAQ.md": "faq.md",
    "PRIVACY.md": "privacy.md",
    "FDROID.md": "fdroid.md",
    "FEATURES.md": "features.md",
    "docs/LANGUAGES.md": "languages.md",
    "docs/ANDROID-AUTO.md": "android-auto.md",
    "docs/dpad.md": "dpad.md",
    "docs/book/README.md": "book/index.md",
    "docs/book/01-places.md": "book/places.md",
    "docs/book/02-data-and-rebakes.md": "book/data-and-rebakes.md",
    "docs/book/03-cameras.md": "book/cameras.md",
    "docs/book/04-navigation.md": "book/navigation.md",
    "docs/book/05-routing.md": "book/routing.md",
    "docs/book/06-search.md": "book/search.md",
    "docs/book/07-talking-to-google.md": "book/talking-to-google.md",
    "docs/book/08-offline.md": "book/offline.md",
    "docs/book/09-transit.md": "book/transit.md",
    "docs/book/10-android-auto.md": "book/android-auto.md",
    "docs/book/11-drive-chrome.md": "book/drive-chrome.md",
    "docs/book/12-releases.md": "book/releases.md",
    "docs/book/13-settings.md": "book/settings.md",
    "SPEC.md": "spec.md",
    "ROADMAP.md": "roadmap.md",
    "docs/ROADMAP-HISTORY.md": "roadmap-history.md",
    "CONTRIBUTING.md": "contributing.md",
    "docs/TRANSLATING.md": "translating.md",
    "docs/BUILDING.md": "building.md",
    "SECURITY.md": "security.md",
    "CLAUDE.md": "project-notes.md",
}
# Not published as pages; links to them go elsewhere.
ELSEWHERE = {"README.md": HOME}
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif")

LINK = re.compile(r"(\]\()([^)\s]+)((?:\s+\"[^\"]*\")?\))")
ATTR = re.compile(r"((?:href|src)=\")([^\"]+)(\")")
FENCE = re.compile(r"^\s*(```|~~~)")
LIST_ITEM = re.compile(r"^(\s*)([-*+]|\d+[.)])\s+\S")


def page_url(dest):
    """The directory URL MkDocs serves a page at (use_directory_urls): faq.md -> faq/, book/index.md -> book/."""
    base = dest[:-3]
    return "" if base == "index" else (base[: -len("index")] if base.endswith("/index") else base + "/")


def rewrite_target(target, src_repo_path, dest_path, images, html=False):
    if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I) or target.startswith("#"):
        return target
    path, _, anchor = target.partition("#")
    anchor = "#" + anchor if anchor else ""
    repo = posixpath.normpath(posixpath.join(posixpath.dirname(src_repo_path), path)) if path else src_repo_path
    if repo.startswith("../"):
        return target
    if repo in PAGES:
        if html:  # raw HTML is not rewritten by MkDocs, so it gets the served URL directly
            here = page_url(dest_path) or "."
            rel = posixpath.relpath(page_url(PAGES[repo]) or ".", here)
            return ("./" if rel == "." else rel + "/") + anchor
        rel = posixpath.relpath(PAGES[repo], posixpath.dirname(dest_path) or ".")
        return rel + anchor
    if repo in ELSEWHERE:
        return ELSEWHERE[repo] + anchor
    if repo.lower().endswith(IMAGE_EXT) and os.path.isfile(os.path.join(ROOT, repo)):
        pub = "assets/" + repo.replace("/", "_")
        images[repo] = pub
        return posixpath.relpath(pub, (page_url(dest_path) or ".") if html else (posixpath.dirname(dest_path) or "."))
    kind = TREE if os.path.isdir(os.path.join(ROOT, repo)) else BLOB
    return kind + repo + anchor


def transform(text, src_repo_path, dest_path, images):
    """Rewrites links, and bridges two places where GitHub's Markdown and Python-Markdown disagree:
    GitHub starts a list right after a paragraph line (Python-Markdown needs a blank line), and
    GitHub keeps a paragraph indented two spaces after a blank line inside its list item
    (Python-Markdown needs four, and otherwise ends the list there)."""
    out, in_fence, prev = [], False, ""
    in_list, blank_since_item, shift = False, False, 0
    for line in text.split("\n"):
        if FENCE.match(line):
            if not in_fence:  # a fence inside a list item needs the item's four-space indent too
                ind = len(line) - len(line.lstrip())
                shift = 4 - ind if in_list and 1 <= ind < 4 else 0
            in_fence = not in_fence
            out.append(" " * shift + line)
            prev = line
            continue
        if in_fence:
            out.append(" " * shift + line if line.strip() else line)
            prev = line
            continue
        if not in_fence:
            fix = lambda m: m.group(1) + rewrite_target(m.group(2), src_repo_path, dest_path, images) + m.group(3)
            fix_html = lambda m: m.group(1) + rewrite_target(m.group(2), src_repo_path, dest_path, images, html=True) + m.group(3)
            line = LINK.sub(fix, line)
            line = ATTR.sub(fix_html, line)
            indent = len(line) - len(line.lstrip())
            if LIST_ITEM.match(line):
                if prev.strip() and not LIST_ITEM.match(prev) and not prev.lstrip().startswith(("|", "#", ">")) \
                        and (not prev.startswith(" ") or blank_since_item):
                    out.append("")
                in_list, blank_since_item = True, False
            elif not line.strip():
                blank_since_item = in_list
            elif in_list:
                if blank_since_item and 1 <= indent < 4 and not prev.strip():
                    line = " " * 4 + line.lstrip()  # a paragraph that belongs to the item above
                elif blank_since_item and indent == 0 and not prev.strip():
                    in_list, blank_since_item = False, False
        out.append(line)
        prev = line
    return "\n".join(out)


def build():
    shutil.rmtree(SRC, ignore_errors=True)
    os.makedirs(SRC)
    images = {}
    for repo, dest in PAGES.items():
        with open(os.path.join(ROOT, repo), encoding="utf-8") as f:
            text = f.read()
        text = transform(text, repo, dest, images)
        target = os.path.join(SRC, dest)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "w", encoding="utf-8") as f:
            f.write(text)
    os.makedirs(os.path.join(SRC, "assets"), exist_ok=True)
    for repo, pub in images.items():
        shutil.copyfile(os.path.join(ROOT, repo), os.path.join(SRC, pub))
    shutil.copyfile(os.path.join(ROOT, "docs", "logo.svg"), os.path.join(SRC, "assets", "logo.svg"))
    shutil.copytree(os.path.join(ROOT, "site", "docs-theme"), os.path.join(SRC, "theme-assets"))
    args = [sys.executable, "-m", "mkdocs", "build", "--clean", "-q", "-f", os.path.join(ROOT, "site", "mkdocs.yml")]
    subprocess.run(args + (["--strict"] if "--strict" in sys.argv else []), check=True)
    print(f"docs site: {OUT}")


if __name__ == "__main__":
    build()
    if "--serve" in sys.argv:
        stage = os.path.join(ROOT, "build", "docs-serve")
        shutil.rmtree(stage, ignore_errors=True)
        os.makedirs(os.path.join(stage, "Vela"))
        shutil.copytree(OUT, os.path.join(stage, "Vela", "docs"))
        shutil.copyfile(os.path.join(ROOT, "site", "index.html"), os.path.join(stage, "Vela", "index.html"))
        shutil.copytree(os.path.join(ROOT, "site", "assets"), os.path.join(stage, "Vela", "assets"))
        print("serving http://127.0.0.1:8095/Vela/docs/")
        subprocess.run([sys.executable, "-m", "http.server", "8095", "-d", stage])
