# Portfolio Visual Assets — Design

**Date:** 2026-06-17
**Status:** Approved (design), pending implementation plan
**Scope:** Diagram assets only (no web case-study page, no running UI)

## Problem

`sparkplug-governance-lab` is a policy-as-code / governance-as-code lab. Its value
lives in *enforced behavior and measurements*, not in a UI — so no running UI is
warranted. However, the project will appear in portfolio contexts (GitHub README, a
web case-study page, presentation/PDF slides, a personal tech blog), and those
surfaces need **visual material** to communicate four things at a glance:

1. **Governance design** — policy-as-code; a pre-deploy gate opens a loop that
   runtime drift detection closes.
2. **Protocol depth** — Sparkplug implemented from primitives (session, schema↔data
   separation, NCMD authorization).
3. **Honest engineering** — lossy mappings surfaced as first-class outputs; measured
   numbers, not claims.
4. **OT→IT integration** — OPC UA information model → UDT → MQTT → Kafka.

The gap: visual material today is three inline Mermaid diagrams (README architecture +
ADR-0008 + ADR-0011). There are no rendered image assets and no measurement
visualizations — the standout numbers (`328 B vs 162 B`, loss ledger composition) are
buried in prose and console output.

"Visual material" here means **diagrams**, not an interactive interface. The right
artifacts are version-controlled diagram sources rendered to portable SVG — which fits
the repo's policy-as-code ethos and carries zero runtime/maintenance burden.

## Goals

- A single, consistent set of diagram assets covering all four proof points.
- One visual style applied uniformly, legible across all four surfaces.
- Sources are version-controlled text (Mermaid + hand-authored SVG), single source of
  truth; rendering is reproducible.
- Measurement numbers in the assets match their ADR sources exactly.

## Non-Goals

- No web case-study page (assets only; the page is a separate future effort).
- No running UI, dashboard, or interactive component.
- No new measurement work — all numbers come from existing ADRs/demos.
- No unrelated diagram redesign beyond the seven assets below.

## Approach

**Hybrid sourcing (chosen over all-Mermaid or all-hand-SVG):**

- Graph/sequence diagrams → **Mermaid** source (`.mmd`), rendered to SVG via
  `mermaid-cli`. Mermaid's auto-layout earns its keep on larger flow/sequence diagrams,
  and the README already uses Mermaid for these.
- Measurement charts → **hand-authored SVG**. Mermaid's `xychart` is experimental and
  cannot match the chosen style or label precision; the charts are the asset that
  proves "honest engineering," so they get pixel control.

**Render toolchain:** `npx @mermaid-js/mermaid-cli` (host Node 22 is present and
working). Docker (`minlag/mermaid-cli`) documented as a fallback for contributors
without Node. (Rejected Docker-primary: Windows volume mounts are fragile; the author's
Node toolchain works today.)

## Visual Style — "Light / GitHub-native" (Style A)

Applied uniformly to all seven SVGs.

- **Background:** transparent, with a subtle white/light-gray rounded **card panel**
  (border `#d0d7de`). This reads as an intentional card on both GitHub light and dark
  mode, avoiding the "white box on dark page" problem.
- **Nodes:** fill `#f6f8fa`, border `#d0d7de`, text `#1f2328`.
- **Accents:** governance/primary `#0969da` (fill `#ddf4ff`); admit/success `#1a7f37`;
  deny/loss `#cf222e`; warning/amber `#9a6700`; arrows/edges `#57606a`.
- **Fonts:** titles in system sans; code-like labels (topics, `schemaRef`, metric
  names) in monospace. No external/web font dependencies — only standard families, so
  SVGs render identically offline and in slides/PDF.

Style tokens are centralized in `docs/diagrams/mermaid-theme.json` (`themeVariables`)
for Mermaid output and mirrored as literal hex constants in the hand-authored SVGs.

## Asset Inventory (7 SVG outputs, 6 conceptual assets)

