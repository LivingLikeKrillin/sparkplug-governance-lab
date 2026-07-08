# Bifrost Governance Product — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the governance implementation out of `sparkplug-governance-lab` into a new standalone public repo `bifrost` (Maven multi-module: `core` library + `heimdall` daemon + `gates` CLIs), whose only contract with consumers is a language-neutral data/wire format — then reconcile the lab as a code-independent consumer.

**Architecture:** Three kinds of artifact in one repo. `core` = rule model (value types + serde + `DefinitionStore`) + evaluators (compat/authz/provenance) + published format schemas. `heimdall` = the runtime write-boundary daemon (NCMD → authorize → OPC-UA apply → stamp). `gates` = short-lived CI CLIs (schema-compat ①, policy-lint ②, provenance-publish ③). `heimdall` and `gates` share `core`; consumers never depend on Bifrost code — they model the published format themselves. See spec `docs/superpowers/specs/2026-07-08-bifrost-governance-product-design.md`.

**Tech Stack:** Java 17, Maven multi-module, Eclipse Tahu (Sparkplug), Eclipse Milo 1.0.0 (OPC-UA client), Chicory `wasm` 1.4.0 (OPA-WASM in-JVM), Jackson 2.17.2, Logback 1.3.14, JUnit 5. New: `maven-shade-plugin` for runnable jars (the lab runs via `exec:java`; the daemon/CLIs need standalone jars).

**Source of moved code:** `sparkplug-governance-lab` at `C:\Users\Eisen\Desktop\Labs\[iiot]\sparkplug-governance-lab` (packages `dev.krillin.sparkplug.{acl,acl.opa,schema,bridge}`). **New package root:** `dev.krillin.bifrost` → `.core.acl` / `.core.acl.opa` / `.core.schema` (from lab `acl`/`acl.opa`/`schema`), `.heimdall` (from lab `bridge`), `.gates` (the CLI entrypoints).

**Repo policy:** `bifrost` is a NEW git repo (public, Apache-2.0), created locally first; push/PR/publish are Eisen-gated. All commits below are local. Do NOT push without explicit OK.

---

## File / module structure (locked before tasks)

```
bifrost/                              (new repo, group dev.krillin.bifrost, v0.1.0-SNAPSHOT)
├─ pom.xml                            parent (packaging=pom; modules core,heimdall,gates; dep mgmt)
├─ LICENSE                            Apache-2.0
├─ README.md                          one-paragraph product intro
├─ core/
│  ├─ pom.xml                         deps: jackson, chicory wasm+runtime
│  └─ src/main/java/dev/krillin/bifrost/core/
│     ├─ schema/     (15 moved: UdtDefinition,Member,Param,SemVer,Verdict,Violation,CompatMode,
│     │              TemplateAdapter,JsonMapperFactory,DefinitionStore,CompatibilityChecker,
│     │              RecipeDefinitionStore,RecipePublish,RecipeManifest  — SchemaGate moves to gates)
│     ├─ acl/        (12 moved: CommandPolicy,Rule,Target,Constraint,Decision,CommandRequest,
│     │              AclEntry,AclMapperFactory,CommandAuthorizer,BrokerAclProjector  — CommandPolicyGate→gates)
│     └─ acl/opa/    (3 moved: OpaCommandAuthorizer,Context,OpaPolicy)
│  ├─ src/main/resources/opa/         command_authz.rego, command_authz.wasm
│  ├─ src/main/resources/schema/      NEW published format specs (definition.schema.json, policy.schema.json, manifest.md)
│  └─ src/test/java/dev/krillin/bifrost/core/   (moved unit tests, repackaged)
├─ heimdall/
│  ├─ pom.xml                         deps: core, tahu-core/edge/host, milo-sdk-client, logback; shade→bifrost-heimdall.jar
│  └─ src/main/java/dev/krillin/bifrost/heimdall/  (6 moved: NcmdOpcUaBridge,Applier,OpcUaApplier,
│                                     NcmdResponse,NcmdOpcUaBridgeMain,RogueNcmd) + neutral defaults
│  └─ src/test/java/dev/krillin/bifrost/heimdall/  (NcmdOpcUaBridgeTest,NcmdBridgePolicyTest)
├─ gates/
│  ├─ pom.xml                         deps: core; shade→bifrost-gates.jar (multi-main or 3 mains)
│  └─ src/main/java/dev/krillin/bifrost/gates/     (SchemaGate①, PolicyGate② [was CommandPolicyGate],
│                                     ProvenancePublish③ [thin CLI over core RecipePublish])
│  └─ src/test/java/dev/krillin/bifrost/gates/     (SchemaGateTest, CommandPolicyGateTest→PolicyGateTest)
├─ examples/  (optional module OR src/examples: SchemaGateDemo,CommandAclDemo,GuardedEdgeNode,InteropEdge,InteropHost)
└─ scripts/   run-schema-gate.sh, run-command-authz-gate.sh, run-provenance-gate.sh, run-ncmd-runtime-gate.sh
```

