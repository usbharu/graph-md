& {
param([string[]] $GraphMdInstallerArguments)

$ErrorActionPreference = "Stop"

$GraphMdRepository = "usbharu/graph-md"
$GraphMdReleasesUrl = if ($env:GRAPHMD_INSTALLER_RELEASES_URL) {
    $env:GRAPHMD_INSTALLER_RELEASES_URL.TrimEnd("/")
} else {
    "https://github.com/$GraphMdRepository/releases"
}

function Show-GraphMdInstallerUsage {
    @"
Install the GraphMD native CLI.

Usage:
  install.ps1 [--version VERSION] [--install-dir DIRECTORY]

Options:
  --version VERSION       Install latest (default), X.Y.Z, or vX.Y.Z.
  --install-dir DIRECTORY Install into DIRECTORY.
  -h, --help              Show this help.

Environment:
  GRAPHMD_VERSION         Default version when --version is omitted.
  GRAPHMD_INSTALL_DIR     Default directory when --install-dir is omitted.
"@
}

function Get-GraphMdNormalizedVersion([string] $Version) {
    if ($Version -eq "latest") {
        return "latest"
    }
    $plainVersion = $Version -replace '^v', ''
    if ($plainVersion -notmatch '^\d+\.\d+\.\d+$') {
        throw "invalid version '$Version' (expected latest, X.Y.Z, or vX.Y.Z)"
    }
    return "v$plainVersion"
}

function Get-GraphMdTarget {
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        throw "unsupported operating system; install.ps1 supports Windows x64 only"
    }
    $architecture = if ($env:PROCESSOR_ARCHITEW6432) {
        $env:PROCESSOR_ARCHITEW6432
    } else {
        $env:PROCESSOR_ARCHITECTURE
    }
    if ($architecture -ne "AMD64") {
        throw "unsupported Windows architecture '$architecture'; Windows x64 is required"
    }
    return "windows-x64"
}

function Receive-GraphMdFile([string] $Uri, [string] $OutFile) {
    Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $OutFile
}

function Get-GraphMdChecksumEntry(
    [string] $ChecksumFile,
    [string] $Target,
    [string] $ReleaseVersion
) {
    if ($ReleaseVersion -eq "latest") {
        $assetPattern = "^graphmd-$([regex]::Escape($Target))-\d+\.\d+\.\d+\.zip$"
    } else {
        $plainVersion = $ReleaseVersion.Substring(1)
        $assetPattern = "^$([regex]::Escape("graphmd-$Target-$plainVersion.zip"))$"
    }

    $entries = @(
        Get-Content -LiteralPath $ChecksumFile | ForEach-Object {
            if ($_ -match '^([0-9A-Fa-f]{64})  (.+)$') {
                $checksum = $Matches[1]
                $asset = $Matches[2]
                if ($asset -match $assetPattern) {
                    [pscustomobject]@{
                        Checksum = $checksum.ToLowerInvariant()
                        Asset = $asset
                    }
                }
            }
        }
    )
    if ($entries.Count -ne 1) {
        throw "SHA256SUMS must contain exactly one checksum for graphmd-$Target"
    }
    return $entries[0]
}

