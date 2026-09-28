[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gitPattern = 'sk-[A-Za-z0-9_-]{20,}|AI_REVIEW_SUMMARY_API_KEY[[:space:]]*=[[:space:]]*[^<${#[:space:]][^[:space:]]{7,}'
$dotNetPattern = 'sk-[A-Za-z0-9_-]{20,}|AI_REVIEW_SUMMARY_API_KEY\s*=\s*[^<\$\{#\s][^\s]{7,}'

function Invoke-GitSecretSearch {
    param([Parameter(Mandatory = $true)][string]$Revision)

    $output = @(& git -C $repositoryRoot grep -Il -E $gitPattern $Revision -- 2>$null)
    if ($LASTEXITCODE -notin 0, 1) {
        throw "Git secret scan failed for revision $Revision."
    }
    return $output
}

$trackedMatches = @(Invoke-GitSecretSearch -Revision 'HEAD')
$historyMatches = New-Object System.Collections.Generic.List[string]
foreach ($commit in @(& git -C $repositoryRoot rev-list --all)) {
    foreach ($path in @(Invoke-GitSecretSearch -Revision $commit)) {
        $historyMatches.Add("$commit`t$path")
    }
}

$excludedSegments = @('\.git\', '\target\', '\.idea\', '\logs\')
$worktreeMatches = New-Object System.Collections.Generic.List[string]
foreach ($file in Get-ChildItem -LiteralPath $repositoryRoot -Recurse -File -Force) {
    $normalized = $file.FullName.Replace('/', '\')
    if ($excludedSegments | Where-Object { $normalized.Contains($_) }) {
        continue
    }
    if ($file.Length -gt 2MB) {
        continue
    }
    if (Select-String -LiteralPath $file.FullName -Pattern $dotNetPattern -Quiet -ErrorAction SilentlyContinue) {
        $relative = $file.FullName.Substring($repositoryRoot.Length).TrimStart('\')
        $worktreeMatches.Add($relative)
    }
}

if ($trackedMatches.Count -gt 0 -or $historyMatches.Count -gt 0 -or $worktreeMatches.Count -gt 0) {
    Write-Error (("Potential provider credential detected. tracked={0}, history={1}, worktree={2}. " +
        "Only file paths and commit identifiers may be inspected; never print matching lines.") -f
        $trackedMatches.Count, $historyMatches.Count, $worktreeMatches.Count)
    $trackedMatches | Sort-Object -Unique | ForEach-Object { Write-Host "tracked: $_" }
    $historyMatches | Sort-Object -Unique | ForEach-Object { Write-Host "history: $_" }
    $worktreeMatches | Sort-Object -Unique | ForEach-Object { Write-Host "worktree: $_" }
    exit 1
}

Write-Host 'Secret scan passed: no provider credential pattern in tracked files, Git history, or current worktree.'
