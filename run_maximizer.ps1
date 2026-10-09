param (
    [string]$Action = "start"
)

$ValidActions = @("start", "stop", "status")
if ($Action -notin $ValidActions) {
    Write-Host "Usage: .\run_maximizer.ps1 [start|stop|status]" -ForegroundColor Yellow
    exit 1
}

Write-Host "Executing iQOO Hardware Maximizer: $Action..." -ForegroundColor Cyan
python "$PSScriptRoot\iqoo_telemetry_maximizer.py" $Action
