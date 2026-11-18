param(
    [string]$AndroidSdkPath = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { '' }),
    [string]$NdkVersion = '28.2.13676358',
    [int]$AndroidApi = 26,
    [string]$BuildRoot = 'D:\ort-1232',
    [string]$PythonExe = 'python'
)

$ErrorActionPreference = 'Stop'

$ExpectedOrtVersion = '1.23.2'
$ExpectedOrtTag = "v$ExpectedOrtVersion"
$ExpectedOrtCommit = 'a83fc4d58cb48eb68890dd689f94f28288cf2278'
$GitRepositories = @(
    'https://github.com/microsoft/onnxruntime.git',
    'https://github.com/arcusbuilds/onnxruntime.git'
)

if ([string]::IsNullOrWhiteSpace($AndroidSdkPath) -or -not (Test-Path -LiteralPath $AndroidSdkPath)) {
    throw "Android SDK path was not found. Pass -AndroidSdkPath or set ANDROID_HOME."
}

$ndkPath = Join-Path $AndroidSdkPath "ndk\$NdkVersion"
if (-not (Test-Path -LiteralPath $ndkPath)) {
    throw "Android NDK $NdkVersion was not found under $AndroidSdkPath."
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$opConfig = Join-Path $projectRoot 'app\src\moonshine\ort\moonshine-required-operators.config'
$outputSo = Join-Path $projectRoot 'app\src\moonshine\ort\jni\arm64-v8a\libonnxruntime.so'
if (-not (Test-Path -LiteralPath $opConfig)) {
    throw "Missing pinned operator config: $opConfig"
}

$cmakeCandidates = Get-ChildItem (Join-Path $AndroidSdkPath 'cmake') -Directory -ErrorAction SilentlyContinue |
    Where-Object { [version]($_.Name -replace '[^0-9.].*$', '') -ge [version]'3.28.0' } |
    Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending
$selectedCmakeDir = $cmakeCandidates | Select-Object -First 1
if ($null -eq $selectedCmakeDir) {
    throw "CMake >= 3.28 is required under $(Join-Path $AndroidSdkPath 'cmake') to build ORT $ExpectedOrtVersion."
}
$cmakeBinDir = Join-Path $selectedCmakeDir.FullName 'bin'
$cmakeExe = Join-Path $cmakeBinDir 'cmake.exe'
$ctestExe = Join-Path $cmakeBinDir 'ctest.exe'
$ninjaExe = Join-Path $cmakeBinDir 'ninja.exe'
foreach ($tool in @($cmakeExe, $ctestExe, $ninjaExe)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required build tool missing: $tool"
    }
}

$ndkBinDir = Join-Path $ndkPath 'toolchains\llvm\prebuilt\windows-x86_64\bin'
$stripExe = Join-Path $ndkBinDir 'llvm-strip.exe'
$readelfExe = Join-Path $ndkBinDir 'llvm-readelf.exe'
$objdumpExe = Join-Path $ndkBinDir 'llvm-objdump.exe'
foreach ($tool in @($stripExe, $readelfExe, $objdumpExe)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required NDK LLVM tool missing: $tool"
    }
}

& $PythonExe -c "import flatbuffers" 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "Python executable '$PythonExe' lacks the 'flatbuffers' module needed by --include_ops_by_config."
}

New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
$srcDir = Join-Path $BuildRoot 'src'
$buildDir = Join-Path $BuildRoot 'b'

$env:GIT_TERMINAL_PROMPT = '0'
$env:GIT_CONFIG_COUNT = '1'
$env:GIT_CONFIG_KEY_0 = 'safe.directory'
$env:GIT_CONFIG_VALUE_0 = '*'

if (-not (Test-Path -LiteralPath (Join-Path $srcDir '.git'))) {
    $cloned = $false
    foreach ($repo in $GitRepositories) {
        if (Test-Path -LiteralPath $srcDir) {
            Remove-Item -LiteralPath $srcDir -Recurse -Force
        }
        Write-Host "Cloning ORT $ExpectedOrtTag from $repo ..."
        $prevPref = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        git -c credential.helper= clone --depth 1 --branch $ExpectedOrtTag $repo $srcDir
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $prevPref
        if ($exitCode -eq 0) {
            $cloned = $true
            break
        }
    }
    if (-not $cloned) {
        throw "Failed to clone ORT $ExpectedOrtTag from any configured remote."
    }
}

