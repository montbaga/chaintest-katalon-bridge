<#
.SYNOPSIS
    One-time interactive setup for chainlp-write-proxy. The only things
    you need to know before running this are your real ChainLP's address
    and its real login - everything else (encoding the credential
    correctly, writing .env, starting the container, telling you what to
    paste into CI) is handled here rather than by hand.
#>

$ErrorActionPreference = 'Stop'
$scriptDir = $PSScriptRoot

Write-Host "chainlp-write-proxy setup"
Write-Host "========================="
Write-Host ""

$remoteUrl = Read-Host "Real ChainLP URL (e.g. https://chainlp.yourco.com)"

Write-Host ""
Write-Host "How does that ChainLP log you in?"
Write-Host "  1) Username + password"
Write-Host "  2) A single API token/key"
$choice = Read-Host "Enter 1 or 2"

if ($choice -eq '1') {
    $username = Read-Host "Username"
    $securePassword = Read-Host "Password" -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    $pair = "${username}:${plainPassword}"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
    $plainPassword = $null
    $authHeader = "Basic $encoded"
} elseif ($choice -eq '2') {
    $token = Read-Host "API token/key"
    $authHeader = "Bearer $token"
} else {
    throw "Enter 1 or 2."
}

$envFile = Join-Path $scriptDir ".env"
# Set-Content -Encoding utf8 always prepends a BOM in Windows PowerShell
# 5.1 (utf8NoBOM isn't available here) - some .env parsers choke on that
# leading byte sequence, so write the file directly instead.
$envContent = "CHAINLP_REMOTE_URL=$remoteUrl`nCHAINLP_REMOTE_AUTH_HEADER=$authHeader"
[System.IO.File]::WriteAllText($envFile, $envContent, (New-Object System.Text.UTF8Encoding $false))
Write-Host ""
Write-Host "Wrote $envFile"

Write-Host "Starting chainlp-write-proxy..."
Push-Location $scriptDir
$chosenPort = $null
try {
    $candidatePorts = @(8086, 8091, 8096, 8101, 8106)
    foreach ($port in $candidatePorts) {
        $env:CHAINLP_WRITE_PROXY_PORT = "$port"
        # docker compose writes its normal progress output to stderr, not
        # an error - but PowerShell 5.1 wraps every stderr line from a
        # native command as a terminating NativeCommandError under
        # $ErrorActionPreference = 'Stop' (set above), even on success.
        # Relax it for just this call and check $LASTEXITCODE instead.
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $output = docker compose up -d 2>&1
        $ErrorActionPreference = $previousErrorActionPreference
        if ($LASTEXITCODE -eq 0) {
            $chosenPort = $port
            break
        }
        $output | ForEach-Object { Write-Host $_ }
        $outputText = $output | Out-String
        if ($outputText -match "port is already allocated" -or $outputText -match "[Pp]orts are not available") {
            Write-Host "Port $port is already in use on this machine - trying the next one..."
            continue
        }
        throw "docker compose up failed for a reason unrelated to the port - see the error above."
    }
} finally {
    Pop-Location
}

if (-not $chosenPort) {
    Write-Host "All candidate ports ($($candidatePorts -join ', ')) are already in use."
    Write-Host "Pick a free one yourself and re-run with:"
    Write-Host '  $env:CHAINLP_WRITE_PROXY_PORT="<port>"; docker compose up -d'
    exit 1
}

Write-Host ""
Write-Host "Done. In your CI pipeline (any platform - GitLab/GitHub/Azure), set:"
Write-Host "  CHAINTEST_GENERATOR_CHAINLP_ENABLED=true"
Write-Host "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://localhost:$chosenPort/"
Write-Host ""
Write-Host "If your CI job runs inside a Docker container (a 'docker' executor/"
Write-Host "runner), use this instead:"
Write-Host "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://host.docker.internal:$chosenPort/"
Write-Host ""
Write-Host "Re-run this script any time to change the URL or credential - it"
Write-Host "overwrites .env and restarts the container."
