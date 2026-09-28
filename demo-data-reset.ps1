[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'tools\demo-data.ps1') reset -Force:$Force
exit $LASTEXITCODE
