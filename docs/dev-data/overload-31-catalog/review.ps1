param(
    [Parameter(Mandatory)]
    [ValidateSet('Start', 'Status', 'Stop')]
    [string]$Action,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$frontend = Join-Path (Split-Path $backend) 'overload-pwa'
$runs = Join-Path $backend 'build/verification-v22'
$pointer = Join-Path $backend 'build/approved-catalog-review.txt'
$frontendOrigin = 'http://127.0.0.1:4180'

function Get-ReviewFrontend {
    try { Invoke-RestMethod "$frontendOrigin/__approved_catalog_review" -TimeoutSec 2 }
    catch { $null }
}

function Test-ReviewPage {
    try {
        foreach ($path in '/', '/@vite/client', '/src/main.tsx', '/src/review/ReviewApp.tsx') {
            if ((Invoke-WebRequest -UseBasicParsing "$frontendOrigin$path" -TimeoutSec 4).StatusCode -ne 200) {
                return $false
            }
        }
        return $true
    } catch { return $false }
}

function Wait-ReviewFile($Path, $Process, $Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while (!(Test-Path -LiteralPath $Path) -and (Get-Date) -lt $deadline) {
        if ($null -ne $Process -and $Process.HasExited) { throw "Review process exited before creating $Path" }
        Start-Sleep -Milliseconds 400
    }
    if (!(Test-Path -LiteralPath $Path)) { throw "Timed out waiting for $Path" }
}

function Stop-ReviewBackend($Directory, $RunId) {
    if (Test-Path -LiteralPath (Join-Path $Directory 'result.json')) { return }
    [IO.File]::WriteAllText((Join-Path $Directory 'stop'), $RunId)
    Wait-ReviewFile (Join-Path $Directory 'result.json') $null 30
}

if (Test-Path -LiteralPath $pointer) {
    $current = [guid]::Parse((Get-Content -Raw -LiteralPath $pointer).Trim()).ToString()
    $directory = Join-Path $runs $current
    $readyPath = Join-Path $directory 'ready.json'
    if (Test-Path -LiteralPath $readyPath) {
        $ready = Get-Content -Raw -LiteralPath $readyPath | ConvertFrom-Json
        if ($ready.runId -ne $current -or $ready.approvedCatalogReview.approvedExercises -ne 31) {
            throw 'The current run is not the approved catalog review'
        }
        if ($Action -eq 'Stop') {
            Stop-ReviewBackend $directory $current
            for ($attempt = 0; $attempt -lt 15; $attempt++) {
                if ((Get-ReviewFrontend).runId -ne $current) { break }
                Start-Sleep -Milliseconds 400
            }
            if ((Get-ReviewFrontend).runId -eq $current) { throw 'The review frontend has not stopped' }
            Write-Output "Stopped approved catalog review $current"
            return
        }
        if ((Get-ReviewFrontend).runId -ne $current) {
            throw 'Review backend is running without its matching frontend. Stop the run, then start again.'
        }
        [pscustomobject]@{ Run = $current; Url = $frontendOrigin; Data = $ready.approvedCatalogReview }
        return
    }
}

if ($Action -ne 'Start') {
    Write-Output 'Approved catalog review is stopped.'
    return
}
if ((Get-ReviewFrontend) -or (Get-NetTCPConnection -LocalPort 4180 -State Listen -ErrorAction SilentlyContinue)) {
    throw 'The local review port 4180 is occupied'
}
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to JDK 25 or pass -JavaHome'
}
if (Get-ChildItem Env: | Where-Object { $_.Name -match '^(SPRING_|OTP_|RESEND_|GOOGLE_|AUTH_|OVERLOAD_)' }) {
    throw 'Open a shell without application environment variables'
}
$null = Get-Command node.exe
if (!(Test-Path -LiteralPath (Join-Path $frontend 'node_modules/vite'))) { throw 'Existing PWA dependencies are required' }
$env:JAVA_HOME = $JavaHome
Push-Location $backend
try {
    & .\gradlew.bat --no-daemon -I scripts/home-review.gradle homeReviewClasspath
    if ($LASTEXITCODE -ne 0) { throw 'Review classpath compilation failed' }
} finally { Pop-Location }

$runId = [guid]::NewGuid().ToString()
$directory = Join-Path $runs $runId
$logs = New-Item -ItemType Directory -Path (Join-Path $backend "build/approved-catalog-review-logs/$runId")
$classpath = (Get-Content -Raw -LiteralPath (Join-Path $backend 'build/home-review/classpath.txt')).Trim()
$javaOptions = @{
    FilePath = Join-Path $JavaHome 'bin/java.exe'
    WorkingDirectory = $backend
    ArgumentList = '-cp "{0}" com.overload.testfixture.V22BrowserEnvironment {1} --approved-catalog' -f $classpath, $runId
    WindowStyle = 'Hidden'
    PassThru = $true
    RedirectStandardOutput = Join-Path $logs.FullName 'backend.log'
    RedirectStandardError = Join-Path $logs.FullName 'backend-error.log'
}
$java = Start-Process @javaOptions
try {
    $readyPath = Join-Path $directory 'ready.json'
    Wait-ReviewFile $readyPath $java 90
    $ready = Get-Content -Raw -LiteralPath $readyPath | ConvertFrom-Json
    $nodeOptions = @{
        FilePath = (Get-Command node.exe).Source
        WorkingDirectory = $frontend
        ArgumentList = "scripts/approved-catalog-review.mjs $runId"
        WindowStyle = 'Hidden'
        PassThru = $true
        RedirectStandardOutput = Join-Path $logs.FullName 'frontend.log'
        RedirectStandardError = Join-Path $logs.FullName 'frontend-error.log'
    }
    $node = Start-Process @nodeOptions
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-ReviewFrontend).runId -ne $runId -or !(Test-ReviewPage)) {
        if ($node.HasExited -or (Get-Date) -ge $deadline) { throw 'The approved catalog review frontend did not start' }
        Start-Sleep -Milliseconds 400
    }
    [IO.File]::WriteAllText($pointer, $runId)
    [pscustomobject]@{ Run = $runId; Url = $frontendOrigin; Data = $ready.approvedCatalogReview }
} catch {
    if (Test-Path -LiteralPath $directory) { Stop-ReviewBackend $directory $runId }
    throw
}
