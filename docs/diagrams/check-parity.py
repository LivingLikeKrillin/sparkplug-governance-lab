#!/usr/bin/env python3
"""Parity check for the diagram source-of-truth discipline.

Each canonical `docs/diagrams/src/*.mmd` must appear verbatim (modulo YAML
title front-matter and line endings) as a ```mermaid``` block in the doc(s)
that mirror it. Enforces the contract documented in
`docs/diagrams/README.md` -> "Source-of-truth & parity".

Usage:  python docs/diagrams/check-parity.py
Exit 0 if every mirror matches its `.mmd`; exit 1 (with a report) on any drift.
Stdlib only; no third-party deps.
"""
import re
import sys
import pathlib

HERE = pathlib.Path(__file__).resolve().parent   # docs/diagrams
ROOT = HERE.parent.parent                         # repo root

# (source .mmd under src/, [mirror markdown files relative to repo root])
PAIRS = [
    ("governance-lifecycle.mmd",       ["README.md"]),
    ("ot-it-dataflow.mmd",             ["README.md"]),
    ("seq-schema-data-separation.mmd", ["docs/adr/ADR-0008-schema-data-separation.en.md",
                                        "docs/adr/ADR-0008-schema-data-separation.md"]),
    ("seq-ncmd-authorization.mmd",     ["docs/adr/ADR-0011-command-authorization.en.md",
                                        "docs/adr/ADR-0011-command-authorization.md"]),
]


def _norm(s):
    return s.replace("\r\n", "\n").replace("\r", "\n").strip()


def mmd_body(path):
    """The .mmd body with any leading `--- ... ---` YAML front-matter stripped."""
    text = _norm(path.read_text(encoding="utf-8"))
    text = re.sub(r"^---\n.*?\n---\n", "", text, flags=re.S)
    return text.strip()


def mermaid_blocks(path):
    text = _norm(path.read_text(encoding="utf-8"))
    return [b.strip() for b in re.findall(r"```mermaid\n(.*?)```", text, flags=re.S)]


def main():
    failures = []
    for mmd_name, mirrors in PAIRS:
        src = HERE / "src" / mmd_name
        if not src.exists():
            print(f"  MISSING source: src/{mmd_name}")
            failures.append((mmd_name, "<missing source>"))
            continue
        body = mmd_body(src)
        for md in mirrors:
            md_path = ROOT / md
            if not md_path.exists():
                print(f"  MISSING mirror: {md}")
                failures.append((mmd_name, md))
                continue
            if body in mermaid_blocks(md_path):
                print(f"  OK     src/{mmd_name}  <->  {md}")
            else:
                print(f"  DRIFT  src/{mmd_name}  !=   {md}")
                failures.append((mmd_name, md))

    if failures:
        print(f"\nPARITY FAILED: {len(failures)} mirror(s) out of sync. "
              "Re-sync the inline ```mermaid``` block(s) with their src/*.mmd source.")
        return 1
    print("\nPARITY OK: every mirror matches its canonical .mmd source.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
