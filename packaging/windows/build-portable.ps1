[CmdletBinding()]
param([switch]$PackageOnly, [string]$ImageDirectory)
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if ($env:OS -ne 'Windows_NT') { throw 'Build the Windows distribution on Windows.' }
$packageVersion = if ($env:CLAPCTRL_VERSION) { $env:CLAPCTRL_VERSION } else { '0.1.2' }
if ($packageVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'CLAPCTRL_VERSION must be x.y.z' }
$env:GRADLE_USER_HOME = Join-Path $repositoryRoot '.gradle-user-home'
if (-not $PackageOnly) {
    $javaExecutable = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
    $wrapperJar = Join-Path $repositoryRoot 'gradle/wrapper/gradle-wrapper.jar'
    & $javaExecutable '-classpath' $wrapperJar 'org.gradle.wrapper.GradleWrapperMain' '-p' $repositoryRoot '--no-daemon' 'test' ':app-desktop:createDistributable'
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
}
$applicationImage = if ($ImageDirectory) { (Resolve-Path -LiteralPath $ImageDirectory).Path } else { Join-Path $repositoryRoot 'app-desktop/build/compose/binaries/main/app/ClapCtrl' }
if (-not (Test-Path -LiteralPath (Join-Path $applicationImage 'ClapCtrl.exe') -PathType Leaf)) { throw "Missing Windows application image: $applicationImage" }
$outputDirectory = Join-Path $repositoryRoot 'packaging/dist'
[void][System.IO.Directory]::CreateDirectory($outputDirectory)
$architecture = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x86_64' }
$archivePath = Join-Path $outputDirectory "clapctrl-$packageVersion-windows-$architecture.zip"
# ZipFile includes hidden runtime files that Compress-Archive can omit.
if (Test-Path -LiteralPath $archivePath) { Remove-Item -LiteralPath $archivePath }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($applicationImage, $archivePath, [System.IO.Compression.CompressionLevel]::Optimal, $true)
$checksum = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText("$archivePath.sha256", "$checksum  $([System.IO.Path]::GetFileName($archivePath))`n", [System.Text.Encoding]::ASCII)
Write-Output "Windows portable image: $archivePath"
Write-Output 'Extract the complete archive and run ClapCtrl/ClapCtrl.exe.'
