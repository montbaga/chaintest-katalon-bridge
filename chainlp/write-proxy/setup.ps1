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
# Strip a trailing slash - nginx's proxy_pass treats any URI baked into
# $chainlp_upstream (even just "/") as an override for every request's
# path, silently sending "/" instead of the real endpoint on every push.
# Confirmed directly: a URL ending in "/" here causes every ChainLP write
# to arrive at "/" instead of its real API path, failing with 405.
$remoteUrl = $remoteUrl.TrimEnd('/')

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
        # Shell out through cmd.exe for plain, predictable file
        # redirection, rather than letting PowerShell itself capture this
        # native command's streams. PowerShell 5.1's own handling of a
        # native command's stderr (via 2>&1 or 2>$file alike) was tried
        # multiple ways and confirmed unreliable every time - sometimes
        # wrapping lines as ErrorRecord objects that fail to text-match as
        # plain strings further down, sometimes leaking formatted
        # "NativeCommandError" noise to the console regardless of redirect
        # target, sometimes dropping content if $ErrorActionPreference is
        # anything quieter than 'Continue'. A cmd.exe /c wrapper does the
        # redirection itself at the OS level, with none of that.
        $stdoutFile = [System.IO.Path]::GetTempFileName()
        $stderrFile = [System.IO.Path]::GetTempFileName()
        cmd /c "docker compose up -d > `"$stdoutFile`" 2> `"$stderrFile`""
        $status = $LASTEXITCODE
        $stdout = Get-Content -Path $stdoutFile -ErrorAction SilentlyContinue
        $stderrText = Get-Content -Raw -Path $stderrFile -ErrorAction SilentlyContinue
        Remove-Item -Path $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
        $stdout | ForEach-Object { Write-Host $_ }
        if ($stderrText) { Write-Host $stderrText }

        if ($status -eq 0) {
            # docker compose up -d returning success does NOT guarantee
            # the container stays up - it can report success and then
            # crash moments later. A single fixed-delay check isn't
            # reliable either: confirmed directly against chainlp-proxy
            # that a container can still show State.Running=true 2
            # seconds in, then die 3 seconds after that. Poll for several
            # seconds instead of trusting one snapshot.
            $running = $false
            for ($i = 0; $i -lt 5; $i++) {
                Start-Sleep -Seconds 1
                $checkNow = docker inspect -f '{{.State.Running}}' chaintest-katalon-chainlp-write-proxy 2>$null
                if ($checkNow -ne 'true') {
                    $running = $false
                    break
                }
                $running = $true
            }
            if ($running) {
                $chosenPort = $port
                break
            }
            Write-Host "docker compose up -d reported success, but chainlp-write-proxy isn't actually running - its own logs:"
            docker logs chaintest-katalon-chainlp-write-proxy 2>&1 | Select-Object -Last 15 | ForEach-Object { Write-Host $_ }
            $stderrText = docker logs chaintest-katalon-chainlp-write-proxy 2>&1 | Out-String
        }

        if ($stderrText -match "port is already allocated" -or $stderrText -match "[Pp]orts are not available") {
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

# Report whatever this actually got published on, not an assumed
# "localhost" - only wrong if ports: was hand-edited to bind a different
# host; "0.0.0.0" isn't itself a URL you can browse to, so that case
# still falls back to localhost.
$boundHost = (docker port chaintest-katalon-chainlp-write-proxy 80/tcp 2>$null | Select-Object -First 1) -replace ':\d+$', ''
if ([string]::IsNullOrWhiteSpace($boundHost) -or $boundHost -eq '0.0.0.0' -or $boundHost -eq '127.0.0.1') {
    $boundHost = 'localhost'
}

Write-Host ""
Write-Host "Done. In your CI pipeline (any platform - GitLab/GitHub/Azure), set:"
Write-Host "  CHAINTEST_GENERATOR_CHAINLP_ENABLED=true"
Write-Host "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://${boundHost}:$chosenPort/"
Write-Host ""
Write-Host "If your CI job runs inside a Docker container (a 'docker' executor/"
Write-Host "runner), use this instead:"
Write-Host "  CHAINTEST_GENERATOR_CHAINLP_HOST_URL=http://host.docker.internal:$chosenPort/"
Write-Host ""
Write-Host "Re-run this script any time to change the URL or credential - it"
Write-Host "overwrites .env and restarts the container."
