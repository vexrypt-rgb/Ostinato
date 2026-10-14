#!/usr/bin/env python3
"""Writes the setting descriptions the Ostinato screen shows, from the Javadoc in Settings.java.

Run from the repository root:  python scripts/gen_setting_descriptions.py

Adds the settings that have no description yet and drops the ones that no longer exist. Descriptions already
in the file are kept as they are, since many were reworded for the screen; --refresh rewrites every one from
the Javadoc. SettingCatalogTest fails when a setting has no entry in the file.
"""
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, 'src', 'api', 'java', 'baritone', 'api', 'Settings.java')
TARGET = os.path.join(ROOT, 'src', 'launch', 'resources', 'assets', 'baritone', 'ostinato',
                      'setting-descriptions.json')

FIELD = re.compile(r'/\*\*(?P<doc>(?:(?!\*/).)*)\*/\s*'
                   r'(?P<annotations>(?:@\w+(?:\([^)]*\))?\s*)*)'
                   r'public\s+final\s+Setting<.*?>\s+(?P<name>\w+)\s*=', re.S)


def link(m):
    """{@link #other} reads as "other", {@link Type#member label} as "label"."""
    target, _, label = m.group(1).strip().partition(' ')
    return label.strip() or target.lstrip('#')


def clean(doc):
    lines = []
    for line in doc.splitlines():
        line = re.sub(r'^\s*\*?\s?', '', line).strip()
        if line.startswith('@'):
            break  # block tags (@see, @deprecated) are not part of the description
        lines.append(line)
    text = ' '.join(l for l in lines if l)
    text = re.sub(r'\{@(?:link|linkplain)\s+([^}]*)\}', link, text)
    text = re.sub(r'\{@(?:code|literal)\s+([^}]*)\}', r'\1', text)
    text = re.sub(r'<li\b[^>]*>', ' - ', text)
    text = re.sub(r'</?[a-zA-Z][^>]*>', ' ', text)
    text = text.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')
    return re.sub(r'\s+', ' ', text).strip()


def main():
    refresh = '--refresh' in sys.argv[1:]
    with io.open(SOURCE, encoding='utf-8') as f:
        source = f.read()
    documented = {}
    for m in FIELD.finditer(source):
        if '@JavaOnly' in m.group('annotations'):
            continue  # not something the screen can edit
        text = clean(m.group('doc'))
        if text:
            documented[m.group('name')] = text
    current = {}
    if not refresh and os.path.exists(TARGET):
        with io.open(TARGET, encoding='utf-8') as f:
            current = json.load(f)
    # Entries already in the file were worded for the screen and are kept; settings that are gone go with them.
    out = {name: text for name, text in current.items() if name in documented}
    added = [name for name in documented if name not in out]
    for name in added:
        out[name] = documented[name]
    with io.open(TARGET, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, indent=1)
        f.write('\n')
    print('%d descriptions (%d added, %d removed) -> %s'
          % (len(out), len(added), len(current) - (len(out) - len(added)), os.path.relpath(TARGET, ROOT)))


if __name__ == '__main__':
    main()
