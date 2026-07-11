# Diagrams

Version-controlled diagram assets for portfolio surfaces (README, blog, slides, PDF).
Style: "Light / GitHub-native". Spec: `../superpowers/specs/2026-06-17-portfolio-visuals-design.md`.

## Assets

| File | Source | Generated? | Shown in |
|------|--------|-----------|----------|
| `svg/governance-lifecycle.svg` | itself (hand-authored) | no | hero; README "The one-line idea" |
| `svg/system-architecture.svg` | itself (hand-authored) | no | README "Architecture" |
| `svg/seq-schema-data-separation.svg` | itself (hand-authored) | no | ADR-0008 |
| `svg/seq-ncmd-authorization.svg` | itself (hand-authored) | no | ADR-0011 |
| `svg/ot-it-dataflow.svg` | itself (hand-authored) | no | README "OT→IT data flow" |
| `svg/nbirth-size.svg` | itself (hand-authored) | no | blog/slides |
| `svg/loss-ledger.svg` | itself (hand-authored) | no | blog/slides |

## Re-rendering

`powershell -NoProfile -File render.ps1` (Windows) or `bash render.sh` (POSIX). Renders every `src/*.mmd`
to `svg/`. First run downloads a Puppeteer Chromium (cached). Docker fallback:
see the comment block in `render.sh`. Hand-authored SVGs are never overwritten.

The scripts are hardened to run from a path containing literal `[` `]` brackets
(they `cd` into this dir and pass relative paths) — see the header comment in `render.sh`.

Rendered SVGs use an **opaque white canvas** (`-b "#ffffff"`), not a transparent one,
so each diagram reads as a clean card on GitHub dark mode (a transparent canvas would
put dark node text on a dark page). The hand-authored charts use the same opaque card.

## Source-of-truth & parity

`src/*.mmd` is canonical. The following README / ADR inline mermaid blocks are
**mirrors** and MUST be kept identical to their `.mmd` source (modulo title front-matter) when either changes:

- `src/governance-lifecycle.mmd` <-> README "The one-line idea"
- `src/ot-it-dataflow.mmd` <-> README "OT→IT data flow"
- `src/seq-schema-data-separation.mmd` <-> ADR-0008 (English + Korean)
- `src/seq-ncmd-authorization.mmd` <-> ADR-0011 (English + Korean)

This discipline is **enforced**, not just documented — run:

```
python check-parity.py
```

It checks that each `.mmd` body (front-matter stripped) appears verbatim as a
```mermaid``` block in every mirror, and exits non-zero on any drift (stdlib
only, suitable for a CI step or pre-commit hook).

`system-architecture.svg` is **not** in this list: it is a hand-authored SVG
(no `.mmd`, embedded in the README as an image), so there is no mermaid mirror
to keep in sync.
