#!/usr/bin/env python3
"""Fail when a document points at something this repository does not contain.

Checked in every tracked Markdown, YAML and shell file:
  1. links pinned to a commit of this repository (github.com/<owner>/sparkplug-governance-lab/{blob,tree}/<sha>/<path>):
     the commit must exist and the path must exist at that commit (needs full history: checkout with fetch-depth 0);
  2. `git checkout <sha>` instructions: the commit must exist;
  3. `-Dexec.mainClass=<class>` run commands: the class must have a source file in this repository
     (src/main/java or legacy/*/src/main/java).
Exits 1 on any failure, and also when nothing was checked (a check that finds no references is not a passing check).
"""
import re, subprocess, sys
from pathlib import Path

REPO = 'sparkplug-governance-lab'
PIN = re.compile(r'https://github\.com/[\w.-]+/' + REPO + r'/(blob|tree)/([0-9a-f]{7,40})/([^\s)\]>"\'#?]+)')
CHECKOUT = re.compile(r'git (?:checkout|switch --detach) ([0-9a-f]{7,40})\b')
MAIN = re.compile(r'-Dexec\.mainClass=([\w.]+)')


def git(*args):
    return subprocess.run(['git', *args], capture_output=True, text=True)


def exists(sha, path=None):
    spec = sha + '^{commit}' if path is None else f'{sha}:{path}'
    return git('cat-file', '-e', spec).returncode == 0


def source_of(cls):
    rel = cls.replace('.', '/') + '.java'
    roots = [Path('src/main/java')] + sorted(Path('legacy').glob('*/src/main/java'))
    return any((r / rel).is_file() for r in roots)


def main():
    files = [f for f in git('ls-files').stdout.splitlines() if f.endswith(('.md', '.yml', '.yaml', '.sh'))]
    problems, checked = [], 0
    for f in files:
        for n, line in enumerate(Path(f).read_text(encoding='utf-8', errors='replace').splitlines(), 1):
            for kind, sha, path in PIN.findall(line):
                checked += 1
                path = path.rstrip('/.,;:')
                if not exists(sha):
                    problems.append(f'{f}:{n}: pinned commit {sha} is not in this repository')
                elif not exists(sha, path):
                    problems.append(f'{f}:{n}: {path} does not exist at {sha}')
            for sha in CHECKOUT.findall(line):
                checked += 1
                if not exists(sha):
                    problems.append(f'{f}:{n}: git checkout {sha}: no such commit in this repository')
            for cls in MAIN.findall(line):
                checked += 1
                if not source_of(cls):
                    problems.append(f'{f}:{n}: -Dexec.mainClass={cls}: no source file in this repository')
    for p in problems:
        print(p)
    if checked == 0:
        print('no references checked: the check found nothing to verify')
        return 1
    print(f'doc refs: {checked} checked, {len(problems)} broken')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
