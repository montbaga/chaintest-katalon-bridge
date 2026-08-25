# Brings up ChainLP + chainlp-proxy, automatically trying a different
# port if 8085 is already taken on this machine - no manual editing of
# docker-compose.yml needed for that case. Run this instead of
# `docker compose up -d` directly.
Set-Location -Path $PSScriptRoot

$CandidatePorts = @(8085, 8090, 8095, 8100, 8105)

foreach ($Port in $CandidatePorts) {
    Write-Host "Trying ChainLP on port $Port..."
    $env:CHAINLP_PROXY_PORT = "$Port"
    $Output = docker compose up -d 2>&1
    $Status = $LASTEXITCODE
    $Output | ForEach-Object { Write-Host $_ }

    if ($Status -eq 0) {
        Write-Host ""
        Write-Host "ChainLP is up: http://localhost:$Port/"
        Write-Host "Set this in your Katalon project's Include/config/chaintest/chaintest.properties:"
        Write-Host "  chaintest.generator.chainlp.enabled=true"
        Write-Host "  chaintest.generator.chainlp.host.url=http://localhost:$Port/"
        exit 0
    }

    $OutputText = $Output | Out-String
    if ($OutputText -match "port is already allocated" -or $OutputText -match "[Pp]orts are not available") {
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
