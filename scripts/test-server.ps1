<#
.SYNOPSIS
  Runs a real Troupe Server (termul-server in Docker) for Troupe's integration test.

.DESCRIPTION
  up       Start the server on http://127.0.0.1:18080 with the fixed test token and wait for /health.
  down     Stop it and delete its volume.
  reverse  Let the LDPlayer emulator reach it at http://127.0.0.1:18080 (adb reverse; adb from TROUPE_ADB, else PATH).
  token    Print the test token (for pairing by hand on the emulator).

  The token below is test-only and also lives in RealServerTest. Never reuse it for a real server.
#>
param([Parameter(Mandatory = $true)][ValidateSet('up', 'down', 'reverse', 'token')][string]$Command)

# docker writes progress to stderr, which Windows PowerShell 5.1 turns into errors under 'Stop'.
$ErrorActionPreference = 'Continue'
$TestToken = '7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c7e57700c'
$Root = Split-Path -Parent $PSScriptRoot
$Compose = Join-Path $Root 'integration\docker-compose.yml'
# TROUPE_ADB picks an adb (for example LDPlayer's own); otherwise the one on PATH.
$Adb = if ($env:TROUPE_ADB) { $env:TROUPE_ADB } else { 'adb' }
$env:TROUPE_TEST_TOKEN = $TestToken

switch ($Command) {
    'up' {
        New-Item -ItemType Directory -Force (Join-Path $Root 'integration\workspace') | Out-Null
        docker compose -f $Compose up -d
        if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
        foreach ($i in 1..30) {
            try {
                $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/health' -TimeoutSec 2 -ErrorAction Stop
                Write-Output "Troupe Server is up: status=$($health.status) allowRemoteWrites=$($health.allowRemoteWrites)"
                return
            } catch { Start-Sleep -Seconds 1 }
        }
        throw 'The server did not answer /health within 30 s. Check: docker compose -f integration\docker-compose.yml logs'
    }
    'down' {
        docker compose -f $Compose down -v
        if ($LASTEXITCODE -ne 0) { throw 'docker compose down failed' }
    }
    'reverse' {
        & $Adb reverse tcp:18080 tcp:18080
        if ($LASTEXITCODE -ne 0) { throw "adb reverse failed (adb: $Adb)" }
    }
    'token' {
        Write-Output $TestToken
    }
}
