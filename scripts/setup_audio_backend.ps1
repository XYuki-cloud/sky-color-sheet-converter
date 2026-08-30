$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$AudioVenv = Join-Path $ProjectRoot ".audio-venv"
$AudioPython = Join-Path $AudioVenv "Scripts\python.exe"
$Requirements = @(
    (Join-Path $ProjectRoot "requirements-audio.txt"),
    (Join-Path $ProjectRoot "requirements-audio-optional.txt"),
    (Join-Path $ProjectRoot "requirements-audio-separation.txt")
)

function New-AudioEnvironment {
    if (Test-Path -LiteralPath $AudioPython -PathType Leaf) {
        return
    }

    $PyLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($null -ne $PyLauncher) {
        Write-Host "Prefer py -3.12, then fall back to py -3.10."
        foreach ($Version in @("-3.12", "-3.10")) {
            & $PyLauncher.Source $Version -m venv $AudioVenv
            if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $AudioPython -PathType Leaf)) {
                return
            }
            Write-Warning "Could not create the audio environment with py $Version; trying the next Python version."
        }
    }

    foreach ($CommandName in @("python", "python3")) {
        $Command = Get-Command $CommandName -ErrorAction SilentlyContinue
        if ($null -eq $Command) {
            continue
        }
        & $Command.Source -m venv $AudioVenv
        if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $AudioPython -PathType Leaf)) {
            return
        }
    }

    throw "No compatible Python 3.12 or 3.10 was found; install Python and make the py launcher available."
}

function Install-Requirements {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Requirements file not found: $Path"
    }
    Write-Host "Installing requirements: $Path"
    & $AudioPython -m pip install -r $Path
    if ($LASTEXITCODE -ne 0) {
        throw "Requirements installation failed: $Path"
    }
}

New-AudioEnvironment
Write-Host "Upgrading pip in the audio environment: $AudioPython"
& $AudioPython -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) {
    throw "Could not upgrade pip in the audio environment: $AudioPython"
}

foreach ($Requirement in $Requirements) {
    Install-Requirements $Requirement
}

$Ffmpeg = Get-Command ffmpeg -ErrorAction SilentlyContinue
if ($null -eq $Ffmpeg) {
    Write-Warning "ffmpeg was not found; video input and audio extraction are unavailable until it is added to PATH."
}
else {
    Write-Host "Found ffmpeg: $($Ffmpeg.Source)"
}

$TsumugiRepo = Join-Path $ProjectRoot "vendor\tsumugi"
$TsumugiCheckpoint = Join-Path $TsumugiRepo "checkpoints\best_model_guitar_v1_5.pth"
$TsumugiInfer = Join-Path $TsumugiRepo "infer.py"
if (Test-Path -LiteralPath $TsumugiInfer -PathType Leaf) {
    Write-Host "Found Tsumugi source: $TsumugiRepo"
    $Uv = Get-Command uv -ErrorAction SilentlyContinue
    if ($null -eq $Uv) {
        Write-Warning "uv was not found; Tsumugi dependencies will not be synced automatically. Install uv and run uv sync --locked in vendor\tsumugi."
    }
    else {
        Push-Location $TsumugiRepo
        try {
            & $Uv.Source sync --locked
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "Tsumugi uv sync --locked failed; inspect the log and follow docs\RUNBOOK.md."
            }
        }
        finally {
            Pop-Location
        }
    }
    if (Test-Path -LiteralPath $TsumugiCheckpoint -PathType Leaf) {
        Write-Host "Found the Tsumugi guitar_v1_5 checkpoint."
    }
    else {
        Write-Warning "Tsumugi checkpoint is missing: $TsumugiCheckpoint; follow docs\RUNBOOK.md."
    }
}
else {
    Write-Warning "Tsumugi submodule is missing: $TsumugiRepo; run git submodule update --init --recursive."
}

Write-Host "Audio environment is ready: $AudioPython"
