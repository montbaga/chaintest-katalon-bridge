# Brings up ChainLP + chainlp-proxy, automatically trying a different
# port if 8085 is already taken on this machine - no manual editing of
# docker-compose.yml needed for that case. Run this instead of
# `docker compose up -d` directly.
Set-Location -Path $PSScriptRoot

$CandidatePorts = @(8085, 8090, 8095, 8100, 8105)

foreach ($Port in $CandidatePorts) {
    Write-Host "Trying ChainLP on port $Port..."
    $env:CHAINLP_PROXY_PORT = "$Port"

    # Shell out through cmd.exe for plain, predictable file redirection,
    # rather than letting PowerShell itself capture this native command's
    # streams. PowerShell 5.1's own handling of a native command's stderr
    # (via 2>&1 or 2>$file alike) was tried multiple ways and confirmed
    # unreliable every time - sometimes wrapping lines as ErrorRecord
    # objects that fail to text-match as plain strings further down,
    # sometimes leaking formatted "NativeCommandError" noise to the
    # console regardless of redirect target, sometimes dropping content
    # if $ErrorActionPreference is anything quieter than 'Continue'. A
    # cmd.exe /c wrapper does the redirection itself at the OS level,
    # with none of that.
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    cmd /c "docker compose up -d > `"$stdoutFile`" 2> `"$stderrFile`""
    $Status = $LASTEXITCODE
    $stdout = Get-Content -Path $stdoutFile -ErrorAction SilentlyContinue
    $stderrText = Get-Content -Raw -Path $stderrFile -ErrorAction SilentlyContinue
    Remove-Item -Path $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
    $stdout | ForEach-Object { Write-Host $_ }
    if ($stderrText) { Write-Host $stderrText }

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
            # "localhost" would be silently wrong here otherwise. Real host
            # bindings (a specific IP) are reported exactly as bound.
            $BoundHost = (docker port chaintest-katalon-chainlp-proxy 80/tcp 2>$null | Select-Object -First 1) -replace ':\d+$', ''
            if ([string]::IsNullOrWhiteSpace($BoundHost) -or $BoundHost -eq '127.0.0.1') {
                $BoundHost = 'localhost'
            } elseif ($BoundHost -eq '0.0.0.0') {
                # "0.0.0.0" means published on every network interface, not
                # just loopback - someone (Scenario E) deliberately opened
                # this up so other machines can reach it, which makes
                # "localhost" actively wrong advice here (it would only ever
                # mean "whichever machine you're sitting at", never this
                # server). Auto-detect this machine's own network-facing IP
                # instead, so the printed address is one another machine
                # could actually use, with no manual ipconfig lookup needed.
                # Loopback, link-local (APIPA), and Hyper-V/WSL virtual
                # switches (what Docker Desktop itself creates) are
                # excluded since none of those are reachable from another
                # real machine.
                $detectedIp = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                    Where-Object {
                        $_.IPAddress -notlike '127.*' -and
                        $_.IPAddress -notlike '169.254.*' -and
                        $_.InterfaceAlias -notlike '*Loopback*' -and
                        $_.InterfaceAlias -notlike 'vEthernet*'
                    } |
                    Select-Object -First 1 -ExpandProperty IPAddress
                if ($detectedIp) {
                    $BoundHost = $detectedIp
                } else {
                    $BoundHost = 'localhost'
                }
            }
            Write-Host ""
            Write-Host "ChainLP is up: http://${BoundHost}:$Port/"
            if ($BoundHost -ne 'localhost' -and $BoundHost -ne '127.0.0.1') {
                Write-Host "(This is this machine's own detected network IP - double-check it's the right one and actually reachable from wherever you need it before sharing it, since a server with more than one network adapter or VPN active can have several.)"
            }
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
