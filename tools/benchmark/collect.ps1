# AprismPrismate :: performance baseline collector (v26.16-A1)
# Boots each instance in turn, polls for the aprism-status.json publish,
# records the boot duration, and writes a comparable CSV.
#
# Usage: powershell -File tools\benchmark\collect.ps1
# Installs the latest Prismate jar into each instance before booting.

param(
    [string[]]$Instances = @(
        "prismate-fab-262",
        "openlumin-neoforge-26.2",
        "openlumin-fabric-1.21.10",
        "despotes-fab-1201"
    )
)

$ErrorActionPreference = "Continue"
$base = "C:\Users\Sails\Documents\Workspace\01-Active\Domain-Projects\Aprism\AprismPrismate"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outDir = Join-Path $base "tools\benchmark\results"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$outFile = Join-Path $outDir "boot-$stamp.csv"

"instance,mcVersion,prismateVersion,phase,deliveredTicks,uptimeMs,collectedAt" |
    Set-Content $outFile -Encoding utf8

function Stop-AllGameProcesses {
    Get-Process -Name java, mdl -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Seconds 4
}

function Get-LatestPrismateJar([string]$Loader) {
    $pattern = $Loader -eq "N" ? "AprismPrismate-*-N-26.2.jar" : "AprismPrismate-*-Fa-26.2.jar"
    $neoDir = Join-Path $base "neoforge\build\libs"
    $fabDir = Join-Path $base "fabric\build\libs"
    $searchDir = $Loader -eq "N" ? $neoDir : $fabDir
    return Get-ChildItem $searchDir -Filter $pattern -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Get-InstanceLoader([string]$name) {
    $neoInstances = @("openlumin-neoforge-26.2")
    return $neoInstances -contains $name ? "N" : "Fa"
}

foreach ($name in $Instances) {
    Write-Host "=== $name ==="
    Stop-AllGameProcesses

    $instDir = "C:\Users\Sails\AppData\Roaming\mdl\instances\$name"
    if (-not (Test-Path $instDir)) { Write-Host "  SKIP: instance not found"; continue }

    # Install the latest Prismate jar for this loader.
    $loader = Get-InstanceLoader $name
    $jar = Get-LatestPrismateJar $loader
    if (-not $jar) { Write-Host "  SKIP: no jar for loader $loader"; continue }
    $modsDir = Join-Path $instDir "mods"
    Get-ChildItem $modsDir -Filter "AprismPrismate*" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jar.FullName $modsDir -Force
    Write-Host ("  installed: {0} ({1:N0} bytes)" -f $jar.Name, $jar.Length)

    # Clean work dir and status for a cold boot.
    Remove-Item -Recurse -Force (Join-Path $instDir "prismate") -ErrorAction SilentlyContinue
    Remove-Item -Force (Join-Path $instDir "aprism-status.json") -ErrorAction SilentlyContinue

    # Launch.
    mdl launch $name --detach --no-queue --no-idle-timeout 2>&1 | Out-Null
    Write-Host "  launched, polling for status..."

    # Poll for the status file (max 180 s).
    $statusPath = Join-Path $instDir "aprism-status.json"
    $deadline = (Get-Date).AddSeconds(180)
    $found = $false
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        if (Test-Path $statusPath) {
            try {
                $status = Get-Content $statusPath -Raw | ConvertFrom-Json
                if ($status.phase -eq "LOADED") {
                    $found = $true
                    break
                }
            } catch { # partial write; keep polling
            }
        }
    }

    if ($found) {
        $line = "{0},{1},{2},{3},{4},{5},{6}" -f $name,
            $status.mcVersion, $status.prismateVersion, $status.phase,
            $status.deliveredTicks, $status.uptimeMs, (Get-Date -Format o)
        Write-Host ("  OK: boot={0}ms ticks={1}" -f $status.uptimeMs, $status.deliveredTicks)
    } else {
        $line = "$name,TIMEOUT,,,,$(Get-Date -Format o)"
        Write-Host "  TIMEOUT"
    }
    Add-Content -Path $outFile -Value $line -Encoding utf8
}

Stop-AllGameProcesses
Write-Host ""
Write-Host "=== Results: $outFile ==="
Get-Content $outFile
