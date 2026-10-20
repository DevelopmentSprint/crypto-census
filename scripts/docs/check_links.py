#!/usr/bin/env python3
"""Check generated HTML links, fragments and assets under the GitHub Pages base."""
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit
import sys
import os

ROOT = Path('docs/.vitepress/dist').resolve()
BASE = os.environ.get('DOCS_BASE', '/')


class Document(HTMLParser):
    def __init__(self, source):
        super().__init__(convert_charrefs=True)
        self.ids, self.links = set(), []
        self.feed(source)

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            self.ids.add(attrs['id'])
        for attr in ('href', 'src'):
            if attr in attrs:
                self.links.append(attrs[attr])


def check():
    documents = {p: Document(p.read_text()) for p in ROOT.rglob('*.html')}
    errors = set()
    checked = 0
    for source, doc in documents.items():
        for link in doc.links:
            url = urlsplit(link)
            if url.scheme or url.netloc:
                continue
            target_path = unquote(url.path)
            if target_path.startswith('/'):
                if not target_path.startswith(BASE):
                    errors.add(f'{source.relative_to(ROOT)}: outside Pages base: {link}')
                    continue
                target = ROOT / target_path[len(BASE):]
            else:
                target = source.parent / target_path if target_path else source
            target = target.resolve()
            if target.is_dir():
                target /= 'index.html'
            elif not target.exists() and not target.suffix:
                target = target.with_suffix('.html')
            if not target.is_relative_to(ROOT) or not target.is_file():
                errors.add(f'{source.relative_to(ROOT)}: missing target: {link}')
            elif url.fragment and target in documents and unquote(url.fragment) not in documents[target].ids:
                errors.add(f'{source.relative_to(ROOT)}: missing fragment: {link}')
            checked += 1
    if not documents:
        errors.add('No generated HTML found')
    for error in sorted(errors):
        print(error, file=sys.stderr)
    print(f'Checked {len(documents)} HTML pages and {checked} local links/assets; {len(errors)} errors.')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(check())
