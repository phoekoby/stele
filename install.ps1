#requires -Version 5
# Build Stele and put `stele` on your PATH. Run from the repo root:  .\install.ps1
$ErrorActionPreference = 'Stop'
$repo = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }

if (-not (Test-Path (Join-Path $repo 'gradlew.bat'))) {
    throw "Run this from the Stele repo root (gradlew.bat not found)."
}

$hasJavaHome = $env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))
if (-not $hasJavaHome -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Need a JDK 17+ - set JAVA_HOME or put java on PATH, then re-run."
}

Write-Host "Building Stele (one-time, ~1 min)..." -ForegroundColor Cyan
& (Join-Path $repo 'gradlew.bat') :cli:installDist --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed." }

$bin = Join-Path $repo 'cli\build\install\stele\bin'
if (-not (Test-Path (Join-Path $bin 'stele.bat'))) { throw "Build did not produce stele.bat." }

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $bin) {
    [Environment]::SetEnvironmentVariable('Path', ($userPath.TrimEnd(';') + ';' + $bin), 'User')
    Write-Host "Added to your PATH (takes effect in new terminals): $bin" -ForegroundColor Green
} else {
    Write-Host "Already on PATH: $bin"
}
$env:Path = "$bin;$env:Path"   # enable in this session too

Write-Host "`nstele is ready:" -ForegroundColor Green
& "$bin\stele.bat" --help | Select-Object -First 1
Write-Host @"

Next steps (in the repo you want to work in):
  cd <your repo>
  stele init
  stele ingest symbols .
  stele ingest docs .
  claude mcp add stele -- "$bin\stele.bat" mcp
  stele usage
"@ -ForegroundColor Gray