function Invoke-GraphMdInstaller([string[]] $Arguments) {
    $version = if ($env:GRAPHMD_VERSION) { $env:GRAPHMD_VERSION } else { "latest" }
    $installDirectory = if ($env:GRAPHMD_INSTALL_DIR) {
        $env:GRAPHMD_INSTALL_DIR
    } elseif ($env:LOCALAPPDATA) {
        Join-Path $env:LOCALAPPDATA "Programs\GraphMD\bin"
    } else {
        throw "LOCALAPPDATA is not set; use --install-dir or GRAPHMD_INSTALL_DIR"
    }

    $argumentList = @($Arguments)
    for ($index = 0; $index -lt $argumentList.Count; $index++) {
        $argument = $argumentList[$index]
        switch -Regex ($argument) {
            '^--version$' {
                if (++$index -ge $argumentList.Count) { throw "--version requires a value" }
                $version = $argumentList[$index]
                continue
            }
            '^--version=(.*)$' {
                $version = $Matches[1]
                continue
            }
            '^--install-dir$' {
                if (++$index -ge $argumentList.Count) { throw "--install-dir requires a value" }
                $installDirectory = $argumentList[$index]
                continue
            }
            '^--install-dir=(.*)$' {
                $installDirectory = $Matches[1]
                continue
            }
            '^(-h|--help)$' {
                Show-GraphMdInstallerUsage
                return
            }
            default {
                throw "unknown argument '$argument' (use --help for usage)"
            }
        }
    }

    if ([string]::IsNullOrWhiteSpace($installDirectory)) {
        throw "install directory must not be empty"
    }
    $releaseVersion = Get-GraphMdNormalizedVersion $version
    $target = Get-GraphMdTarget
    $downloadRoot = if ($releaseVersion -eq "latest") {
        "$GraphMdReleasesUrl/latest/download"
    } else {
        "$GraphMdReleasesUrl/download/$releaseVersion"
    }

    $temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ("graphmd-install-" + [guid]::NewGuid())
    $stagedBinary = $null
    New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
    try {
        $checksumFile = Join-Path $temporaryDirectory "SHA256SUMS"
        $checksumUrl = "$downloadRoot/SHA256SUMS"
        try {
            Receive-GraphMdFile $checksumUrl $checksumFile
        } catch {
            throw "failed to download required checksum file from $checksumUrl ($($_.Exception.Message)); check network access and ensure the release publishes SHA256SUMS (v0.0.7 and earlier do not)"
        }

        $entry = Get-GraphMdChecksumEntry $checksumFile $target $releaseVersion
        $archive = Join-Path $temporaryDirectory $entry.Asset
        try {
            Receive-GraphMdFile "$downloadRoot/$($entry.Asset)" $archive
        } catch {
            throw "failed to download $($entry.Asset)"
        }

        $actualChecksum = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
        if ($actualChecksum -ne $entry.Checksum) {
            throw "checksum mismatch for $($entry.Asset)"
        }

        $extractedDirectory = Join-Path $temporaryDirectory "extracted"
        Expand-Archive -LiteralPath $archive -DestinationPath $extractedDirectory
        $extractedBinary = Join-Path $extractedDirectory "graphmd.exe"
        if (-not (Test-Path -LiteralPath $extractedBinary -PathType Leaf)) {
            throw "$($entry.Asset) does not contain graphmd.exe"
        }

        New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null
        $stagedBinary = Join-Path $installDirectory (".graphmd.install." + [guid]::NewGuid() + ".exe")
        Copy-Item -LiteralPath $extractedBinary -Destination $stagedBinary
        $installedBinary = Join-Path $installDirectory "graphmd.exe"
        Move-Item -Force -LiteralPath $stagedBinary -Destination $installedBinary
        $stagedBinary = $null

        Write-Output "Installed GraphMD ($($entry.Asset)) to $installedBinary"
        $pathEntries = @($env:PATH -split ';' | ForEach-Object { $_.TrimEnd('\') })
        if ($pathEntries -notcontains $installDirectory.TrimEnd('\')) {
            Write-Output "Add $installDirectory to your user PATH to run graphmd from any directory."
        }
    } finally {
        if ($stagedBinary -and (Test-Path -LiteralPath $stagedBinary)) {
            Remove-Item -Force -LiteralPath $stagedBinary
        }
        if (Test-Path -LiteralPath $temporaryDirectory) {
            Remove-Item -Recurse -Force -LiteralPath $temporaryDirectory
        }
    }
}

try {
    Invoke-GraphMdInstaller $GraphMdInstallerArguments
} catch {
    throw "graphmd installer: $($_.Exception.Message)"
}
} $(if ($MyInvocation.MyCommand.CommandType -eq [System.Management.Automation.CommandTypes]::ExternalScript) { ,$args } else { ,@() })