Lab after reconciliation keeps its own copies of the `schema/` value types + `DefinitionStore` and loses `acl/`, the moved `schema/` evaluators, `bridge/`, and the 5 moving demos.

---

## Chunk 1: Stand up `bifrost` — core + heimdall, runtime-green

### Task 1: Create the repo skeleton + parent POM

**Files:**
- Create: `bifrost/` (new dir at `C:\Users\Eisen\Desktop\Labs\[iiot]\bifrost`), `bifrost/pom.xml`, `bifrost/LICENSE`, `bifrost/README.md`, `bifrost/.gitignore`

- [ ] **Step 1:** `mkdir bifrost && cd bifrost && git init`. Add `.gitignore` (target/, *.class, .idea/, node_modules/). Add `LICENSE` = Apache-2.0 full text. `README.md` = one paragraph: "Bifrost — a governance product for the IT↔OT boundary: schema-compat, deny-by-default command authz, and version provenance, as CI gates + a runtime write-boundary daemon (Heimdall). Consumers integrate via a published data/wire contract, never shared code."
- [ ] **Step 2:** Write parent `pom.xml`: `<groupId>dev.krillin.bifrost</groupId>`, `<artifactId>bifrost-parent</artifactId>`, `<version>0.1.0-SNAPSHOT</version>`, `<packaging>pom</packaging>`, `<modules>core,heimdall,gates</modules>`. Set `<maven.compiler.release>17</maven.compiler.release>`. Put shared versions in `<properties>` (tahu, milo=1.0.0, jackson=2.17.2, chicory=1.4.0, logback=1.3.14, junit) and a `<dependencyManagement>` block pinning them (copy exact coordinates from the lab `pom.xml`). Add `<pluginManagement>` for `maven-surefire-plugin` and `maven-shade-plugin`.
- [ ] **Step 3:** Run `mvn -q -N validate` at `bifrost/`. Expected: BUILD SUCCESS (parent resolves; no modules yet errors are fine if modules commented until created — otherwise create empty module dirs first).
- [ ] **Step 4: Commit** — `git add -A && git commit -m "chore: bifrost repo skeleton + parent pom (Apache-2.0)"`.

### Task 2: `core` module — move rule model + evaluators, port tests

**Files:**
- Create: `core/pom.xml`
- Move (copy from lab, repackage): lab `src/main/java/dev/krillin/sparkplug/schema/*.java` **except `SchemaGate.java`** → `core/src/main/java/dev/krillin/bifrost/core/schema/`; lab `acl/*.java` **except `CommandPolicyGate.java`** → `core/.../core/acl/`; lab `acl/opa/*.java` → `core/.../core/acl/opa/`; lab `src/main/resources/opa/command_authz.{rego,wasm}` → `core/src/main/resources/opa/`.
- Move tests: lab `src/test/.../acl/*`, `acl/opa/*`, and the schema evaluator tests (`SchemaGateTest` goes to the gates module in Chunk 2; keep compat/recipe tests here) → `core/src/test/java/dev/krillin/bifrost/core/...`.

