#!/usr/bin/env bash
# Renders every docs/diagrams/src/*.mmd to docs/diagrams/svg/*.svg in Style A.
# Hand-authored SVGs (nbirth-size.svg, loss-ledger.svg) are NOT touched.
#
# Background is -b "#ffffff" (opaque white), NOT transparent: an opaque white
# canvas reads as a clean card on GitHub dark mode, whereas a transparent canvas
# would put dark node text on a dark page. Do not change to -b transparent.
#
# NOTE on the bracketed repo path: this repo can live under a path containing
# literal brackets (e.g. ".../[oss]/..."). mermaid-cli expands its -i argument
# as a glob, so an absolute path with brackets is read as a char-class and the
# input "doesn't exist". Fix: cd into this dir and pass RELATIVE paths so the
# glob string contains no brackets.
set -euo pipefail
shopt -s nullglob  # empty src/ -> zero iterations (parity with PowerShell), not a literal-glob failure
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here"
for f in src/*.mmd; do
  base="$(basename "$f" .mmd)"
  echo "rendering $base.mmd -> svg/$base.svg"
  npx -y @mermaid-js/mermaid-cli -i "src/$base.mmd" -o "svg/$base.svg" -c "mermaid-theme.json" -b "#ffffff"
done
echo "done. (hand-authored charts in svg/ are left untouched)"
# Docker fallback (no host Node), run from this dir:
#   docker run --rm -v "$PWD:/data" minlag/mermaid-cli \
#     -i /data/src/<name>.mmd -o /data/svg/<name>.svg -c /data/mermaid-theme.json -b "#ffffff"