| # | Output file | Source | Proves | Origin |
|---|-------------|--------|--------|--------|
| 1 | `governance-lifecycle.svg` (hero) | `src/governance-lifecycle.mmd` | governance design, honest eng | **New.** Loop: `SchemaGate` (pre-deploy, fail-closed) admits → `Registry` (SemVer source of truth) → definitions → `Edge/UNS` → observed NBIRTH → `DriftMonitor` (runtime, detect-only) → drift signal back to governance. Caption: the gate opens the loop, drift closes it. |
| 2 | `system-architecture.svg` | `src/system-architecture.mmd` | whole picture, OT→IT | Reuse README architecture Mermaid block. |
| 3 | `seq-schema-data-separation.svg` | `src/seq-schema-data-separation.mmd` | protocol depth | Reuse ADR-0008 sequence block (retained DEFINITION once; thin **162 B vs 328 B inline**). |
| 4 | `seq-ncmd-authorization.svg` | `src/seq-ncmd-authorization.mmd` | governance, protocol depth | Reuse ADR-0011 flowchart (the case a broker topic ACL cannot block — command identity in payload). |
| 5 | `ot-it-dataflow.svg` | `src/ot-it-dataflow.mmd` | OT→IT integration | **New.** OPC UA server → Milo browse → `OpcUaTypeMapper` → `UdtDefinition` + `LossLedger` → retained DEFINITION + thin NDATA → Edge → MQTT → Kafka (log compaction) with side-channel (`ua_ticks`, `ua_statuscode`). |
| 6a | `nbirth-size.svg` | hand-authored SVG | honest engineering | **New.** Bar: inline `328 B` vs thin `162 B` (`166 B`/birth saved, ≈49%). |
| 6b | `loss-ledger.svg` | hand-authored SVG | honest engineering | **New.** Loss ledger composition: `9 members: 8 clean / 1 side-channel preserved / 0 type-identity lost`, with LossClass legend (CLEAN / PRECISION_LOSS / TYPE_IDENTITY_LOSS / SIDE_CHANNEL_REQUIRED). |

All numbers trace to ADR sources: 328/162/166 from ADR-0008; the `8 clean / 1
side-channel / 0 type-identity lost` example and the four LossClasses from ADR-0010.

## Directory Layout

```
docs/diagrams/
  src/                            # Mermaid sources (canonical for #1–#5)
    governance-lifecycle.mmd
    system-architecture.mmd
    seq-schema-data-separation.mmd
    seq-ncmd-authorization.mmd
    ot-it-dataflow.mmd
  svg/                            # committed outputs (rendered #1–#5 + hand-authored #6a/#6b)
    governance-lifecycle.svg
    system-architecture.svg
    seq-schema-data-separation.svg
    seq-ncmd-authorization.svg
    ot-it-dataflow.svg
    nbirth-size.svg               # hand-authored (also its own source)
    loss-ledger.svg               # hand-authored (also its own source)
  mermaid-theme.json              # Style A themeVariables
  render.ps1                      # Windows: npx mermaid-cli batch render
  render.sh                       # POSIX equivalent
  README.md                       # per-diagram description, re-render steps, generated-vs-hand-authored map
```

## Render Pipeline

- `render.ps1` / `render.sh` iterate `src/*.mmd` and run:
  `npx -y @mermaid-js/mermaid-cli -i src/<name>.mmd -o svg/<name>.svg -t neutral -c mermaid-theme.json -b transparent`
- Hand-authored SVGs (`nbirth-size.svg`, `loss-ledger.svg`) are **not** render targets —
  they are committed sources, edited by hand.
- First run downloads a Puppeteer Chromium; this is documented in `docs/diagrams/README.md`.
- Docker fallback (no host Node): `docker run --rm -v "$PWD:/data" minlag/mermaid-cli …`,
  documented for contributors.

## Embedding Strategy (per surface)

The single source of truth is `src/*.mmd` (for #1–#5) and the hand-authored SVGs (for
#6a/#6b). Surfaces consume them differently:

- **README:** keep **native inline Mermaid** for #2/#3/#4 (the author deliberately moved
  README to native Mermaid in commit `03e7fee`; that decision is respected). The hero
  (#1) and OT→IT (#5) may be added inline as Mermaid too. README inline blocks are
  **mirrors** of the corresponding `src/*.mmd`; `docs/diagrams/README.md` names them as
  sync targets so the canonical `.mmd` and the README block do not drift.
- **Blog / slides / case-study / PDF:** use the rendered `svg/*.svg` (Mermaid does not
  render natively there).
- Measurement charts (#6a/#6b) are SVG everywhere (no Mermaid equivalent).

## Validation / Success Criteria

- All seven SVGs render to valid, standalone SVG and are legible at slide scale in a
  browser.
- SVG backgrounds are transparent with the intended card panel; they read correctly on
  both GitHub light and dark mode.
- No external font dependencies (only standard families).
- Measurement values match ADR sources exactly: `328` / `162` / `166` (ADR-0008);
  `8 clean / 1 side-channel / 0 type-identity lost` and the four LossClasses (ADR-0010).
- Re-running `render.ps1` reproduces the committed rendered SVGs (no uncommitted diff
  beyond nondeterministic id/timestamp noise, if any — to be confirmed during
  implementation).
- README inline Mermaid blocks match their canonical `src/*.mmd` counterparts.

## Open Questions / To Confirm During Implementation

- Whether `mermaid-cli` output is byte-stable across runs (Mermaid embeds generated
  ids); if not, the "re-render reproduces committed SVG" criterion relaxes to "visually
  identical," and `render.ps1` is a regeneration aid rather than a CI gate.
- Exact wording of captions on the hero and OT→IT diagrams (to be finalized against the
  ADR vocabulary during authoring).