- [ ] **Step 1:** Write `core/pom.xml` (parent = bifrost-parent; artifactId `bifrost-core`; deps jackson-core/annotations/databind, chicory `wasm` + `runtime`, junit-jupiter test). No tahu/milo.
- [ ] **Step 2:** Copy the class files listed above into the new package dirs. Rewrite package headers + imports: `dev.krillin.sparkplug.schema` → `dev.krillin.bifrost.core.schema`; `dev.krillin.sparkplug.acl` → `dev.krillin.bifrost.core.acl`; `dev.krillin.sparkplug.acl.opa` → `dev.krillin.bifrost.core.acl.opa`. (Mechanical: `find core/src -name '*.java' -exec sed -i 's/dev\.krillin\.sparkplug\.schema/dev.krillin.bifrost.core.schema/g; s/dev\.krillin\.sparkplug\.acl/dev.krillin.bifrost.core.acl/g' {} +`.)
- [ ] **Step 3:** Any OPA resource path references inside `OpaCommandAuthorizer`/`Context` must resolve to `opa/command_authz.wasm` on the classpath (unchanged path). Verify the class loads the resource via classpath, not a filesystem path; adjust if it used a lab-relative path.
- [ ] **Step 4: Compile** — `mvn -q -pl core compile`. Expected: BUILD SUCCESS. Fix any dangling `dev.krillin.sparkplug.*` import (should be none after Step 2; `SchemaGate`/`CommandPolicyGate` are intentionally absent — if a core class references them, that reference belongs in gates, revisit).
- [ ] **Step 5:** Port the moved unit tests (repackage same as Step 2). Run `mvn -q -pl core test`. Expected: the moved tests pass (`CommandAuthorizerTest`, `OpaCommandAuthorizerTest`, compatibility/recipe tests, `ReconciliationProvenance`-style tests if present). Any test that referenced `SchemaGate`/`CommandPolicyGate` moves to gates in Chunk 2 — exclude it here.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat(core): rule model + evaluators (compat, authz, provenance) + OPA resources"`.

### Task 3: `core` — publish the data-contract format specs (NEW)

**Files:**
- Create: `core/src/main/resources/schema/definition.schema.json`, `core/src/main/resources/schema/policy.schema.json`, `core/src/main/resources/schema/provenance-manifest.md`
- Test: `core/src/test/java/dev/krillin/bifrost/core/schema/FormatSpecConformanceTest.java`

- [ ] **Step 1: Write the failing test** — `FormatSpecConformanceTest`: serialize a sample `UdtDefinition` with `JsonMapperFactory`, then assert the produced JSON validates against `definition.schema.json` (load the JSON Schema from classpath; use a minimal hand-rolled check or a JSON-schema lib added to core test scope). Assert `CommandPolicy` serialization validates against `policy.schema.json`.
- [ ] **Step 2: Run** — `mvn -q -pl core test -Dtest=FormatSpecConformanceTest`. Expected: FAIL (schema files absent).
- [ ] **Step 3: Author the schemas** — hand-write `definition.schema.json` (JSON Schema draft-07 describing UdtDefinition: templateRef, version[SemVer string], members[name,type,…], per the actual serialized shape) and `policy.schema.json` (deny-by-default: defaultEffect, rules[target,constraints,effect]). Author `provenance-manifest.md` documenting the ③ manifest layout AND the hashing algorithm ("sha256 over the raw committed blob bytes, hex-encoded; defRef = git commit SHA") so any language can recompute.
- [ ] **Step 4: Run** — same test. Expected: PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(core): published data-contract format specs (definition/policy schema + provenance+hash spec)"`.

