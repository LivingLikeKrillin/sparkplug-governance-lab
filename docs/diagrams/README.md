# Diagrams

Version-controlled diagram assets for portfolio surfaces (README, blog, slides, PDF).
Style: "Light / GitHub-native". Spec: `../superpowers/specs/2026-06-17-portfolio-visuals-design.md`.

## Assets

| File | Source | Generated? | Shown in |
|------|--------|-----------|----------|
| `svg/governance-lifecycle.svg` | `src/governance-lifecycle.mmd` | mermaid-cli | hero; README "The one-line idea" |
| `svg/system-architecture.svg` | `src/system-architecture.mmd` | mermaid-cli | README "Architecture" |
| `svg/seq-schema-data-separation.svg` | `src/seq-schema-data-separation.mmd` | mermaid-cli | ADR-0008 |
| `svg/seq-ncmd-authorization.svg` | `src/seq-ncmd-authorization.mmd` | mermaid-cli | ADR-0011 |
| `svg/ot-it-dataflow.svg` | `src/ot-it-dataflow.mmd` | mermaid-cli | README "OT→IT data flow" |
| `svg/nbirth-size.svg` | itself (hand-authored) | no | blog/slides |
| `svg/loss-ledger.svg` | itself (hand-authored) | no | blog/slides |

## Re-rendering

`powershell -NoProfile -File render.ps1` (Windows) or `bash render.sh` (POSIX). Renders every `src/*.mmd`
to `svg/`. First run downloads a Puppeteer Chromium (cached). Docker fallback:
see the comment block in `render.sh`. Hand-authored SVGs are never overwritten.

## Source-of-truth & parity

`src/*.mmd` is canonical. The following README / ADR inline mermaid blocks are
**mirrors** and MUST be kept identical to their `.mmd` source (modulo title front-matter) when either changes:

- `src/governance-lifecycle.mmd` <-> README "The one-line idea"
- `src/system-architecture.mmd` <-> README "Architecture"
- `src/ot-it-dataflow.mmd` <-> README "OT→IT data flow"
- `src/seq-schema-data-separation.mmd` <-> ADR-0008
- `src/seq-ncmd-authorization.mmd` <-> ADR-0011
