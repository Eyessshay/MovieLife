[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'tools\demo-data.ps1') check
exit $LASTEXITCODE
