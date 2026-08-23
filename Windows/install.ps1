<#
.SYNOPSIS
    Installs the ChainTest-Katalon Bridge into a Katalon Studio project.

.DESCRIPTION
    Copies the bridge's Test Listener, Keywords, config, and Drivers jars
    into the target Katalon project, then records exactly what it installed
    in <project>/.chaintest-bridge/manifest.txt so uninstall.ps1 can remove
    it cleanly later without touching anything else in the project.

    Safe to re-run (upgrade): library/keyword/jar files are always
    refreshed to the version shipped in this package.
    Include/config/chaintest/chaintest.properties is left alone if the
    target project already customized it - pass -Force to overwrite it too.

.PARAMETER ProjectPath
    Path to the target Katalon Studio project root (the folder containing
    the project's *.prj file).

.PARAMETER Force
    Also overwrite an existing Include/config/chaintest/chaintest.properties
    in the target project with the one shipped here.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install.ps1 -ProjectPath "C:\Users\User\Katalon Studio\my-other-project"

.EXAMPLE
    .\install.ps1 -ProjectPath "..\another-project" -Force
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectPath,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path $PSScriptRoot -Parent
$payloadRoot = Join-Path $repoRoot 'payload'
$version = (Get-Content (Join-Path $repoRoot 'VERSION') -Raw).Trim()

if (-not (Test-Path $ProjectPath)) {
    throw "ProjectPath does not exist: $ProjectPath"
}
$ProjectPath = (Resolve-Path $ProjectPath).Path

$projectFile = Get-ChildItem -Path $ProjectPath -Filter '*.prj' -File -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $projectFile) {
    throw "No *.prj file found directly under '$ProjectPath'. This does not look like a Katalon Studio project root - aborting to avoid writing into the wrong folder."
}

Write-Host "Installing ChainTest-Katalon Bridge v$version into: $ProjectPath" -ForegroundColor Cyan
Write-Host "  (detected Katalon project: $($projectFile.Name))"

$manifestDir = Join-Path $ProjectPath '.chaintest-bridge'
New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null
$installedRelativePaths = New-Object System.Collections.Generic.List[string]

$configRelativePath = 'Include\config\chaintest\chaintest.properties'

foreach ($sourceFile in (Get-ChildItem -Path $payloadRoot -Recurse -File)) {
    $relativePath = $sourceFile.FullName.Substring($payloadRoot.Length).TrimStart('\', '/')
    $destinationFile = Join-Path $ProjectPath $relativePath

    $isUserConfig = ($relativePath -eq $configRelativePath) -or ($relativePath -eq $configRelativePath.Replace('\', '/'))
    if ($isUserConfig -and (Test-Path $destinationFile) -and -not $Force) {
        Write-Host "  SKIP (already customized, use -Force to overwrite): $relativePath" -ForegroundColor Yellow
        # Still part of this install even though this run didn't touch it -
        # the manifest tracks "what this bridge is responsible for", not
        # "what this specific run copied". Leaving it out here would make
        # uninstall silently unable to find (and therefore never remove) a
        # customized chaintest.properties, even with -RemoveConfig.
        $installedRelativePaths.Add($relativePath) | Out-Null
        continue
    }

    $destinationDir = Split-Path $destinationFile -Parent
    if (-not (Test-Path $destinationDir)) {
        New-Item -ItemType Directory -Path $destinationDir -Force | Out-Null
    }
    Copy-Item -Path $sourceFile.FullName -Destination $destinationFile -Force
    $installedRelativePaths.Add($relativePath) | Out-Null
    Write-Host "  OK   $relativePath"
}

$manifestPath = Join-Path $manifestDir 'manifest.txt'
Set-Content -Path $manifestPath -Value (@($version) + $installedRelativePaths) -Encoding utf8

# Best-effort: register the Drivers jars in .classpath if the project
# already has one open in the IDE, so Katalon's editor resolves the
# ChainTest classes immediately instead of after a manual refresh.
$classpathFile = Join-Path $ProjectPath '.classpath'
if (Test-Path $classpathFile) {
    [xml]$classpathXml = Get-Content $classpathFile -Raw
    $classpathRoot = $classpathXml.classpath
    $existingPaths = $classpathRoot.classpathentry | ForEach-Object { $_.path }
    $jarsToRegister = @(
        'Drivers/chaintest-core-1.0.12.jar',
        'Drivers/freemarker-2.3.33.jar',
        'Drivers/jackson-databind-2.18.0.jar',
        'Drivers/jackson-core-2.18.0.jar',
        'Drivers/jackson-annotations-2.18.0.jar',
        'Drivers/snakeyaml-2.3.jar',
        'Drivers/slf4j-api-2.0.16.jar'
    )
    $classpathChanged = $false
    foreach ($jarPath in $jarsToRegister) {
        if ($existingPaths -notcontains $jarPath) {
            $entry = $classpathXml.CreateElement('classpathentry')
            $entry.SetAttribute('kind', 'lib')
            $entry.SetAttribute('path', $jarPath)
            $classpathRoot.AppendChild($entry) | Out-Null
            $classpathChanged = $true
        }
    }
    if ($classpathChanged) {
        $classpathXml.Save($classpathFile)
        Write-Host "  OK   .classpath (registered Drivers jars for the IDE editor)"
    }
}

Write-Host ""
Write-Host "Install complete." -ForegroundColor Green
Write-Host "Next steps:"
Write-Host "  1. Reopen (or refresh) the project in Katalon Studio."
Write-Host "  2. Run any Test Suite as usual - no changes needed to existing tests."
Write-Host "  3. Look for '[ChainTest]' lines in the console, and a chaintest-report/ folder afterwards."
Write-Host "  4. Open chaintest-report/<Name>_<timestamp>/Index.html directly - no server needed."
Write-Host "  5. Want real-time analytics/history too? See chainlp/ in this bridge's own repository, then set chaintest.generator.chainlp.enabled=true."
