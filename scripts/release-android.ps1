$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $env:LOCALAPPDATA "MC Monitor\release-signing"
$keystorePath = Join-Path $signingDirectory "mcmonitor-release.jks"
$credentialPath = Join-Path $signingDirectory "credentials.dpapi.json"

if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
    throw "Android release keystore is missing from the local signing directory."
}
if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
    throw "DPAPI-protected Android signing credentials are missing."
}

$credentials = Get-Content -LiteralPath $credentialPath -Raw | ConvertFrom-Json
$invalidCredentials = @(
    $credentials.format -ne "mc-monitor-android-signing-v1"
    $credentials.keystore -ne $keystorePath
    [string]::IsNullOrWhiteSpace($credentials.alias)
    [string]::IsNullOrWhiteSpace($credentials.storePassword)
    [string]::IsNullOrWhiteSpace($credentials.keyPassword)
) -contains $true
if ($invalidCredentials) {
    throw "Android signing credentials have an unsupported or invalid format."
}

function ConvertTo-PlainText([string]$EncryptedValue) {
    $secureValue = ConvertTo-SecureString $EncryptedValue
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

$storePassword = ConvertTo-PlainText $credentials.storePassword
$keyPassword = ConvertTo-PlainText $credentials.keyPassword
$env:ANDROID_KEYSTORE_PATH = $keystorePath
$env:ANDROID_KEYSTORE_PASSWORD = $storePassword
$env:ANDROID_KEY_ALIAS = $credentials.alias
$env:ANDROID_KEY_PASSWORD = $keyPassword

try {
    Push-Location $repoRoot
    & .\gradlew.bat :app:assembleRelease :app:lint
    if ($LASTEXITCODE -ne 0) {
        throw "Android release build or lint failed with exit code $LASTEXITCODE."
    }

    $gradleFile = Join-Path $repoRoot "app\build.gradle"
    $versionMatch = [regex]::Match(
        (Get-Content -LiteralPath $gradleFile -Raw),
        'versionName\s+"([^"]+)"'
    )
    if (-not $versionMatch.Success) {
        throw "Could not read Android versionName from app/build.gradle."
    }

    $releaseDirectory = Join-Path $repoRoot "app\build\outputs\apk\release"
    $sourceApk = Join-Path $releaseDirectory "app-release.apk"
    if (-not (Test-Path -LiteralPath $sourceApk -PathType Leaf)) {
        throw "Gradle did not produce the signed release APK."
    }
    $versionedApk = Join-Path $releaseDirectory (
        "MC-Monitor-Android-v" + $versionMatch.Groups[1].Value + ".apk"
    )
    Copy-Item -LiteralPath $sourceApk -Destination $versionedApk -Force
    Write-Output ("Signed APK ready: " + $versionedApk)
} finally {
    if ((Get-Location).Path -eq $repoRoot) {
        Pop-Location
    }
    Remove-Item Env:\ANDROID_KEYSTORE_PATH -ErrorAction SilentlyContinue
    Remove-Item Env:\ANDROID_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:\ANDROID_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:\ANDROID_KEY_PASSWORD -ErrorAction SilentlyContinue
    $storePassword = $null
    $keyPassword = $null
}
