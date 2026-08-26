[CmdletBinding()]
param(
    [string]$SignalDate = (Get-Date -Format 'yyyy-MM-dd'),
    [string]$SessionId = 'analysis-paper',
    [int]$MaxCycles = 0,
    [ValidateSet('INTERNAL_PAPER', 'EXTERNAL_PAPER')]
    [string]$ExecutionMode = 'INTERNAL_PAPER',
    [string]$TradingCalendarFile = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$computeRoot = Join-Path $projectRoot 'modules/compute-python'
$watchLogRoot = Join-Path $computeRoot 'logs/watch'
$storePath = Join-Path $watchLogRoot 'runtime.sqlite3'

$existing = Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -and
    $_.CommandLine -match '-m\s+paper\.cli\s+watch(\s|$)' -and
    $_.CommandLine -notmatch 'watch-serve'
}
if ($existing) {
    $ids = ($existing | Select-Object -ExpandProperty ProcessId) -join ', '
    throw "已有 paper watch 进程运行（PID: $ids），为避免重复撮合，本次不启动。"
}

if (-not $env:MX_APIKEY) {
    $env:MX_APIKEY = [Environment]::GetEnvironmentVariable('MX_APIKEY', 'User')
}
if (-not $env:MX_APIKEY) {
    throw '未找到 MX_APIKEY；真实行情分析需要通过环境变量注入 API Key。'
}

# 真实行情/策略分析：常规扫描走公开批量行情；只有 EXTERNAL_PAPER
# 且信号通过风控后才会调用妙想 mockTrading/trade。
$env:PAPER_WATCH_EXECUTION_MODE = $ExecutionMode
$env:PAPER_WATCH_LIVE = '1'
$env:PAPER_WATCH_UNIVERSE_MODE = 'WATCHLIST'
$env:PAPER_MAX_OBSERVE_CODES = '200'
# 0 表示关闭最大持仓只数限制；资金、总仓位、单票权重等风控仍然生效。
$env:PAPER_MAX_POSITIONS = '0'
$env:PAPER_KLINE_SOURCE = 'auto'
$env:PAPER_KLINE_RETRY_WORKERS = '8'
$env:PAPER_KLINE_RETRY_BATCH_SIZE = '24'
$env:PAPER_KLINE_REQUEST_TIMEOUT_SEC = '8'
$env:PAPER_KLINE_MAX_RETRY = '2'
$env:PAPER_QUOTE_BATCH_WORKERS = '2'
$env:PAPER_QUOTE_REQUEST_TIMEOUT_SEC = '8'
$env:PAPER_QUOTE_BATCH_MAX_RETRY = '2'
$env:PAPER_SESSION_ID = $SessionId
$env:PAPER_INTERNAL_INITIAL_CASH = '1000000'
$env:PAPER_INTERNAL_MATCHING_MODEL = 'NEXT_BAR'
$env:PAPER_WATCH_STORE_PATH = $storePath
$env:PAPER_AUTO_CANCEL_EXPIRED = '1'
if ($TradingCalendarFile) {
    $env:PAPER_TRADING_CALENDAR_FILE = (Resolve-Path $TradingCalendarFile).Path
}
if ($ExecutionMode -eq 'EXTERNAL_PAPER' -and -not $env:PAPER_TRADING_CALENDAR_FILE) {
    throw 'EXTERNAL_PAPER 必须通过 -TradingCalendarFile 或 PAPER_TRADING_CALENDAR_FILE 注入正式交易日历。'
}

$python = (Get-Command python.exe -ErrorAction Stop).Source
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdoutPath = Join-Path $watchLogRoot "internal-paper-watch-$stamp.stdout.log"
$stderrPath = Join-Path $watchLogRoot "internal-paper-watch-$stamp.stderr.log"
$arguments = @(
    '-u', '-m', 'paper.cli', 'watch',
    '--signal-date', $SignalDate,
    '--live', '--confirm',
    '--max-cycles', [string]$MaxCycles
)

$startParams = @{
    FilePath = $python
    ArgumentList = $arguments
    WorkingDirectory = $computeRoot
    WindowStyle = 'Hidden'
    RedirectStandardOutput = $stdoutPath
    RedirectStandardError = $stderrPath
    PassThru = $true
}
$process = Start-Process @startParams

[pscustomobject]@{
    ProcessId = $process.Id
    Mode = $env:PAPER_WATCH_EXECUTION_MODE
    Live = $env:PAPER_WATCH_LIVE
    Universe = $env:PAPER_WATCH_UNIVERSE_MODE
    SessionId = $env:PAPER_SESSION_ID
    Store = $storePath
    Stdout = $stdoutPath
    Stderr = $stderrPath
}
