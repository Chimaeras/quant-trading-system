[CmdletBinding()]
param(
    [string]$SignalDate = (Get-Date -Format 'yyyy-MM-dd'),
    [Parameter(Mandatory = $true)]
    [string]$TradingCalendarFile,
    [string]$SessionId = 'analysis-external-paper',
    [int]$MaxCycles = 0
)

$ErrorActionPreference = 'Stop'
$calendarPath = (Resolve-Path -LiteralPath $TradingCalendarFile -ErrorAction Stop).Path
$launcher = Join-Path $PSScriptRoot 'start_internal_paper_watch.ps1'

& $launcher `
    -SignalDate $SignalDate `
    -SessionId $SessionId `
    -MaxCycles $MaxCycles `
    -ExecutionMode 'EXTERNAL_PAPER' `
    -TradingCalendarFile $calendarPath