### Task 4: `heimdall` module — move the daemon, neutralize koshei defaults

**Files:**
- Create: `heimdall/pom.xml`
- Move: lab `src/main/java/dev/krillin/sparkplug/bridge/*.java` (6 files) → `heimdall/src/main/java/dev/krillin/bifrost/heimdall/`; tests `NcmdOpcUaBridgeTest`, `NcmdBridgePolicyTest` → `heimdall/src/test/...`.
- Modify: `NcmdOpcUaBridgeMain.java` (neutral defaults).

- [ ] **Step 1:** Write `heimdall/pom.xml` (parent; artifactId `bifrost-heimdall`; deps `bifrost-core`, tahu-core/edge/host, milo-sdk-client 1.0.0, logback; junit test; `maven-shade-plugin` execution producing `bifrost-heimdall.jar` with mainClass `dev.krillin.bifrost.heimdall.NcmdOpcUaBridgeMain`).
- [ ] **Step 2:** Copy the 6 bridge classes; repackage `dev.krillin.sparkplug.bridge` → `dev.krillin.bifrost.heimdall`, and fix imports of moved core types (`dev.krillin.sparkplug.acl.*`→`dev.krillin.bifrost.core.acl.*`, `.schema.*`→`.core.schema.*`).
- [ ] **Step 3: Write the failing test** — in `NcmdOpcUaBridgeMain`, change the koshei-named defaults to neutral ones and add/extend a test `NcmdOpcUaBridgeMainDefaultsTest` asserting: default `OPCUA_URL`=`opc.tcp://localhost:48400` (generic), `SPB_GROUP`=`Bifrost:Line1` (was `Koshei:Line1`), `SPB_EDGE`=`recipe-edge`, `POLICY_PATH`=`registry/policy.json` (was `registry/koshei-line1-policy.json`), and that env vars override. Run → FAIL.
- [ ] **Step 4:** Apply the neutral defaults in `NcmdOpcUaBridgeMain`. Run the test → PASS.
- [ ] **Step 5: Build** — `mvn -q -pl core,heimdall install`. Expected: SUCCESS; `bifrost-heimdall.jar` produced under `heimdall/target/`. Run `mvn -q -pl heimdall test` → bridge tests pass.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat(heimdall): runtime write-boundary daemon (bridge) + neutral non-koshei defaults + shade jar"`.

### Task 5: `run-ncmd-runtime-gate.sh` (Chunk-1 acceptance)

**Files:**
- Create: `scripts/run-ncmd-runtime-gate.sh`

- [ ] **Step 1:** Port the koshei `run-r2-ncmd-gate.sh` logic into a bifrost-local gate that: starts a simulator OPC-UA + broker, runs `bifrost-heimdall.jar` with a test policy, publishes an authorized NCMD (asserts confirm-by-read applied value), publishes a rogue/unauthorized NCMD (asserts deny-by-default refusal), prints `[GATE] PASS run-ncmd-runtime-gate.sh` on success. Use `cygpath -m` for all paths handed to the JVM (Windows-native JVM). Use a `$(date +%s)` unique id where a workflow/edge id must be unique.
- [ ] **Step 2: Run FOREGROUND** (the full-stack gate is long; background jobs get reaped) — `timeout 600 bash scripts/run-ncmd-runtime-gate.sh`. Expected: `[GATE] PASS`. If orphan JVMs hold ports, `jps -l | grep -iE 'bifrost|NcmdOpcUaBridge|Sim' | taskkill` before re-run.
- [ ] **Step 3: Commit** — `git add -A && git commit -m "test(gate): run-ncmd-runtime-gate — authorize+apply+refuse against sim OPC-UA [GATE PASS]"`.

> **CHUNK 1 DONE-BIT (controller-direct):** `mvn -q install` at repo root green; `run-ncmd-runtime-gate.sh` `[GATE] PASS`. Heimdall stands alone.

