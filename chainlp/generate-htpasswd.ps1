<#
.SYNOPSIS
    Generates proxy/.htpasswd, the login required for remote (tunnel)
    access to ChainLP. Local access on this machine (http://localhost:8085)
    never needs this - only chainlp-tunnel-auth reads it.

.PARAMETER Username
    The username you'll type when the login prompt appears.

.PARAMETER Password
    The password you'll type when the login prompt appears. Prompted
    securely if not passed.

.EXAMPLE
    .\generate-htpasswd.ps1 -Username monty
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Username,

    [securestring]$Password
)

$ErrorActionPreference = 'Stop'

if (-not $Password) {
    $Password = Read-Host -Prompt "Password for '$Username'" -AsSecureString
}
$bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
$plainPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
[System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)

$scriptDir = $PSScriptRoot
$outFile = Join-Path $scriptDir 'proxy\.htpasswd'

Write-Host "Generating bcrypt hash via a throwaway 'httpd:alpine' container (needs Docker, nothing installed locally)..."
$line = docker run --rm httpd:alpine htpasswd -Bbn $Username $plainPassword
$plainPassword = $null

if (-not $line) {
    throw "htpasswd produced no output - is Docker running?"
}

Set-Content -Path $outFile -Value $line -Encoding ascii -NoNewline
Write-Host "Wrote $outFile"
Write-Host ""
Write-Host "Next: docker compose --profile tunnel up -d"
Write-Host "(Re-run this script any time to change the password - it overwrites the file.)"
