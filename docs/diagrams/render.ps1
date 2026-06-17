# Renders every docs/diagrams/src/*.mmd to docs/diagrams/svg/*.svg in Style A.
# Hand-authored SVGs (nbirth-size.svg, loss-ledger.svg) are NOT touched.
# First run downloads a Puppeteer Chromium (cached by npx).
#
# NOTE on the bracketed repo path: this repo can live under a path containing
# literal brackets (e.g. "...\[oss]\..."). Both PowerShell's Get-ChildItem -Path
# and mermaid-cli's -i argument treat brackets as a glob char-class, so the
# enumeration silently matches zero files and mermaid can't find its input.
# Fix: enumerate with -LiteralPath, then Push-Location into this dir and invoke
# mermaid-cli with RELATIVE paths so no glob string contains brackets.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location -LiteralPath $here
try {
  Get-ChildItem -LiteralPath "src" -Filter *.mmd | ForEach-Object {
    $base = $_.BaseName
    Write-Host "rendering $($_.Name) -> svg/$base.svg"
    npx -y "@mermaid-js/mermaid-cli" -i "src/$base.mmd" -o "svg/$base.svg" -c "mermaid-theme.json" -b "#ffffff"
    if ($LASTEXITCODE -ne 0) { throw "mermaid-cli failed for $($_.Name) (exit $LASTEXITCODE)" }
  }
}
finally {
  Pop-Location
}
Write-Host "done. (hand-authored charts in svg/ are left untouched)"
