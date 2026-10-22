#!/usr/bin/env python3
"""Keeps the website findable: writes its sitemap, and checks the tags search engines read.

    tools/site-seo.py sitemap   write site/sitemap.xml (the pages workflow runs this before it publishes)
    tools/site-seo.py check     exit 1 if a page breaks the rules below (CI runs this on every pull request)

The site is a GitHub Pages *project* site, https://pintofi.github.io/beanbook/. Two things follow from
that, and both are easy to get wrong:

  * robots.txt is only read at the root of a host, https://pintofi.github.io/robots.txt, which a
    project site does not own. A robots.txt inside site/ would be ignored, so there is none. With no
    file crawlers may fetch everything, which is what is wanted.
  * A sitemap may live in a subdirectory and then covers the URLs under it, so site/sitemap.xml is
    right. It is submitted in Search Console (and Bing Webmaster Tools) rather than named in robots.txt.

The sitemap is written when the site is published, never kept in the repository, so a date in it is
always one the file really changed: Google ignores <lastmod> from sites whose dates are not reliable.
<priority> and <changefreq> are left out because Google ignores them.

Rules checked, from Google Search Central:
  * every page has one <title> and one meta description, and neither repeats on another page;
  * every page has an absolute rel="canonical" link that points at itself and matches og:url;
  * every page has exactly one <h1>, and every <img> has an alt attribute;
  * every ld+json block is valid JSON.
Pages that must not be indexed (404.html, and keys.html, which only redirects) are exempt from the
title, description and heading rules but must say noindex.
"""
import json
import subprocess
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
BASE = "https://pintofi.github.io/beanbook/"
NOT_INDEXED = {"404.html", "keys.html"}


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.titles, self.h1, self.imgs_without_alt = [], 0, 0
        self.description = self.canonical = self.og_url = self.robots = None
        self.ld, self._in_title, self._in_ld = [], False, False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "title":
            self._in_title = True
            self.titles.append("")
        elif tag == "h1":
            self.h1 += 1
        elif tag == "img" and "alt" not in a:
            self.imgs_without_alt += 1
        elif tag == "meta":
            if a.get("name") == "description":
                self.description = a.get("content")
            elif a.get("name") == "robots":
                self.robots = a.get("content")
            elif a.get("property") == "og:url":
                self.og_url = a.get("content")
        elif tag == "link" and a.get("rel") == "canonical":
            self.canonical = a.get("href")
        elif tag == "script" and a.get("type") == "application/ld+json":
            self._in_ld = True
            self.ld.append("")

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
        elif tag == "script":
            self._in_ld = False

    def handle_data(self, data):
        if self._in_title:
            self.titles[-1] += data
        elif self._in_ld:
            self.ld[-1] += data


def pages():
    return sorted(p for p in SITE.glob("*.html"))


def url_of(path):
    return BASE if path.name == "index.html" else BASE + path.name


def check():
    problems, titles, descriptions = [], {}, {}
    for path in pages():
        page = Page()
        page.feed(path.read_text(encoding="utf-8"))
        name = path.name
        if name in NOT_INDEXED:
            if "noindex" not in (page.robots or ""):
                problems.append(f"{name}: not meant for the index, so it needs <meta name=\"robots\" content=\"noindex\">")
            continue
        if len(page.titles) != 1 or not page.titles[0].strip():
            problems.append(f"{name}: needs exactly one non-empty <title>")
        else:
            title = page.titles[0].strip()
            if title in titles:
                problems.append(f"{name}: the title repeats {titles[title]}'s: {title!r}")
            titles[title] = name
        if not page.description or len(page.description.strip()) < 50:
            problems.append(f"{name}: needs a meta description of at least 50 characters")
        else:
            d = page.description.strip()
            if d in descriptions:
                problems.append(f"{name}: the description repeats {descriptions[d]}'s")
            descriptions[d] = name
        want = url_of(path)
        if page.canonical != want:
            problems.append(f"{name}: rel=canonical is {page.canonical!r}, expected {want!r}")
        if page.og_url != want:
            problems.append(f"{name}: og:url is {page.og_url!r}, expected {want!r}")
        if page.h1 != 1:
            problems.append(f"{name}: needs exactly one <h1>, has {page.h1}")
        if page.imgs_without_alt:
            problems.append(f"{name}: {page.imgs_without_alt} <img> without an alt attribute")
        for block in page.ld:
            try:
                json.loads(block)
            except ValueError as error:
                problems.append(f"{name}: ld+json is not valid JSON ({error})")
    return problems


def lastmod(path):
    """The day the file last changed in git, or nothing when git cannot say."""
    result = subprocess.run(
        ["git", "log", "-1", "--format=%cs", "--", str(path.relative_to(ROOT))],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    return result.stdout.strip() or None


def sitemap():
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">']
    for path in pages():
        if path.name in NOT_INDEXED:
            continue
        lines.append("  <url>")
        lines.append(f"    <loc>{url_of(path)}</loc>")
        day = lastmod(path)
        if day:
            lines.append(f"    <lastmod>{day}</lastmod>")
        lines.append("  </url>")
    lines.append("</urlset>")
    (SITE / "sitemap.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {SITE / 'sitemap.xml'}")


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "sitemap":
        sitemap()
    elif command == "check":
        found = check()
        for line in found:
            print("site:", line)
        if found:
            sys.exit(1)
        print(f"{len(pages())} pages checked")
    else:
        sys.exit(__doc__)
