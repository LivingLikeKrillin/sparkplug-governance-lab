# Portfolio Visual Assets Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce seven version-controlled diagram assets (5 Mermaid-rendered + 2 hand-authored SVG charts) in "Light / GitHub-native" style that prove governance design, protocol depth, honest engineering, and OT→IT integration across README/blog/slides/PDF.

**Architecture:** `.mmd` sources under `docs/diagrams/src/` are canonical for diagrams #1–#5 and render to `docs/diagrams/svg/` via `mermaid-cli`; two measurement charts are hand-authored SVG committed directly to `svg/`. A centralized `mermaid-theme.json` carries Style A tokens. README keeps native inline Mermaid (mirrors of the `.mmd`); rendered SVGs serve the other surfaces.

**Tech Stack:** Mermaid CLI (`@mermaid-js/mermaid-cli` via `npx`, host Node 22), hand-authored SVG, PowerShell + Bash render scripts. Docker (`minlag/mermaid-cli`) documented as fallback.

**Spec:** `docs/superpowers/specs/2026-06-17-portfolio-visuals-design.md`

**Validation model (no unit-test framework):** Diagrams have no traditional unit tests. The "failing test → make it pass" cycle maps to: (a) a validation check that the target SVG does not yet exist / does not yet contain required tokens, (b) authoring/rendering, (c) re-running the check until it passes (well-formed XML + required label tokens present), (d) a human/visual confirmation, (e) commit. Well-formedness is checked with the always-available PowerShell `[xml]` cast; token presence with `Select-String` (or `grep`). Note: per-step `Select-String -Pattern a,b,c` is OR (it returns matched lines and does not fail on a missing token), so those steps require the operator to read that N distinct lines came back. The final number-fidelity audit (Task 11, Step 3) uses a strict AND assertion that throws on any missing token, so the build gate does not depend on eyeballing.

