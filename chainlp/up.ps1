# Brings up ChainLP + chainlp-proxy, automatically trying a different
# port if 8085 is already taken on this machine - no manual editing of
# docker-compose.yml needed for that case. Run this instead of
# `docker compose up -d` directly.
Set-Location -Path $PSScriptRoot

$CandidatePorts = @(8085, 8090, 8095, 8100, 8105)

foreach ($Port in $CandidatePorts) {
    Write-Host "Trying ChainLP on port $Port..."
    $env:CHAINLP_PROXY_PORT = "$Port"

    # Redirect stderr to a real file rather than 2>&1 - PowerShell 5.1
    # wraps a native command's stderr lines as ErrorRecord objects when
    # merged via 2>&1, which can silently fail to text-match as plain
    # strings further down (confirmed directly: a clear "port is already
    # allocated" message failed the -match check below under 2>&1). A
    # file redirect captures the raw, unmangled text instead.
    # Note: PowerShell 5.1 prints native stderr to the console as a
    # red "NativeCommandError"-looking line regardless of this redirect
    # target - cosmetic only, confirmed directly. Setting
    # $ErrorActionPreference to anything quieter than 'Continue' here
    # was tried and rejected: it discards the content before the
    # redirect ever sees it, breaking the port-conflict detection below
    # entirely rather than just hiding the noise.
    $errFile = [System.IO.Path]::GetTempFileName()
    $stdout = docker compose up -d 2>$errFile
    $Status = $LASTEXITCODE
    $stderrText = Get-Content -Raw -Path $errFile -ErrorAction SilentlyContinue
    Remove-Item -Path $errFile -ErrorAction SilentlyContinue
    $stdout | ForEach-Object { Write-Host $_ }

    if ($Status -eq 0) {
        # docker compose up -d returning success does NOT guarantee the
        # container stays up - it can report success and then crash
        # moments later. A single fixed-delay check isn't reliable either:
        # confirmed directly that a container can still show
        # State.Running=true 2 seconds in, then die 3 seconds after that
        # (nginx's own entrypoint takes a variable amount of time before
        # it actually attempts its port bind and fails). Poll for several
        # seconds instead of trusting one snapshot.
        $running = $false
        for ($i = 0; $i -lt 5; $i++) {
            Start-Sleep -Seconds 1
            $checkNow = docker inspect -f '{{.State.Running}}' chaintest-katalon-chainlp-proxy 2>$null
            if ($checkNow -ne 'true') {
                $running = $false
                break
            }
            $running = $true
        }
        if ($running) {
            # Report whatever this actually got published on, not an
            # assumed "localhost" - if ports: was hand-edited to bind a
            # different host (e.g. a real IP for Scenario E/testing),
            # "localhost" would be silently wrong here otherwise. "0.0.0.0"
            # isn't itself a URL you can browse to, so that case still
            # falls back to localhost - real host bindings (a specific
            # IP) are reported exactly as bound.
            $BoundHost = (docker port chaintest-katalon-chainlp-proxy 80/tcp 2>$null | Select-Object -First 1) -replace ':\d+$', ''
            if ([string]::IsNullOrWhiteSpace($BoundHost) -or $BoundHost -eq '0.0.0.0' -or $BoundHost -eq '127.0.0.1') {
                $BoundHost = 'localhost'
            }
            Write-Host ""
            Write-Host "ChainLP is up: http://${BoundHost}:$Port/"
            Write-Host "Set this in your Katalon project's Include/config/chaintest/chaintest.properties:"
            Write-Host "  chaintest.generator.chainlp.enabled=true"
            Write-Host "  chaintest.generator.chainlp.host.url=http://${BoundHost}:$Port/"
            exit 0
        }
        Write-Host "docker compose up -d reported success, but chainlp-proxy isn't actually running - its own logs:"
        docker logs chaintest-katalon-chainlp-proxy 2>&1 | Select-Object -Last 15 | ForEach-Object { Write-Host $_ }
        $stderrText = docker logs chaintest-katalon-chainlp-proxy 2>&1 | Out-String
    }

    if ($stderrText -match "port is already allocated" -or $stderrText -match "[Pp]orts are not available") {
        Write-Host "Port $Port is already in use on this machine - trying the next one..."
        continue
    }

    Write-Host "docker compose up failed for a reason unrelated to the port - see the error above."
    exit 1
}

Write-Host ""
Write-Host "All candidate ports ($($CandidatePorts -join ', ')) are already in use."
Write-Host "Pick a free one yourself and run:"
Write-Host '  $env:CHAINLP_PROXY_PORT="<port>"; docker compose up -d'
exit 1