---

## Chunk 2: `gates` module — the CI tooling

### Task 6: `gates` module — SchemaGate ① + PolicyGate ②

**Files:**
- Create: `gates/pom.xml`
- Move: lab `schema/SchemaGate.java` → `gates/.../gates/SchemaGate.java`; lab `acl/CommandPolicyGate.java` → `gates/.../gates/PolicyGate.java` (rename class). Tests `SchemaGateTest`, `CommandPolicyGateTest` → `gates/src/test/...` (rename the latter `PolicyGateTest`).

- [ ] **Step 1:** Write `gates/pom.xml` (parent; artifactId `bifrost-gates`; dep `bifrost-core`; junit; `maven-shade-plugin` → `bifrost-gates.jar`; a small dispatcher main `dev.krillin.bifrost.gates.GatesCli` that routes `schema|policy|provenance` subcommands, OR three mains — choose one and be consistent).
- [ ] **Step 2:** Move `SchemaGate` + `CommandPolicyGate`→`PolicyGate`; repackage to `dev.krillin.bifrost.gates`; fix imports to `dev.krillin.bifrost.core.*`. Ensure each has a `main(String[])` that reads file args, calls the core evaluator, prints a verdict, and exits non-zero on rejection.
- [ ] **Step 3: Port tests** — `SchemaGateTest`, `PolicyGateTest` (repackaged). Add a CLI-level test per gate: exit 0 on a compatible/valid input, non-zero on incompatible/invalid.
- [ ] **Step 4: Run** — `mvn -q -pl gates test`. Expected: PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(gates): SchemaGate ① + PolicyGate ② CLIs over core evaluators"`.

### Task 7: `gates` — ProvenancePublish ③ CLI

**Files:**
- Create: `gates/.../gates/ProvenancePublish.java`, test `gates/.../gates/ProvenancePublishTest.java`