**Background-color refinement vs spec:** The spec specifies an opaque rounded card panel for all assets. Mermaid CLI cannot draw a rounded card behind an auto-laid-out graph, so Mermaid outputs (#1–#5) use an **opaque full-bleed white canvas** (`-b "#ffffff"`) — still dark-safe (reads as a white card on GitHub dark mode). The two hand-authored charts (#6a/#6b) implement the **rounded** opaque card panel exactly. This is an intentional, documented refinement.

---

## Chunk 1: Scaffolding, theme, and render scripts

### Task 1: Create directory structure and Style A theme

**Files:**
- Create: `docs/diagrams/mermaid-theme.json`
- Create: `docs/diagrams/src/.gitkeep`
- Create: `docs/diagrams/svg/.gitkeep`
- Modify: `.gitignore`

- [ ] **Step 1: Validation — confirm the theme file does not exist yet**

Run: `Test-Path docs/diagrams/mermaid-theme.json`
Expected: `False`

- [ ] **Step 2: Create the directories and theme file**

Create `docs/diagrams/src/.gitkeep` (empty) and `docs/diagrams/svg/.gitkeep` (empty).

Create `docs/diagrams/mermaid-theme.json` with Style A tokens:

```json
{
  "theme": "base",
  "themeVariables": {
    "fontFamily": "-apple-system, Segoe UI, Helvetica, Arial, sans-serif",
    "fontSize": "14px",
    "primaryColor": "#f6f8fa",
    "primaryBorderColor": "#d0d7de",
    "primaryTextColor": "#1f2328",
    "lineColor": "#57606a",
    "secondaryColor": "#ddf4ff",
    "secondaryBorderColor": "#0969da",
    "tertiaryColor": "#ffffff",
    "tertiaryBorderColor": "#d0d7de",
    "clusterBkg": "#ffffff",
    "clusterBorder": "#d0d7de",
    "edgeLabelBackground": "#ffffff",
    "actorBkg": "#f6f8fa",
    "actorBorder": "#d0d7de",
    "actorTextColor": "#1f2328",
    "signalColor": "#57606a",
    "signalTextColor": "#1f2328",
    "noteBkgColor": "#ddf4ff",
    "noteBorderColor": "#0969da"
  }
}
```

- [ ] **Step 3: Add the Puppeteer cache guard to `.gitignore`**

Append to `.gitignore`:

```
# mermaid-cli / puppeteer chromium cache must never be committed
.cache/
.puppeteer/
docs/diagrams/.mermaid-cli/
```

- [ ] **Step 4: Validation — theme parses as JSON**

Run: `Get-Content docs/diagrams/mermaid-theme.json -Raw | ConvertFrom-Json | Out-Null; "ok"`
Expected: prints `ok` with no error.

- [ ] **Step 5: Commit**

```bash
git add docs/diagrams/mermaid-theme.json docs/diagrams/src/.gitkeep docs/diagrams/svg/.gitkeep .gitignore
git commit -m "chore(diagrams): scaffold dirs, Style A theme, gitignore guard"
```

### Task 2: Create render scripts

**Files:**
- Create: `docs/diagrams/render.ps1`
- Create: `docs/diagrams/render.sh`

- [ ] **Step 1: Validation — confirm scripts do not exist**

Run: `Test-Path docs/diagrams/render.ps1`
Expected: `False`

- [ ] **Step 2: Create `docs/diagrams/render.ps1`**

```powershell
# Renders every docs/diagrams/src/*.mmd to docs/diagrams/svg/*.svg in Style A.
# Hand-authored SVGs (nbirth-size.svg, loss-ledger.svg) are NOT touched.
# First run downloads a Puppeteer Chromium (cached by npx).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src  = Join-Path $here "src"
$svg  = Join-Path $here "svg"
$theme = Join-Path $here "mermaid-theme.json"
Get-ChildItem -Path $src -Filter *.mmd | ForEach-Object {
  $out = Join-Path $svg ($_.BaseName + ".svg")
  Write-Host "rendering $($_.Name) -> svg/$($_.BaseName).svg"
  npx -y "@mermaid-js/mermaid-cli" -i $_.FullName -o $out -c $theme -b "#ffffff"
}
Write-Host "done. (hand-authored charts in svg/ are left untouched)"
```

- [ ] **Step 3: Create `docs/diagrams/render.sh`**

```bash
#!/usr/bin/env bash
# Renders every docs/diagrams/src/*.mmd to docs/diagrams/svg/*.svg in Style A.
# Hand-authored SVGs (nbirth-size.svg, loss-ledger.svg) are NOT touched.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for f in "$here"/src/*.mmd; do
  base="$(basename "$f" .mmd)"
  echo "rendering $base.mmd -> svg/$base.svg"
  npx -y @mermaid-js/mermaid-cli -i "$f" -o "$here/svg/$base.svg" -c "$here/mermaid-theme.json" -b "#ffffff"
done
echo "done. (hand-authored charts in svg/ are left untouched)"
# Docker fallback (no host Node):
#   docker run --rm -v "$here:/data" minlag/mermaid-cli \
#     -i /data/src/<name>.mmd -o /data/svg/<name>.svg -c /data/mermaid-theme.json -b "#ffffff"
```

- [ ] **Step 4: Validation — scripts are syntactically valid**

Run: `bash -n docs/diagrams/render.sh; "bash ok"`
Expected: prints `bash ok` (no syntax error). PowerShell parse: `powershell -NoProfile -Command "[System.Management.Automation.PSParser]::Tokenize((Get-Content docs/diagrams/render.ps1 -Raw),[ref]$null) | Out-Null; 'ps ok'"` → `ps ok`.

- [ ] **Step 5: Commit**

```bash
git add docs/diagrams/render.ps1 docs/diagrams/render.sh
git commit -m "chore(diagrams): add npx mermaid-cli render scripts (+docker fallback note)"
```

---

## Chunk 2: Mermaid diagrams (#1–#5)

> For each task: create the `.mmd`, run `docs/diagrams/render.ps1` (or `render.sh`), confirm the SVG renders and contains required label tokens, visually confirm, commit. If the first `npx` invocation stalls on the Chromium download, that is expected once.

### Task 3: Hero — governance lifecycle (#1)

**Files:**
- Create: `docs/diagrams/src/governance-lifecycle.mmd`
- Produces: `docs/diagrams/svg/governance-lifecycle.svg`

- [ ] **Step 1: Validation — target SVG absent**

Run: `Test-Path docs/diagrams/svg/governance-lifecycle.svg`
Expected: `False`

- [ ] **Step 2: Author the source**

`docs/diagrams/src/governance-lifecycle.mmd`:

```
---
title: "Governance lifecycle — the gate opens the loop, drift closes it"
---
flowchart LR
    subgraph GOV["Governance (policy-as-code)"]
        GATE["SchemaGate<br/>pre-deploy, fail-closed"]
        REG[("UDT registry<br/>SemVer, source of truth")]
    end
    EDGE["Edge Node / UNS<br/>NBIRTH / NDATA"]
    DRIFT["DriftMonitor<br/>runtime, detect-only"]
    GATE -->|"admit / reject<br/>breaking change"| REG
    REG -->|"definitions"| EDGE
    EDGE -->|"observed NBIRTH"| DRIFT
    REG -->|"source of truth"| DRIFT
    DRIFT -.->|"drift signal<br/>closes the loop"| GATE
```

- [ ] **Step 3: Render**

Run: `powershell -NoProfile -File docs/diagrams/render.ps1` (or `bash docs/diagrams/render.sh`)
Expected: writes `svg/governance-lifecycle.svg`, exit 0.

- [ ] **Step 4: Validation — well-formed + required tokens**

Run:
```
[xml](Get-Content docs/diagrams/svg/governance-lifecycle.svg -Raw) | Out-Null; "xml ok"
Select-String -Path docs/diagrams/svg/governance-lifecycle.svg -Pattern "SchemaGate","DriftMonitor","source of truth" -SimpleMatch
```
Expected: `xml ok` and all three tokens found.

- [ ] **Step 5: Visual confirmation**

Open `svg/governance-lifecycle.svg` in a browser. Confirm: four nodes, the dashed return edge from DriftMonitor to SchemaGate is visible (the closed loop), Style A colors, legible at slide scale.

- [ ] **Step 6: Commit**

```bash
git add docs/diagrams/src/governance-lifecycle.mmd docs/diagrams/svg/governance-lifecycle.svg
git commit -m "feat(diagrams): hero governance-lifecycle diagram"
```

### Task 4: System architecture (#2)

**Files:**
- Create: `docs/diagrams/src/system-architecture.mmd`
- Produces: `docs/diagrams/svg/system-architecture.svg`

- [ ] **Step 1: Validation — target absent**

Run: `Test-Path docs/diagrams/svg/system-architecture.svg` → `False`

- [ ] **Step 2: Author the source** (copied verbatim from `README.md` architecture block so README and `.mmd` stay identical):

```
flowchart TB
    subgraph GOV["Governance (policy-as-code)"]
        GATE["SchemaGate (CI, fail-closed)"]
        REG[("UDT schema registry (SemVer)")]
        POL[("command-policy.json (deny-by-default)")]
    end
    subgraph OT["OT / Edge"]
        SIM["OPC UA server (sim)"]
        MAP["opcua: ObjectType to UDT + loss ledger"]
        AUTH["acl: CommandAuthorizer (fail-closed)"]
        EDGE["Sparkplug Edge Node (Tahu)"]
    end
    subgraph MQ["HiveMQ CE"]
        TOPICS["spBv1.0/# (BIRTH / DATA / CMD)"]
        RET["retained: DEFINITION / STATE"]
    end
    subgraph IT["IT / Consumers"]
        HOST["Host Application"]
        DRIFT["drift: DriftMonitor (detect-only)"]
        KB["kafka: stateful UNS bridge"]
        KAFKA[("Kafka (compacted topics)")]
    end
    SIM -->|browse / read| MAP
    MAP -->|UdtDefinition| EDGE
    GATE -->|admit / reject| REG
    REG -->|definitions| EDGE
    POL -->|project| AUTH
    AUTH -->|ALLOW only| EDGE
    EDGE -->|thin NBIRTH / NDATA| TOPICS
    EDGE -->|DEFINITION, once| RET
    TOPICS --> HOST
    RET -->|learn schema| HOST
    TOPICS -->|passive observe| DRIFT
    REG -->|source of truth| DRIFT
    TOPICS --> KB
    KB -->|"RBE = log compaction"| KAFKA
    HOST -->|NCMD| TOPICS
```

- [ ] **Step 3: Render** — `powershell -NoProfile -File docs/diagrams/render.ps1`. Expected: `svg/system-architecture.svg` written.

- [ ] **Step 4: Validation**

```
[xml](Get-Content docs/diagrams/svg/system-architecture.svg -Raw) | Out-Null; "xml ok"
Select-String -Path docs/diagrams/svg/system-architecture.svg -Pattern "HiveMQ CE","DriftMonitor","Kafka" -SimpleMatch
```
Expected: `xml ok` + tokens found.

- [ ] **Step 5: Parity check — `.mmd` matches README block**

Confirm the body of `src/system-architecture.mmd` is identical to the fenced ```mermaid block in `README.md` (lines ~16–54). They must stay in sync.

- [ ] **Step 6: Visual confirmation** — four subgraphs (GOV/OT/MQ/IT), edges labeled, Style A.

- [ ] **Step 7: Commit**

```bash
git add docs/diagrams/src/system-architecture.mmd docs/diagrams/svg/system-architecture.svg
git commit -m "feat(diagrams): system-architecture svg from README source"
```

### Task 5: Sequence — schema↔data separation (#3)

**Files:**
- Create: `docs/diagrams/src/seq-schema-data-separation.mmd`
- Produces: `docs/diagrams/svg/seq-schema-data-separation.svg`

- [ ] **Step 1: Validation — target absent** → `Test-Path ... ` = `False`

- [ ] **Step 2: Author the source** (verbatim from ADR-0008 so they stay in sync):

```
sequenceDiagram
    participant R as Schema Registry (authority)
    participant E as Edge Node
    participant B as HiveMQ (retained)
    participant C as Consumer
    R->>E: build Definition (Motor@1.1.0)
    E->>B: DEFINITION - retained, published once
    E->>B: thin NBIRTH - schemaRef + alias-only (162 B vs 328 B inline)
    E->>B: NDATA - alias-only (RBE)
    B->>C: retained DEFINITION, learn() = NEW
    B->>C: duplicate DEFINITION, learn() = UNCHANGED (idempotent)
    B->>C: thin NBIRTH/NDATA, resolved via schemaRef
```

- [ ] **Step 3: Render** — `svg/seq-schema-data-separation.svg` written.

- [ ] **Step 4: Validation**

```
[xml](Get-Content docs/diagrams/svg/seq-schema-data-separation.svg -Raw) | Out-Null; "xml ok"
Select-String -Path docs/diagrams/svg/seq-schema-data-separation.svg -Pattern "162 B vs 328 B","idempotent","schemaRef" -SimpleMatch
```
Expected: `xml ok` + tokens found. (The `162 B vs 328 B` token must be present — it is the honest-engineering number.)

- [ ] **Step 5: Parity check** — body identical to the ADR-0008 ```mermaid block.

- [ ] **Step 6: Visual confirmation** — 4 participants, retained DEFINITION published once, thin NBIRTH labeled with byte sizes.

- [ ] **Step 7: Commit**

```bash
git add docs/diagrams/src/seq-schema-data-separation.mmd docs/diagrams/svg/seq-schema-data-separation.svg
git commit -m "feat(diagrams): schema-data separation sequence svg"
```

### Task 6: NCMD authorization (#4)

**Files:**
- Create: `docs/diagrams/src/seq-ncmd-authorization.mmd`
- Produces: `docs/diagrams/svg/seq-ncmd-authorization.svg`

- [ ] **Step 1: Validation — target absent** → `False`

- [ ] **Step 2: Author the source** (verbatim from ADR-0011):

```
flowchart TB
    PUB["Command publisher (any MQTT client)"]
    subgraph BRK["MQTT broker - sees the TOPIC only"]
        ACL["Topic ACL (node-level, all-or-nothing)"]
    end
    subgraph EDGE["Edge node - sees the PAYLOAD"]
        AUTH["CommandAuthorizer (allowlist + value range + type, deny-by-default)"]
        EXEC["execute (ALLOW only)"]
    end
    subgraph POL["Single policy source"]
        GATE["CI lint gate (fail-closed)"]
        P[("command-policy.json (deny-by-default)")]
        PROJ["BrokerAclProjector"]
    end
    PUB -->|"PUBLISH NCMD - topic carries no command name"| ACL
    ACL -->|"payload passes through - broker cannot inspect metrics"| AUTH
    AUTH -->|ALLOW| EXEC
    GATE -->|lint| P
    P --> PROJ
    PROJ -->|"projected ACL: identity to node"| ACL
    P -->|"loaded at edge: per-command / per-value"| AUTH
```

- [ ] **Step 3: Render** — `svg/seq-ncmd-authorization.svg` written.

- [ ] **Step 4: Validation**

```
[xml](Get-Content docs/diagrams/svg/seq-ncmd-authorization.svg -Raw) | Out-Null; "xml ok"
Select-String -Path docs/diagrams/svg/seq-ncmd-authorization.svg -Pattern "CommandAuthorizer","deny-by-default","topic carries no command name" -SimpleMatch
```
Expected: `xml ok` + tokens found.

- [ ] **Step 5: Parity check** — body identical to ADR-0011 ```mermaid block.

- [ ] **Step 6: Visual confirmation** — broker sees topic only; edge sees payload; single policy source projects both ways.

- [ ] **Step 7: Commit**

```bash
git add docs/diagrams/src/seq-ncmd-authorization.mmd docs/diagrams/svg/seq-ncmd-authorization.svg
git commit -m "feat(diagrams): ncmd authorization svg"
```

### Task 7: OT→IT data flow (#5)

**Files:**
- Create: `docs/diagrams/src/ot-it-dataflow.mmd`
- Produces: `docs/diagrams/svg/ot-it-dataflow.svg`

- [ ] **Step 1: Validation — target absent** → `False`

- [ ] **Step 2: Author the source**

```
---
title: "OT to IT — OPC UA information model to Kafka, with loss ledger + side-channel"
---
flowchart LR
    SIM["OPC UA server<br/>information model"]
    BROWSE["Milo browse"]
    MAP["OpcUaTypeMapper<br/>+ LossLedger"]
    DEF["retained DEFINITION<br/>+ thin NDATA"]
    EDGE["Sparkplug Edge"]
    MQTT["HiveMQ (MQTT)"]
    KAFKA[("Kafka<br/>log compaction")]
    SC["side-channel<br/>ua_ticks, ua_statuscode"]
    SIM --> BROWSE --> MAP --> DEF --> EDGE --> MQTT --> KAFKA
    MAP -.->|"verbatim originals"| SC
    SC -.-> DEF
```

- [ ] **Step 3: Render** — `svg/ot-it-dataflow.svg` written.

- [ ] **Step 4: Validation**

```
[xml](Get-Content docs/diagrams/svg/ot-it-dataflow.svg -Raw) | Out-Null; "xml ok"
Select-String -Path docs/diagrams/svg/ot-it-dataflow.svg -Pattern "OpcUaTypeMapper","LossLedger","log compaction","ua_statuscode" -SimpleMatch
```
Expected: `xml ok` + tokens found.

- [ ] **Step 5: Visual confirmation** — linear OT→IT flow with the side-channel branch preserving originals; terminates at Kafka log compaction.

- [ ] **Step 6: Commit**

```bash
git add docs/diagrams/src/ot-it-dataflow.mmd docs/diagrams/svg/ot-it-dataflow.svg
git commit -m "feat(diagrams): ot-to-it dataflow svg"
```

---

## Chunk 3: Hand-authored measurement charts (#6a, #6b)

> These are committed SVG sources (not render targets). They implement the rounded opaque card panel exactly. All numbers must match ADR sources: 328/162/166 (ADR-0008); 9 members = 8 clean / 1 side-channel / 0 type-identity lost, four LossClasses (ADR-0010).

### Task 8: NBIRTH size chart (#6a)

**Files:**
- Create: `docs/diagrams/svg/nbirth-size.svg`

- [ ] **Step 1: Validation — target absent** → `Test-Path docs/diagrams/svg/nbirth-size.svg` = `False`

- [ ] **Step 2: Author the SVG**

`docs/diagrams/svg/nbirth-size.svg`:

```svg
<svg viewBox="0 0 520 320" width="520" height="320" xmlns="http://www.w3.org/2000/svg" font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif">
  <rect x="8" y="8" width="504" height="304" rx="12" fill="#ffffff" stroke="#d0d7de"/>
  <text x="32" y="44" font-size="16" font-weight="600" fill="#1f2328">NBIRTH payload size — schema/data separation</text>
  <text x="32" y="64" font-size="12" fill="#57606a">measured per birth (ADR-0008)</text>
  <!-- baseline -->
  <line x1="120" y1="270" x2="470" y2="270" stroke="#d0d7de"/>
  <!-- inline bar: 328 B -->
  <rect x="160" y="120" width="90" height="150" rx="3" fill="#f6f8fa" stroke="#d0d7de"/>
  <text x="205" y="110" font-size="14" font-weight="600" text-anchor="middle" fill="#1f2328">328 B</text>
  <text x="205" y="290" font-size="12" text-anchor="middle" fill="#57606a">inline (full UDT)</text>
  <!-- thin bar: 162 B -->
  <rect x="330" y="196" width="90" height="74" rx="3" fill="#ddf4ff" stroke="#0969da"/>
  <text x="375" y="186" font-size="14" font-weight="600" text-anchor="middle" fill="#0969da">162 B</text>
  <text x="375" y="290" font-size="12" text-anchor="middle" fill="#57606a">thin (schemaRef)</text>
  <!-- saving bracket -->
  <line x1="250" y1="120" x2="420" y2="120" stroke="#1a7f37" stroke-dasharray="4 3"/>
  <line x1="420" y1="120" x2="420" y2="196" stroke="#1a7f37" stroke-dasharray="4 3"/>
  <text x="436" y="162" font-size="13" font-weight="600" fill="#1a7f37">-166 B</text>
  <text x="436" y="180" font-size="11" fill="#1a7f37">~50% / birth</text>
</svg>
```

- [ ] **Step 3: Validation — well-formed + numbers present**

```
[xml](Get-Content docs/diagrams/svg/nbirth-size.svg -Raw) | Out-Null; "xml ok"
Select-String -Path docs/diagrams/svg/nbirth-size.svg -Pattern "328 B","162 B","-166 B" -SimpleMatch
```
Expected: `xml ok` + all three present. (Cross-check arithmetic: 328 − 162 = 166; 166/328 ≈ 50%.)

- [ ] **Step 4: Visual confirmation** — open in browser: two bars to scale (inline ~2× thin), green saving bracket, rounded white card reads on light and dark backgrounds (preview by setting the browser/page to dark).

- [ ] **Step 5: Commit**

```bash
git add docs/diagrams/svg/nbirth-size.svg
git commit -m "feat(diagrams): hand-authored NBIRTH size chart (328 vs 162 B)"
```

### Task 9: Loss ledger chart (#6b)

**Files:**
- Create: `docs/diagrams/svg/loss-ledger.svg`

- [ ] **Step 1: Validation — target absent** → `False`

- [ ] **Step 2: Author the SVG**

`docs/diagrams/svg/loss-ledger.svg`:

```svg
<svg viewBox="0 0 560 320" width="560" height="320" xmlns="http://www.w3.org/2000/svg" font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif">
  <rect x="8" y="8" width="544" height="304" rx="12" fill="#ffffff" stroke="#d0d7de"/>
  <text x="32" y="44" font-size="16" font-weight="600" fill="#1f2328">OPC UA to UDT — loss ledger (example: 9 members)</text>
  <text x="32" y="64" font-size="12" fill="#57606a">honesty as a first-class output (ADR-0010)</text>
  <!-- segmented bar: 9 cells, 8 clean + 1 side-channel + 0 type-identity-lost -->
  <g>
    <rect x="32" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="72" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="112" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="152" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="192" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="232" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="272" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="312" y="92" width="40" height="40" fill="#1a7f37" stroke="#ffffff"/>
    <rect x="352" y="92" width="40" height="40" fill="#9a6700" stroke="#ffffff"/>
  </g>
  <text x="32" y="156" font-size="13" font-weight="600" fill="#1f2328">8 clean / 1 side-channel preserved / 0 type-identity lost</text>
  <!-- legend: 4 LossClasses -->
  <g font-size="12" fill="#1f2328">
    <rect x="32" y="186" width="14" height="14" fill="#1a7f37"/><text x="54" y="198">CLEAN — 8</text>
    <rect x="32" y="212" width="14" height="14" fill="#0969da"/><text x="54" y="224">PRECISION_LOSS — 0</text>
    <rect x="32" y="238" width="14" height="14" fill="#cf222e"/><text x="54" y="250">TYPE_IDENTITY_LOSS — 0</text>
    <rect x="32" y="264" width="14" height="14" fill="#9a6700"/><text x="54" y="276">SIDE_CHANNEL_REQUIRED — 1</text>
  </g>
  <text x="300" y="224" font-size="11" fill="#57606a">lossless truth kept verbatim in</text>
  <text x="300" y="240" font-size="11" font-family="ui-monospace,Consolas,monospace" fill="#57606a">ua_ticks / ua_statuscode</text>
</svg>
```

- [ ] **Step 3: Validation — well-formed + required facts present**

```
[xml](Get-Content docs/diagrams/svg/loss-ledger.svg -Raw) | Out-Null; "xml ok"
Select-String -Path docs/diagrams/svg/loss-ledger.svg -Pattern "8 clean","0 type-identity lost","SIDE_CHANNEL_REQUIRED","TYPE_IDENTITY_LOSS","ua_statuscode" -SimpleMatch
```
Expected: `xml ok` + all four tokens present. (Cross-check: 8 + 1 + 0 = 9 members; all four LossClasses named.)

- [ ] **Step 4: Visual confirmation** — 9-cell bar (8 green, 1 amber), legend lists all four LossClasses with counts, side-channel note in monospace.

- [ ] **Step 5: Commit**

```bash
git add docs/diagrams/svg/loss-ledger.svg
git commit -m "feat(diagrams): hand-authored loss ledger chart"
```

---

## Chunk 4: Integration — README embeds and diagrams README

### Task 10: Add hero + OT→IT inline Mermaid to README; declare parity

**Files:**
- Modify: `README.md`
- Create: `docs/diagrams/README.md`

- [ ] **Step 1: Add the hero diagram inline near the top of `README.md`**

Immediately under the project intro paragraph (before the existing `## Architecture`), insert a new section. Paste the **body** of `src/governance-lifecycle.mmd` (without the `--- title ---` front-matter — use a Markdown heading instead) inside a ```mermaid fence:

```markdown
## The one-line idea

The pre-deploy gate opens a governance loop that runtime drift detection closes.

\`\`\`mermaid
flowchart LR
    subgraph GOV["Governance (policy-as-code)"]
        GATE["SchemaGate<br/>pre-deploy, fail-closed"]
        REG[("UDT registry<br/>SemVer, source of truth")]
    end
    EDGE["Edge Node / UNS<br/>NBIRTH / NDATA"]
    DRIFT["DriftMonitor<br/>runtime, detect-only"]
    GATE -->|"admit / reject<br/>breaking change"| REG
    REG -->|"definitions"| EDGE
    EDGE -->|"observed NBIRTH"| DRIFT
    REG -->|"source of truth"| DRIFT
    DRIFT -.->|"drift signal<br/>closes the loop"| GATE
\`\`\`
```

(Replace `\`\`\`` with real triple backticks when editing.)

- [ ] **Step 2: Add the OT→IT inline Mermaid to README**

After the `## Modules` table (or wherever OT→IT is discussed), add a ```mermaid fence containing the body of `src/ot-it-dataflow.mmd` (drop the front-matter; use a `### OT→IT data flow` heading above it).

- [ ] **Step 3: Create `docs/diagrams/README.md`**

```markdown
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

`src/*.mmd` is canonical. The following README / ADR inline ```mermaid blocks are
**mirrors** and MUST be kept identical to their `.mmd` source when either changes:

- `src/governance-lifecycle.mmd` ↔ README "The one-line idea"
- `src/system-architecture.mmd` ↔ README "Architecture"
- `src/ot-it-dataflow.mmd` ↔ README "OT→IT data flow"
- `src/seq-schema-data-separation.mmd` ↔ ADR-0008
- `src/seq-ncmd-authorization.mmd` ↔ ADR-0011
```

- [ ] **Step 4: Validation — README renders and mirrors match**

Run:
```
Select-String -Path README.md -Pattern "The one-line idea","closes the loop" -SimpleMatch
```
Expected: both found. Manually diff each README/ADR ```mermaid block against its `src/*.mmd` body — they must be character-identical (modulo the `.mmd` title front-matter).

- [ ] **Step 5: Visual confirmation** — view README on GitHub (or a Markdown preview) and confirm the new Mermaid blocks render and read in both light and dark mode.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/diagrams/README.md
git commit -m "docs: embed hero + ot-it diagrams in README; add diagrams README + parity map"
```

### Task 11: Final validation pass

**Files:** none (verification only)

- [ ] **Step 1: All seven SVGs exist and are well-formed**

```
$f = "governance-lifecycle","system-architecture","seq-schema-data-separation","seq-ncmd-authorization","ot-it-dataflow","nbirth-size","loss-ledger"
$f | ForEach-Object { [xml](Get-Content "docs/diagrams/svg/$_.svg" -Raw) | Out-Null; "$_ ok" }
```
Expected: seven `... ok` lines, no error.

- [ ] **Step 2: Re-render is reproducible (Mermaid set only)**

Run `powershell -NoProfile -File docs/diagrams/render.ps1`, then `git status --short docs/diagrams/svg`.
Expected: no diff on the five rendered SVGs. **If mermaid-cli embeds nondeterministic ids and a diff appears**, relax this criterion to "visually identical" (per spec open question), note it in `docs/diagrams/README.md`, and `git checkout -- docs/diagrams/svg` to drop the noise.

- [ ] **Step 3: Number-fidelity audit**

Use a strict AND assertion (unlike per-step `Select-String`, this throws if ANY token is missing):

```powershell
function Assert-Tokens($path, [string[]]$tokens) {
  $text = Get-Content $path -Raw
  $missing = @($tokens | Where-Object { $text -notlike "*$_*" })
  if ($missing.Count) { throw "MISSING in ${path}: $($missing -join ', ')" }
  "OK ${path}: all $($tokens.Count) tokens present"
}
Assert-Tokens "docs/diagrams/svg/nbirth-size.svg" @("328 B","162 B","-166 B")
Assert-Tokens "docs/diagrams/svg/loss-ledger.svg" @("8 clean","0 type-identity lost","SIDE_CHANNEL_REQUIRED","TYPE_IDENTITY_LOSS")
Assert-Tokens "docs/diagrams/svg/seq-schema-data-separation.svg" @("162 B vs 328 B")
```
Expected: three `OK ...` lines and no exception — numbers match ADR-0008/0010. A thrown `MISSING ...` fails the audit.

- [ ] **Step 4: Finish the branch**

Use superpowers:finishing-a-development-branch to decide merge/PR/cleanup. Do not merge without the user's choice.
```

---

## Post-merge updates (2026-06-18)

Executed via subagent-driven development and merged. Two follow-ups changed things
described above (see the design spec's "Post-merge updates" for detail):

- **Task 4 (`system-architecture`) was superseded** by a hand-authored bespoke SVG
  (PR #2). `src/system-architecture.mmd` was deleted, so it is no longer a render
  target; the README embeds `svg/system-architecture.svg` as an image. The Task-4 parity
  step (`.mmd` ↔ README Mermaid block) no longer applies to this diagram.
- **Mermaid-mirror parity is now enforced** by `docs/diagrams/check-parity.py`
  (exit 1 on drift) rather than left as a manual discipline.
