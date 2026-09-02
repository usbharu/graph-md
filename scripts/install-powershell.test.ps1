$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$projectDirectory = Split-Path -Parent $PSScriptRoot
$installerPath = Join-Path $projectDirectory "install.ps1"
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

function Wait-ForServer([string] $HealthUrl) {
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $HealthUrl | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }
    throw "Fixture HTTP server did not start"
}

$serverJob = $null
$savedArchitecture = $env:PROCESSOR_ARCHITECTURE
$savedWowArchitecture = $env:PROCESSOR_ARCHITEW6432
$savedVersion = $env:GRAPHMD_VERSION
$savedInstallDirectory = $env:GRAPHMD_INSTALL_DIR
$savedReleasesUrl = $env:GRAPHMD_INSTALLER_RELEASES_URL

try {
    $fixtureRoot = Join-Path $testDirectory "http-root"
    $latestDirectory = Join-Path $fixtureRoot "releases\latest\download"
    $pinnedDirectory = Join-Path $fixtureRoot "releases\download\v1.2.3"
    $archiveDirectory = Join-Path $testDirectory "archive"
    New-Item -ItemType Directory -Path $latestDirectory, $pinnedDirectory, $archiveDirectory | Out-Null
    Set-Content -NoNewline -LiteralPath (Join-Path $archiveDirectory "graphmd.exe") -Value "new graphmd"
    $assetName = "graphmd-windows-x64-1.2.3.zip"
    $fixtureArchive = Join-Path $latestDirectory $assetName
    Compress-Archive -Path (Join-Path $archiveDirectory "graphmd.exe") -DestinationPath $fixtureArchive
    Copy-Item -LiteralPath $fixtureArchive -Destination $pinnedDirectory
    $fixtureChecksum = (Get-FileHash -Algorithm SHA256 -LiteralPath $fixtureArchive).Hash.ToLowerInvariant()
    $checksumLine = "$fixtureChecksum  $assetName"
    Set-Content -LiteralPath (Join-Path $latestDirectory "SHA256SUMS") -Value $checksumLine
    Set-Content -LiteralPath (Join-Path $pinnedDirectory "SHA256SUMS") -Value $checksumLine

    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $portProbe.Start()
    $port = ([Net.IPEndPoint] $portProbe.LocalEndpoint).Port
    $portProbe.Stop()
    $prefix = "http://127.0.0.1:$port/"
    $serverJob = Start-Job -ScriptBlock {
        param([string] $Root, [string] $Prefix)
        $listener = [Net.HttpListener]::new()
        $listener.Prefixes.Add($Prefix)
        $listener.Start()
        try {
            while ($listener.IsListening) {
                $context = $listener.GetContext()
                try {
                    if ($context.Request.Url.AbsolutePath -eq "/health") {
                        $context.Response.StatusCode = 200
                    } else {
                        $relativePath = [Uri]::UnescapeDataString($context.Request.Url.AbsolutePath.TrimStart('/'))
                        $filePath = Join-Path $Root ($relativePath -replace '/', [IO.Path]::DirectorySeparatorChar)
                        if (Test-Path -LiteralPath $filePath -PathType Leaf) {
                            $bytes = [IO.File]::ReadAllBytes($filePath)
                            $context.Response.StatusCode = 200
                            $context.Response.ContentLength64 = $bytes.Length
                            $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
                        } else {
                            $context.Response.StatusCode = 404
                        }
                    }
                } finally {
                    $context.Response.Close()
                }
            }
        } finally {
            $listener.Close()
        }
    } -ArgumentList $fixtureRoot, $prefix
    Wait-ForServer "${prefix}health"
    $env:GRAPHMD_INSTALLER_RELEASES_URL = "${prefix}releases"

    # Direct execution accepts arguments, which override environment defaults.
    $argumentDestination = Join-Path $testDirectory "argument-bin"
    $env:GRAPHMD_VERSION = "9.9.9"
    $env:GRAPHMD_INSTALL_DIR = Join-Path $testDirectory "environment-bin"
    & $installerPath --version v1.2.3 --install-dir $argumentDestination | Out-Null
    Assert-Equal "new graphmd" (Get-Content -Raw -LiteralPath (Join-Path $argumentDestination "graphmd.exe")) "Argument install"
    if (Test-Path -LiteralPath (Join-Path $env:GRAPHMD_INSTALL_DIR "graphmd.exe")) {
        throw "Argument install directory did not override the environment"
    }

    # The documented in-memory invocation uses a child scope and installs latest.
    $latestDestination = Join-Path $testDirectory "latest-bin"
    $env:GRAPHMD_VERSION = "latest"
    $env:GRAPHMD_INSTALL_DIR = $latestDestination
    function Invoke-GraphMdInstaller { return "sentinel function" }
    $script:GraphMdRepository = "sentinel repository"
    $script:GraphMdReleasesUrl = "sentinel releases"
    $originalErrorActionPreference = $ErrorActionPreference
    $installerText = Get-Content -Raw -LiteralPath $installerPath
    & ([scriptblock]::Create($installerText)) | Out-Null
    Assert-Equal "new graphmd" (Get-Content -Raw -LiteralPath (Join-Path $latestDestination "graphmd.exe")) "Latest install"
    Assert-Equal "sentinel function" (Invoke-GraphMdInstaller) "Caller function preservation"
    Assert-Equal "sentinel repository" $script:GraphMdRepository "Caller variable preservation"
    Assert-Equal "sentinel releases" $script:GraphMdReleasesUrl "Caller variable preservation"
    Assert-Equal $originalErrorActionPreference $ErrorActionPreference "ErrorActionPreference preservation"

    # Checksum failures leave an existing binary untouched.
    Set-Content -LiteralPath (Join-Path $pinnedDirectory "SHA256SUMS") -Value "$('0' * 64)  $assetName"
    $preservedDestination = Join-Path $testDirectory "preserved-bin"
    New-Item -ItemType Directory -Path $preservedDestination | Out-Null
    Set-Content -NoNewline -LiteralPath (Join-Path $preservedDestination "graphmd.exe") -Value "keep graphmd"
    Assert-Throws {
        & $installerPath --version 1.2.3 --install-dir $preservedDestination | Out-Null
    } "Checksum mismatch"
    Assert-Equal "keep graphmd" (Get-Content -Raw -LiteralPath (Join-Path $preservedDestination "graphmd.exe")) "Preserved install"

    Set-Content -LiteralPath (Join-Path $pinnedDirectory "SHA256SUMS") -Value ""
    Assert-Throws {
        & $installerPath --version 1.2.3 --install-dir (Join-Path $testDirectory "missing-checksum") | Out-Null
    } "Missing checksum"

    $env:GRAPHMD_INSTALLER_RELEASES_URL = "http://127.0.0.1:1/releases"
    Assert-Throws {
        & $installerPath --version 1.2.3 --install-dir (Join-Path $testDirectory "download-failure") | Out-Null
    } "Download failure"
    $env:GRAPHMD_INSTALLER_RELEASES_URL = "${prefix}releases"

    Assert-Throws { & $installerPath --version 1.2 --install-dir (Join-Path $testDirectory "invalid-version") | Out-Null } "Invalid version"

    $env:PROCESSOR_ARCHITECTURE = "ARM64"
    Remove-Item Env:PROCESSOR_ARCHITEW6432 -ErrorAction SilentlyContinue
    Assert-Throws { & $installerPath --install-dir (Join-Path $testDirectory "arm64") | Out-Null } "Unsupported Windows architecture"

    Write-Output "install.ps1 integration tests passed"
} finally {
    if ($serverJob) {
        Stop-Job -Job $serverJob -ErrorAction SilentlyContinue
        Remove-Job -Job $serverJob -Force -ErrorAction SilentlyContinue
    }
    $env:PROCESSOR_ARCHITECTURE = $savedArchitecture
    if ($null -eq $savedWowArchitecture) { Remove-Item Env:PROCESSOR_ARCHITEW6432 -ErrorAction SilentlyContinue } else { $env:PROCESSOR_ARCHITEW6432 = $savedWowArchitecture }
    if ($null -eq $savedVersion) { Remove-Item Env:GRAPHMD_VERSION -ErrorAction SilentlyContinue } else { $env:GRAPHMD_VERSION = $savedVersion }
    if ($null -eq $savedInstallDirectory) { Remove-Item Env:GRAPHMD_INSTALL_DIR -ErrorAction SilentlyContinue } else { $env:GRAPHMD_INSTALL_DIR = $savedInstallDirectory }
    if ($null -eq $savedReleasesUrl) { Remove-Item Env:GRAPHMD_INSTALLER_RELEASES_URL -ErrorAction SilentlyContinue } else { $env:GRAPHMD_INSTALLER_RELEASES_URL = $savedReleasesUrl }
    if (Test-Path -LiteralPath $testDirectory) {
        Remove-Item -Recurse -Force -LiteralPath $testDirectory
    }
}
