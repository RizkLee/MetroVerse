param(
    [string]$KeystorePath = 'app/keystore/metroverse-release.keystore',
    [string]$PropertiesPath = 'keystore.properties'
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedKeystorePath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $KeystorePath))
$resolvedPropertiesPath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $PropertiesPath))

if (Test-Path -LiteralPath $resolvedKeystorePath) {
    throw "Keystore already exists: $resolvedKeystorePath"
}
if (Test-Path -LiteralPath $resolvedPropertiesPath) {
    throw "Signing properties already exist: $resolvedPropertiesPath"
}

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw 'JAVA_HOME is not set. Point it to a complete JDK 21 installation.'
}
$keytool = Join-Path $javaHome 'bin/keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) {
    throw "keytool.exe was not found under JAVA_HOME: $keytool"
}

$keystoreDirectory = Split-Path -Parent $resolvedKeystorePath
New-Item -ItemType Directory -Path $keystoreDirectory -Force | Out-Null

$passwordBytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
$password = [Convert]::ToHexString($passwordBytes)
$alias = 'metroverse'

$keytoolArgs = @(
    '-genkeypair'
    '-v'
    '-keystore'
    $resolvedKeystorePath
    '-storetype'
    'PKCS12'
    '-storepass'
    $password
    '-alias'
    $alias
    '-keypass'
    $password
    '-keyalg'
    'RSA'
    '-keysize'
    '4096'
    '-validity'
    '10000'
    '-dname'
    'CN=MetroVerse Local Release,O=Rizklee'
)

& $keytool @keytoolArgs
if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE"
}

$relativeStoreFile = [System.IO.Path]::GetRelativePath($projectRoot, $resolvedKeystorePath).Replace('\', '/')
$properties = @(
    "storeFile=$relativeStoreFile"
    "storePassword=$password"
    "keyAlias=$alias"
    "keyPassword=$password"
) -join [Environment]::NewLine

[System.IO.File]::WriteAllText(
    $resolvedPropertiesPath,
    $properties + [Environment]::NewLine,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host 'MetroVerse release signing files created.'
Write-Host "Keystore: $resolvedKeystorePath"
Write-Host "Properties: $resolvedPropertiesPath"
Write-Host 'Back up both files securely. Losing the keystore prevents signed updates to installed release builds.'