- [ ] **Step 1: Write the failing test** — given a committed definition file, `ProvenancePublish` mints a reference (content-hash over raw bytes + defRef=commit SHA) and materializes the manifest; an independent recompute equals the manifest; a tampered blob is rejected (non-zero). Run → FAIL.
- [ ] **Step 2: Implement** `ProvenancePublish` as a thin CLI over core `RecipePublish`/`RecipeDefinitionStore` (mint + verify paths). Raw-byte sha256 only (matches the published hashing spec + resequence's independent recompute).
- [ ] **Step 3: Run** → PASS.
- [ ] **Step 4:** `run-schema-gate.sh`, `run-command-authz-gate.sh`, `run-provenance-gate.sh` in `scripts/`: each drives `bifrost-gates.jar` with fixture inputs and asserts `[GATE] PASS`. Run each. Expected: `[GATE] PASS`.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat(gates): ProvenancePublish ③ CLI + three gate scripts [GATE PASS]"`.

> **CHUNK 2 DONE-BIT:** `mvn -q install` green; the four gate scripts each `[GATE] PASS`; the ① gate validates a change against the published `definition.schema.json`.

---

## Chunk 3: Reconcile `sparkplug-governance-lab` as a code-independent consumer

> Work in the lab repo on branch `feat/heimdall-governance-engine` (already checked out). Lab keeps its OWN copies of the schema value types + `DefinitionStore`; it loses only governance logic.

### Task 8: Confirm the lab's retained value-type set compiles standalone

**Files:**
- Modify: lab `src/main/java/dev/krillin/sparkplug/...` (remove moved logic)

- [ ] **Step 1:** Delete from the lab: `acl/` (whole package incl. `opa/`), `bridge/` (whole), the moved `schema/` **evaluators** (`CompatibilityChecker`, `RecipeDefinitionStore`, `RecipePublish`, `SchemaGate`) — but **keep** the schema value types + `DefinitionStore` (`UdtDefinition`,`Member`,`Param`,`SemVer`,`Verdict`,`Violation`,`CompatMode`,`TemplateAdapter`,`JsonMapperFactory`,`DefinitionStore`,`RecipeManifest`). Delete the 5 moving demos (`SchemaGateDemo`,`CommandAclDemo`,`GuardedEdgeNode`,`InteropEdge`,`InteropHost`) and their tests. Delete `opa/` resources.
- [ ] **Step 2: Compile** — `mvn -q -f "<lab>/pom.xml" compile`. Expected: SUCCESS. If a staying class imports a deleted evaluator, that is a mis-scoped file — re-check against the spec §5 import analysis (staying code should only need value types + `DefinitionStore`).
- [ ] **Step 3: Test** — `mvn -q -f "<lab>/pom.xml" test`. Expected: the lab's remaining tests (`spb40`,`kafka`,`drift`,`opcua`) pass. Remove tests of deleted classes.
- [ ] **Step 4: Commit (lab repo)** — `git add -A && git commit -m "refactor: governance logic extracted to bifrost; lab keeps its own value types as a consumer"`.

### Task 9: Re-point the cross-repo NCMD gate to the Heimdall daemon

**Files:**
- Modify: the koshei `run-r2-ncmd-gate.sh` (or its bifrost equivalent) to launch `bifrost-heimdall.jar` instead of the lab's `exec:java` bridge.

- [ ] **Step 1:** Update the gate so `LAB_DIR`/bridge invocation targets the built `bifrost-heimdall.jar`; policy/registry files are passed as data (the data contract). Keep the assertions (authorized apply + rogue refusal).
- [ ] **Step 2: Run FOREGROUND** — `timeout 600 bash <gate>`. Expected: `[GATE] PASS`.
- [ ] **Step 3: Commit** — `git commit -m "test(gate): NCMD gate drives bifrost-heimdall.jar (process contract) [GATE PASS]"`.

### Task 10: Confirm `resequence-twin-lab` ③ verify against a Bifrost manifest

**Files:**
- Modify (resequence): the source of the ③ published manifest → a Bifrost `ProvenancePublish` output.

- [ ] **Step 1:** Publish a reference with `bifrost-gates.jar provenance ...`; point the resequence version-reference verification at that manifest (bytes only — no code dependency).
- [ ] **Step 2: Run** the resequence version-reference gate. Expected: `provenanceVerified` + twin-recomputed contentSha256 == manifest, `[GATE] PASS`.
- [ ] **Step 3: Commit (resequence repo)** — `git commit -m "test: verify ③ reference published by bifrost (data-contract, no code dep) [GATE PASS]"`.

> **CHUNK 3 DONE-BIT / FINAL ACCEPTANCE (controller-direct):** bifrost `mvn install` green + 4 gate scripts `[GATE] PASS`; lab builds + its demos run with no bifrost code dependency; resequence ③ verify passes against a bifrost-published manifest. Push/PR/publish for all three repos are Eisen-gated (do not push).

---

## Cross-cutting notes for the executor

- **Windows/MSYS:** every path handed to a native JVM (`-D`, `exec.args`, `java -jar`) must be `cygpath -m`'d. `git -C` needs the `cygpath -m` Windows path, not `/c/...`.
- **Full-stack gates run FOREGROUND** with `timeout 600` (background jobs get reaped ~5min). Kill orphan JVMs holding ports before re-running.
- **Raw-byte hashing everywhere** for ③ (readBytes / git-show blob / sha256sum) — never charset-decoded text — so publisher/consumer agree cross-platform.
- **No push/PR/merge** anywhere without Eisen's explicit OK; all commits local. bifrost is a brand-new repo (not yet on GitHub).
- **@Skills:** use superpowers:test-driven-development for each task; superpowers:verification-before-completion before claiming a done-bit (run the gate yourself, don't trust a subagent's PASS claim).
