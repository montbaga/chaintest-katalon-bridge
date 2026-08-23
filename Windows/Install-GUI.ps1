<#
.SYNOPSIS
    Interactive, no-typing wrapper around install.ps1 for people who'd
    rather click a folder than type a path on a command line.

    Launched by double-clicking Install.bat. Not meant to be run directly
    with arguments - see install.ps1 for the scriptable/CI entry point.
#>

Add-Type -AssemblyName System.Windows.Forms

function Show-Info($message) {
    [System.Windows.Forms.MessageBox]::Show(
        $message, 'ChainTest-Katalon Bridge',
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Information) | Out-Null
}

function Show-Warning($message) {
    [System.Windows.Forms.MessageBox]::Show(
        $message, 'ChainTest-Katalon Bridge',
        [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
}

$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = "Select your Katalon Studio project folder (the top-level folder that contains a *.prj file)"
$dialog.ShowNewFolderButton = $false

Write-Host "Waiting for folder selection ..."
$result = $dialog.ShowDialog()

if ($result -ne [System.Windows.Forms.DialogResult]::OK) {
    Write-Host "Cancelled - no folder selected."
    exit 0
}

$projectPath = $dialog.SelectedPath

$projectFile = Get-ChildItem -Path $projectPath -Filter '*.prj' -File -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $projectFile) {
    Show-Warning "No *.prj file found directly in:`n$projectPath`n`nThat doesn't look like a Katalon Studio project root. Pick the top-level project folder (the one Katalon Studio opens as a project) and try again."
    exit 1
}

Write-Host "Installing into: $projectPath"
Write-Host ""

try {
    & (Join-Path $PSScriptRoot 'install.ps1') -ProjectPath $projectPath
}
catch {
    Show-Warning "Install failed:`n`n$($_.Exception.Message)`n`nSee the console window for full details."
    exit 1
}

Show-Info "ChainTest-Katalon Bridge installed into:`n$projectPath`n`nNext steps:`n1. Reopen (or refresh) the project in Katalon Studio.`n2. Run any Test Suite as usual.`n3. Look for a chaintest-report folder afterwards.`n4. Open chaintest-report\<Name>_<timestamp>\Index.html directly."
