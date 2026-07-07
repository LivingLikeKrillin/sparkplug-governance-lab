#!/usr/bin/env bash
# Regenerates src/main/resources/opa/command_authz.wasm from command_authz.rego.
# Pinned toolchain: opa 0.70.0 (wasm output is version-specific).
set -euo pipefail
cd "$(dirname "$0")/.."
OPA_PIN="0.70.0"
opa version | grep -q "Version: ${OPA_PIN}" || { echo "ERROR: opa ${OPA_PIN} required (got: $(opa version | head -1))"; exit 1; }
opa build -t wasm -e acl/command_authz/decision \
  src/main/resources/opa/command_authz.rego -o build/policy-bundle.tar.gz
tar -xzf build/policy-bundle.tar.gz -C build   # → build/policy.wasm
cp build/policy.wasm src/main/resources/opa/command_authz.wasm
echo "built src/main/resources/opa/command_authz.wasm"
