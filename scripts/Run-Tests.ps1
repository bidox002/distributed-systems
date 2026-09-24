$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot

$javaBin = 'C:\Program Files\Java\jdk-27\bin'
if (-not (Test-Path -LiteralPath (Join-Path $javaBin 'javac.exe'))) {
    $compiler = Get-Command javac.exe -ErrorAction Stop
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $javac = $compiler.Source
} else {
    $javac = Join-Path $javaBin 'javac.exe'
    $java = Join-Path $javaBin 'java.exe'
}

$outputPath = Join-Path $projectRoot 'out-test'
$sourceFiles = @(
    Get-ChildItem (Join-Path $projectRoot 'src\main\java') -Recurse -Filter '*.java' |
        ForEach-Object FullName
    Get-ChildItem (Join-Path $projectRoot 'src\test\java') -Recurse -Filter '*.java' |
        ForEach-Object FullName
)

New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
& $javac --release 11 -encoding UTF-8 -d $outputPath $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "Compilation failed with exit code $LASTEXITCODE."
}

$testLogDirectory = Join-Path $projectRoot 'logs\tests'
New-Item -ItemType Directory -Path $testLogDirectory -Force | Out-Null
$testLogPath = Join-Path $testLogDirectory 'test-output.txt'
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $testOutput = & $java -cp $outputPath ProjectTests 2>&1
    $testExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$testOutput | Tee-Object -FilePath $testLogPath
if ($testExitCode -ne 0) {
    throw "Automated tests failed with exit code $testExitCode. See $testLogPath."
}

Write-Output "Test output saved to $testLogPath"
