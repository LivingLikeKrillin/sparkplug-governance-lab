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
