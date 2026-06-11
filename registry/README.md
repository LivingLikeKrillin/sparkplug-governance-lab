# UDT Data-Contract Registry

**Source of truth** for UDT (Sparkplug Template) definitions. The `_types_/<Name>` metric in NBIRTH is only wire truth (ADR-0005).

## Layout
- `policy.json` — compatibility mode to enforce (`FORWARD` (default) / `BACKWARD` / `FULL` / `NONE`).
- `udt/<templateRef>/<semver>.json` — versioned data contract per UDT type.

## Workflow (data-contract-as-PR)
1. To change a UDT, create a proposed file at `udt/<ref>/<new-semver>.json`.
2. CI runs the gate:
   `mvn -q exec:java -Dexec.mainClass=dev.krillin.sparkplug.schema.SchemaGate -Dexec.args="registry <proposed.json>"`
3. exit 0 = compatible (merge allowed), exit 1 = breaking (blocked), exit 2 = error.
4. On merge, promote to the registry with `--promote`.

## Policy rationale (FORWARD default)
In a UNS, producers (edge nodes) evolve UDTs while many consumers lag behind. For lagging consumers to tolerate a producer's schema bump, the old schema must be readable by consumers seeing new data (= FORWARD): member **additions are OK**; **removals and type changes are breaking** — a new `templateRef` + major bump is required (e.g. `Motor` → `Motor2`).