$actualCommit = (git -C $srcDir rev-parse HEAD).Trim()
$actualVersion = (Get-Content (Join-Path $srcDir 'VERSION_NUMBER') -Raw).Trim()
if ($actualCommit -ne $ExpectedOrtCommit -or $actualVersion -ne $ExpectedOrtVersion) {
    throw "Unexpected ORT checkout ($actualCommit / $actualVersion); expected $ExpectedOrtCommit / $ExpectedOrtVersion."
}

if (Test-Path -LiteralPath $buildDir) {
    # Clearing ensures no stale CMakeCache flags (especially onnxruntime_USE_KLEIDIAI) persist.
    Remove-Item -LiteralPath $buildDir -Recurse -Force
}

$env:PATH = "$cmakeBinDir;$env:PATH"
$env:CMAKE_POLICY_VERSION_MINIMUM = '3.5'
$env:ANDROID_HOME = $AndroidSdkPath
$env:ANDROID_SDK_ROOT = $AndroidSdkPath
$env:ANDROID_NDK_HOME = $ndkPath

$buildPy = Join-Path $srcDir 'tools\ci_build\build.py'
$prevPref = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $PythonExe $buildPy `
    --build_dir $buildDir `
    --config Release `
    --cmake_generator Ninja `
    --cmake_path $cmakeExe `
    --ctest_path $ctestExe `
    --skip_submodule_sync `
    --android `
    --android_sdk_path $AndroidSdkPath `
    --android_ndk_path $ndkPath `
    --android_abi arm64-v8a `
    --android_api $AndroidApi `
    --build_shared_lib `
    --minimal_build extended custom_ops `
    --include_ops_by_config $opConfig `
    --disable_ml_ops `
    --no_kleidiai `
    --compile_no_warning_as_error `
    --cmake_extra_defines CMAKE_POLICY_VERSION_MINIMUM=3.5 ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON `
    --skip_tests `
    --parallel
$buildExit = $LASTEXITCODE
$ErrorActionPreference = $prevPref
if ($buildExit -ne 0) {
    throw "ORT build failed with exit code $buildExit"
}

$unstrippedSo = Join-Path $buildDir 'Release\libonnxruntime.so'
if (-not (Test-Path -LiteralPath $unstrippedSo)) {
    throw "Expected output library missing: $unstrippedSo"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputSo) | Out-Null
& $stripExe --strip-unneeded $unstrippedSo -o $outputSo
if ($LASTEXITCODE -ne 0) {
    throw "llvm-strip failed with exit code $LASTEXITCODE"
}

$dynSyms = & $readelfExe --dyn-syms --wide $outputSo
if (-not ($dynSyms -match 'OrtGetApiBase@@VERS_1\.23\.2') -or
    -not ($dynSyms -match 'OrtSessionOptionsAppendExecutionProvider_CPU@@VERS_1\.23\.2')) {
    throw "Rebuilt libonnxruntime.so is missing required VERS_1.23.2 exports."
}

$dynHeaders = & $readelfExe -d $outputSo
if ($dynHeaders -match 'libc\+\+_shared\.so') {
    throw "Rebuilt libonnxruntime.so unexpectedly depends on libc++_shared.so."
}

$progHeaders = & $readelfExe -l $outputSo | Where-Object { $_ -match '^\s*LOAD\s+' }
if ($progHeaders.Count -eq 0 -or ($progHeaders | Where-Object { $_ -notmatch '\s0x4000$' })) {
    throw "Rebuilt libonnxruntime.so lacks 16 KB (0x4000) PT_LOAD alignment."
}

$smeHits = & $objdumpExe -d --mattr=+sme,+sme2 $outputSo |
    Select-String -Pattern '\b(smstart|smstop|rdsvl|psel|fmopa|bfmopa|smopa|umopa|sumopa|usmopa)\b'
if ($smeHits.Count -gt 0) {
    throw "Rebuilt libonnxruntime.so still contains SME/SME2 instructions."
}

$sha = (Get-FileHash -Path $outputSo -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Successfully built and verified KleidiAI-free libonnxruntime.so"
Write-Host "Output: $outputSo ($((Get-Item $outputSo).Length) bytes)"
Write-Host "SHA-256: $sha"
