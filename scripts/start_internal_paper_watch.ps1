[CmdletBinding()]
param(
    [string]$SignalDate = '',
    [string]$SessionId = 'analysis-paper',
    [int]$MaxCycles = 0,
    [ValidateSet('SHADOW', 'INTERNAL_PAPER', 'EXTERNAL_PAPER')]
    [string]$ExecutionMode = 'SHADOW',
    [ValidateSet('T1', 'WATCHLIST', 'FULL')]
    [string]$UniverseMode = 'T1',
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

if ($ExecutionMode -eq 'EXTERNAL_PAPER' -and -not $env:MX_APIKEY) {
    $env:MX_APIKEY = [Environment]::GetEnvironmentVariable('MX_APIKEY', 'User')
}
if ($ExecutionMode -eq 'EXTERNAL_PAPER' -and -not $env:MX_APIKEY) {
    throw 'EXTERNAL_PAPER 未找到 MX_APIKEY；禁止启动外部模拟盘。'
}

# 真实行情/策略分析：常规扫描走公开批量行情；只有 EXTERNAL_PAPER
# 且信号通过风控后才会调用妙想 mockTrading/trade。
$env:PAPER_WATCH_EXECUTION_MODE = $ExecutionMode
$paperExecution = $ExecutionMode -ne 'SHADOW'
$env:PAPER_WATCH_LIVE = if ($paperExecution) { '1' } else { '0' }
$env:PAPER_WATCH_UNIVERSE_MODE = $UniverseMode
$env:PAPER_CANDIDATE_FILE = ''
$env:PAPER_WATCHLIST_FILE = ''
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
# D-CAL-02（2026-10-13 修正）：此前只有显式传 -TradingCalendarFile 才会注入日历，
# 而 INTERNAL_PAPER 不做强制校验，于是默认走 trading_calendar.py 的「周一至周五」回退——
# 国庆/中秋等法定节假日会被当作交易日。仓库已自带日历文件，这里默认自动注入；
# 目标年份文件不存在时保持原回退行为并给出明确告警，不静默假定。
if (-not $env:PAPER_TRADING_CALENDAR_FILE) {
    $calendarYear = if ($SignalDate) { ([datetime]$SignalDate).Year } else { (Get-Date).Year }
    $defaultCalendar = Join-Path $computeRoot "paper/trading-calendar.$calendarYear.json"
    if (Test-Path $defaultCalendar) {
        $env:PAPER_TRADING_CALENDAR_FILE = (Resolve-Path $defaultCalendar).Path
        Write-Host "[calendar] 自动注入仓库日历: $($env:PAPER_TRADING_CALENDAR_FILE)"
    } else {
        Write-Warning "[calendar] 未找到 paper/trading-calendar.$calendarYear.json，将退化为「周一至周五」判定，法定节假日会被当作交易日；请用 -TradingCalendarFile 显式注入。"
    }
}
if ($UniverseMode -eq 'T1' -and -not $SignalDate) {
    if (-not $env:PAPER_TRADING_CALENDAR_FILE) {
        throw 'T1 模式自动选择信号日需要正式日历；请提供 -TradingCalendarFile 和上一交易日 -SignalDate。'
    }
    $calendar = Get-Content -LiteralPath $env:PAPER_TRADING_CALENDAR_FILE -Raw -Encoding UTF8 | ConvertFrom-Json
    $today = (Get-Date).Date
    for ($offset = 1; $offset -le 31; $offset++) {
        $day = $today.AddDays(-$offset)
        if ($day.Year -ne $today.Year) {
            throw '跨年信号日需要上一年度正式日历，请显式提供 -SignalDate 和适用的 -TradingCalendarFile。'
        }
        $key = $day.ToString('yyyy-MM-dd')
        $open = ($calendar.extra_workdays -contains $key) -or (
            $day.DayOfWeek -ne [DayOfWeek]::Saturday -and $day.DayOfWeek -ne [DayOfWeek]::Sunday -and
            $calendar.holidays -notcontains $key)
        if ($open) { $SignalDate = $key; break }
    }
    if (-not $SignalDate) { throw '正式日历中未找到上一交易日。' }
}
if (-not $SignalDate) { $SignalDate = Get-Date -Format 'yyyy-MM-dd' }
if ($ExecutionMode -eq 'EXTERNAL_PAPER' -and -not $env:PAPER_TRADING_CALENDAR_FILE) {
    throw 'EXTERNAL_PAPER 必须通过 -TradingCalendarFile 或 PAPER_TRADING_CALENDAR_FILE 注入正式交易日历。'
}

$python = (Get-Command python.exe -ErrorAction Stop).Source
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logMode = $ExecutionMode.ToLowerInvariant().Replace('_', '-')
$stdoutPath = Join-Path $watchLogRoot "$logMode-watch-$stamp.stdout.log"
$stderrPath = Join-Path $watchLogRoot "$logMode-watch-$stamp.stderr.log"
$arguments = @(
    '-u', '-m', 'paper.cli', 'watch',
    '--signal-date', $SignalDate,
    '--max-cycles', [string]$MaxCycles
)
if ($paperExecution) { $arguments += @('--live', '--confirm') }

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
    ExecutionMode = $env:PAPER_WATCH_EXECUTION_MODE
    Live = $env:PAPER_WATCH_LIVE
    Confirm = $paperExecution
    SignalDate = $SignalDate
    Universe = $env:PAPER_WATCH_UNIVERSE_MODE
    SessionId = $env:PAPER_SESSION_ID
    AccountScope = "$($env:PAPER_WATCH_EXECUTION_MODE):$($env:PAPER_SESSION_ID)"
    Store = $storePath
    DataSource = 'quote=eastmoney_batch;kline=auto'
    ExternalAccountConfigured = [bool]($ExecutionMode -eq 'EXTERNAL_PAPER' -and $env:MX_APIKEY)
    Stdout = $stdoutPath
    Stderr = $stderrPath
}
