#!/usr/bin/env bash
# Renders every docs/diagrams/src/*.mmd to docs/diagrams/svg/*.svg in Style A.
# Hand-authored SVGs (nbirth-size.svg, loss-ledger.svg) are NOT touched.
set -euo pipefail
shopt -s nullglob  # empty src/ -> zero iterations (parity with PowerShell), not a literal-glob failure
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
