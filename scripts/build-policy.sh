#!/usr/bin/env bash
# Regenerates src/main/resources/opa/command_authz.wasm from command_authz.rego.
# Pinned toolchain: opa 0.70.0 (wasm output is version-specific).
set -euo pipefail
cd "$(dirname "$0")/.."
OPA_PIN="0.70.0"
opa version | grep -q "Version: ${OPA_PIN}" || { echo "ERROR: opa ${OPA_PIN} required (got: $(opa version | head -1))"; exit 1; }
build_wasm() {  # $1=rego $2=entrypoint $3=out.wasm
  opa build -t wasm -e "$2" "$1" -o build/policy-bundle.tar.gz
  tar -xzf build/policy-bundle.tar.gz -C build   # → build/policy.wasm
  cp build/policy.wasm "$3"
  echo "built $3"
}
build_wasm src/main/resources/opa/command_authz.rego acl/command_authz/decision src/main/resources/opa/command_authz.wasm
# spike.wasm is the ABI-regression smoke policy (OpaWasmSpikeTest) — regenerate it too so it's drift-guarded.
build_wasm src/main/resources/opa/spike.rego spike/decision src/main/resources/opa/spike.wasm
