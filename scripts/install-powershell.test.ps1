$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$projectDirectory = Split-Path -Parent $PSScriptRoot
$env:GRAPHMD_INSTALLER_TESTING = "1"
. (Join-Path $projectDirectory "install.ps1")

$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ("graphmd-install-test-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testDirectory | Out-Null

function Assert-Equal([string] $Expected, [string] $Actual, [string] $Message) {
    if ($Expected -ne $Actual) {
        throw "$Message`: expected '$Expected', got '$Actual'"
    }
}

function Assert-Throws([scriptblock] $Action, [string] $Message) {
    try {
        & $Action
    } catch {
        return
    }
    throw "$Message`: expected an error"
}

try {
    $fixtureDirectory = Join-Path $testDirectory "release"
    $archiveDirectory = Join-Path $fixtureDirectory "archive"
    New-Item -ItemType Directory -Path $archiveDirectory | Out-Null
    Set-Content -NoNewline -LiteralPath (Join-Path $archiveDirectory "graphmd.exe") -Value "new graphmd"
    $fixtureArchive = Join-Path $fixtureDirectory "graphmd-windows-x64-1.2.3.zip"
    Compress-Archive -Path (Join-Path $archiveDirectory "graphmd.exe") -DestinationPath $fixtureArchive
    $fixtureChecksum = (Get-FileHash -Algorithm SHA256 -LiteralPath $fixtureArchive).Hash.ToLowerInvariant()
    Set-Content -LiteralPath (Join-Path $fixtureDirectory "SHA256SUMS") -Value "$fixtureChecksum  graphmd-windows-x64-1.2.3.zip"

    function Get-GraphMdTarget { return "windows-x64" }
    function Receive-GraphMdFile([string] $Uri, [string] $OutFile) {
        Copy-Item -LiteralPath (Join-Path $script:FixtureDirectory ([IO.Path]::GetFileName($Uri))) -Destination $OutFile
    }
    $script:FixtureDirectory = $fixtureDirectory

    $argumentDestination = Join-Path $testDirectory "argument-bin"
    New-Item -ItemType Directory -Path $argumentDestination | Out-Null
    Set-Content -NoNewline -LiteralPath (Join-Path $argumentDestination "graphmd.exe") -Value "old graphmd"
    $env:GRAPHMD_VERSION = "9.9.9"
    $env:GRAPHMD_INSTALL_DIR = Join-Path $testDirectory "environment-bin"
    Invoke-GraphMdInstaller @("--version", "v1.2.3", "--install-dir", $argumentDestination) | Out-Null
    Assert-Equal "new graphmd" (Get-Content -Raw -LiteralPath (Join-Path $argumentDestination "graphmd.exe")) "Argument install"
    if (Test-Path -LiteralPath (Join-Path $env:GRAPHMD_INSTALL_DIR "graphmd.exe")) {
        throw "Argument install directory did not override the environment"
    }

    Remove-Item Env:GRAPHMD_VERSION
    Remove-Item Env:GRAPHMD_INSTALL_DIR
    $latestDestination = Join-Path $testDirectory "latest-bin"
    Invoke-GraphMdInstaller @("--install-dir=$latestDestination") | Out-Null
    Assert-Equal "new graphmd" (Get-Content -Raw -LiteralPath (Join-Path $latestDestination "graphmd.exe")) "Latest install"

    $badFixtureDirectory = Join-Path $testDirectory "bad-release"
    New-Item -ItemType Directory -Path $badFixtureDirectory | Out-Null
    Copy-Item -LiteralPath $fixtureArchive -Destination $badFixtureDirectory
    Set-Content -LiteralPath (Join-Path $badFixtureDirectory "SHA256SUMS") -Value "$('0' * 64)  graphmd-windows-x64-1.2.3.zip"
    $script:FixtureDirectory = $badFixtureDirectory
    $preservedDestination = Join-Path $testDirectory "preserved-bin"
    New-Item -ItemType Directory -Path $preservedDestination | Out-Null
    Set-Content -NoNewline -LiteralPath (Join-Path $preservedDestination "graphmd.exe") -Value "keep graphmd"
    Assert-Throws {
        Invoke-GraphMdInstaller @("--version", "1.2.3", "--install-dir", $preservedDestination) | Out-Null
    } "Checksum mismatch"
    Assert-Equal "keep graphmd" (Get-Content -Raw -LiteralPath (Join-Path $preservedDestination "graphmd.exe")) "Preserved install"

    $emptyFixtureDirectory = Join-Path $testDirectory "empty-release"
    New-Item -ItemType Directory -Path $emptyFixtureDirectory | Out-Null
    Set-Content -LiteralPath (Join-Path $emptyFixtureDirectory "SHA256SUMS") -Value ""
    $script:FixtureDirectory = $emptyFixtureDirectory
    Assert-Throws {
        Invoke-GraphMdInstaller @("--version", "1.2.3", "--install-dir", (Join-Path $testDirectory "missing")) | Out-Null
    } "Missing checksum"

    function Receive-GraphMdFile([string] $Uri, [string] $OutFile) {
        throw "simulated download failure"
    }
    Assert-Throws {
        Invoke-GraphMdInstaller @("--version", "1.2.3", "--install-dir", (Join-Path $testDirectory "download-failure")) | Out-Null
    } "Download failure"

    Assert-Equal "latest" (Get-GraphMdNormalizedVersion "latest") "Latest normalization"
    Assert-Equal "v1.2.3" (Get-GraphMdNormalizedVersion "1.2.3") "Version normalization"
    Assert-Throws { Get-GraphMdNormalizedVersion "1.2" | Out-Null } "Invalid version"

    # Exercise the real architecture check after restoring its implementation.
    Remove-Item Function:Get-GraphMdTarget
    . (Join-Path $projectDirectory "install.ps1")
    Assert-Equal "windows-x64" (Get-GraphMdTarget) "Windows x64 target"
    $savedArchitecture = $env:PROCESSOR_ARCHITECTURE
    $savedWowArchitecture = $env:PROCESSOR_ARCHITEW6432
    try {
        $env:PROCESSOR_ARCHITECTURE = "ARM64"
        Remove-Item Env:PROCESSOR_ARCHITEW6432 -ErrorAction SilentlyContinue
        Assert-Throws { Get-GraphMdTarget | Out-Null } "Unsupported Windows architecture"
    } finally {
        $env:PROCESSOR_ARCHITECTURE = $savedArchitecture
        if ($null -eq $savedWowArchitecture) {
            Remove-Item Env:PROCESSOR_ARCHITEW6432 -ErrorAction SilentlyContinue
        } else {
            $env:PROCESSOR_ARCHITEW6432 = $savedWowArchitecture
        }
    }

    Write-Output "install.ps1 tests passed"
} finally {
    Remove-Item Env:GRAPHMD_INSTALLER_TESTING -ErrorAction SilentlyContinue
    Remove-Item Env:GRAPHMD_VERSION -ErrorAction SilentlyContinue
    Remove-Item Env:GRAPHMD_INSTALL_DIR -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $testDirectory) {
        Remove-Item -Recurse -Force -LiteralPath $testDirectory
    }
}
