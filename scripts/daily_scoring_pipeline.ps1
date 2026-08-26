#Requires -Version 5.1
<#
.SYNOPSIS
  盘后一条龙：检测/启动后端 → 近7交易日数据更新 → 五维度打分 → Top20 CSV + 汇总。

.DESCRIPTION
  与选股打分工作台 (StockPicker.vue) 默认参数对齐。
  输出目录：output/scoring/{tradeDate}/
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$ScorePoolSize = 500,
    [int]$FocusTopN = 20,
    [int]$TradingDayCount = 7,
    [int]$BackendStartupTimeoutSec = 300,
    [int]$DataUpdateTimeoutSec = 10800,
    [int]$ScoringTaskTimeoutSec = 3600,
    [ValidateSet('MAIN_BOARD')]
    [string]$DataUpdateMode = 'MAIN_BOARD',
    [switch]$SkipDataUpdate,
    [switch]$SkipBackendStart
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$scriptRoot = $PSScriptRoot
$projectRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path
$backendDir = Join-Path $projectRoot 'modules\backend-java'
$outputRoot = Join-Path $projectRoot 'output\scoring'

# ── 日志 ──────────────────────────────────────────────────────────────

$logFile = $null
$startedBackend = $false
$backendLogFile = $null

function Write-Log {
    param(
        [string]$Message,
        [ValidateSet('INFO', 'WARN', 'ERROR', 'OK')]
        [string]$Level = 'INFO'
    )
    $line = "[{0}] [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    switch ($Level) {
        'ERROR' { Write-Host $line -ForegroundColor Red }
        'WARN'  { Write-Host $line -ForegroundColor Yellow }
        'OK'    { Write-Host $line -ForegroundColor Green }
        default { Write-Host $line }
    }
    if ($logFile) {
        Add-Content -Path $logFile -Value $line -Encoding UTF8
    }
}

function Test-TcpPort {
    param([string]$HostName = '127.0.0.1', [int]$Port)
    try {
        return (Test-NetConnection -ComputerName $HostName -Port $Port -WarningAction SilentlyContinue).TcpTestSucceeded
    } catch {
        return $false
    }
}

function Get-LastWeekdayOnOrBefore {
    param([DateTime]$Date)
    $d = $Date.Date
    while ([int]$d.DayOfWeek -eq 0 -or [int]$d.DayOfWeek -eq 6) {
        $d = $d.AddDays(-1)
    }
    return $d
}

function Get-TradingDayWindow {
    param(
        [DateTime]$EndDate,
        [int]$Count
    )
    if ($Count -lt 1) { $Count = 1 }
    $end = Get-LastWeekdayOnOrBefore -Date $EndDate
    $cur = $end
    $remaining = $Count - 1
    while ($remaining -gt 0) {
        $cur = $cur.AddDays(-1)
        if ([int]$cur.DayOfWeek -ne 0 -and [int]$cur.DayOfWeek -ne 6) {
            $remaining--
        }
    }
    return @{
        Start = $cur
        End   = $end
    }
}

function Invoke-QuantApi {
    param(
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [int]$TimeoutSec = 60
    )
    $uri = ($BaseUrl.TrimEnd('/')) + $Path
    $params = @{
        Method      = $Method
        Uri         = $uri
        TimeoutSec  = $TimeoutSec
        ContentType = 'application/json; charset=utf-8'
    }
    if ($Body -ne $null) {
        $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    $raw = Invoke-RestMethod @params
    if ($null -eq $raw) {
        throw "API 无响应: $Method $Path"
    }
    if ($raw.PSObject.Properties.Name -contains 'code') {
        $code = [int]$raw.code
        if ($code -ne 200) {
            $msg = if ($raw.message) { [string]$raw.message } else { "HTTP 业务错误 code=$code" }
            throw "$msg ($Method $Path)"
        }
        return $raw.data
    }
    return $raw
}

function Test-BackendHealthy {
    try {
        $data = Invoke-QuantApi -Method GET -Path '/api/data/overview' -TimeoutSec 10
        return ($null -ne $data)
    } catch {
        return $false
    }
}

function Wait-BackendReady {
    param([int]$TimeoutSec)
    Write-Log '等待后端就绪...'
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-BackendHealthy) {
            Write-Log '后端 API 已就绪' -Level OK
            return
        }
        Start-Sleep -Seconds 5
    }
    throw "后端在 ${TimeoutSec}s 内未就绪，请查看日志: $script:backendLogFile"
}

function Start-QuantBackend {
    if (-not (Test-Path $backendDir)) {
        throw "后端目录不存在: $backendDir"
    }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw '未找到 mvn 命令，请安装 Maven 并加入 PATH'
    }

    $script:backendLogFile = Join-Path $outputRoot 'backend-startup.log'
    New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

    $cmd = 'mvn spring-boot:run "-Dquant.skipFrontendBuild=true" >> "' + $script:backendLogFile + '" 2>&1'
    Write-Log "后台启动 Spring Boot，日志: $script:backendLogFile"
    Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', $cmd) -WorkingDirectory $backendDir -WindowStyle Hidden | Out-Null
    $script:startedBackend = $true
    Wait-BackendReady -TimeoutSec $BackendStartupTimeoutSec
}

function Ensure-Backend {
    if (Test-BackendHealthy) {
        Write-Log '后端已在运行，跳过启动' -Level OK
        return
    }
    if ($SkipBackendStart) {
        throw '后端未运行且指定了 -SkipBackendStart'
    }
    Start-QuantBackend
}

function Ensure-MySql {
    if (Test-TcpPort -Port 3306) {
        Write-Log 'MySQL (3306) 已就绪' -Level OK
        return
    }
    Write-Log 'MySQL 未监听 3306，尝试启动 Windows 服务...' -Level WARN
    $serviceNames = @('MySQL80', 'MySQL', 'MYSQL80', 'mysql')
    foreach ($name in $serviceNames) {
        $svc = Get-Service -Name $name -ErrorAction SilentlyContinue
        if ($svc -and $svc.Status -ne 'Running') {
            Start-Service -Name $name
            Write-Log "已启动服务: $name"
            Start-Sleep -Seconds 5
            break
        }
    }
    if (-not (Test-TcpPort -Port 3306)) {
        throw 'MySQL 不可用。请先启动 MySQL 服务后再运行本脚本。'
    }
    Write-Log 'MySQL 已就绪' -Level OK
}

function Wait-DataUpdateTask {
    param(
        [string]$TaskId,
        [int]$TimeoutSec
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $task = Invoke-QuantApi -Method GET -Path "/api/data/update/tasks/$TaskId" -TimeoutSec 30
        $status = [string]$task.status
        $processed = $task.processed_stocks
        $total = $task.total_stocks
        Write-Log "数据更新 [$TaskId] $status ($processed/$total)"
        $st = $status.ToUpper()
        if ($st -eq 'SUCCESS') { return $task }
        if ($st -eq 'PARTIAL') {
            Write-Log '数据更新部分成功 (PARTIAL)，继续打分流程' -Level WARN
            return $task
        }
        if ($st -eq 'FAILED') { throw "数据更新失败: $($task.message)" }
        if ($st -eq 'STOPPED') { throw "数据更新已停止: $($task.message)" }
        Start-Sleep -Seconds 30
    }
    throw "数据更新超时 (${TimeoutSec}s): $TaskId"
}

function Wait-ScoringTask {
    param(
        [string]$TaskId,
        [int]$TimeoutSec
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $task = Invoke-QuantApi -Method GET -Path "/api/scoring/unified/tasks/$TaskId" -TimeoutSec 30
        $status = [string]$task.status
        $pct = if ($null -ne $task.progress_pct) { $task.progress_pct } else { 0 }
        Write-Log "打分任务 [$TaskId] $status (${pct}%)"
        $st = $status.ToUpper()
        if ($st -eq 'SUCCESS') { return $task }
        if ($st -eq 'FAILED') { throw "打分失败: $($task.message)" }
        if ($st -eq 'CANCELLED') { throw "打分已取消: $($task.message)" }
        Start-Sleep -Seconds 5
    }
    throw "打分任务超时 (${TimeoutSec}s): $TaskId"
}

function Get-ItemScore {
    param([object]$Row)
    $candidates = @('final_score', 'total_score', 'composite_score', 'score')
    foreach ($key in $candidates) {
        if ($Row.PSObject.Properties.Name -contains $key -and $null -ne $Row.$key -and "$($Row.$key)" -ne '') {
            $n = 0.0
            if ([double]::TryParse([string]$Row.$key, [ref]$n)) {
                return $n
            }
        }
    }
    return 0.0
}

function Get-TopFocusRows {
    param(
        [array]$Items,
        [int]$TopN,
        [double]$MinScore = 0
    )
    return @(
        $Items |
            Where-Object { (Get-ItemScore $_) -ge $MinScore } |
            Sort-Object { Get-ItemScore $_ } -Descending |
            Select-Object -First $TopN
    )
}

function Escape-CsvField {
    param([string]$Value)
    if ($null -eq $Value) { return '' }
    $s = [string]$Value
    if ($s -match '[",\r\n]') {
        return '"' + ($s -replace '"', '""') + '"'
    }
    return $s
}

function Export-TopCsv {
    param(
        [string]$FilePath,
        [array]$Rows,
        [hashtable]$Meta
    )
    $headers = @(
        'rank_no', 'stock_code', 'stock_name', 'exchange_type',
        'final_score', 'total_score', 'trend_score', 'momentum_score',
        'volume_score', 'ret20', 'ret60'
    )
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($k in @('trade_date', 'strategy', 'strategy_label', 'factor_version', 'result_id', 'task_id', 'total_items', 'qualified_items')) {
        if ($Meta.ContainsKey($k) -and $null -ne $Meta[$k]) {
            $lines.Add("# $k=$($Meta[$k])")
        }
    }
    $lines.Add(($headers -join ','))
    $rank = 1
    foreach ($row in $Rows) {
        $values = @(
            $rank
            $(if ($row.stock_code) { $row.stock_code } else { '' })
            $(if ($row.stock_name) { $row.stock_name } else { '' })
            $(if ($row.exchange_type) { $row.exchange_type } else { '' })
            $(if ($null -ne $row.final_score) { $row.final_score } else { '' })
            $(if ($null -ne $row.total_score) { $row.total_score } else { '' })
            $(if ($null -ne $row.trend_score) { $row.trend_score } else { '' })
            $(if ($null -ne $row.momentum_score) { $row.momentum_score } else { '' })
            $(if ($null -ne $row.volume_score) { $row.volume_score } else { '' })
            $(if ($null -ne $row.ret20) { $row.ret20 } else { '' })
            $(if ($null -ne $row.ret60) { $row.ret60 } else { '' })
        )
        $lines.Add(($values | ForEach-Object { Escape-CsvField $_ }) -join ',')
        $rank++
    }
    $utf8Bom = New-Object System.Text.UTF8Encoding $true
    [System.IO.File]::WriteAllLines($FilePath, $lines.ToArray(), $utf8Bom)
}

function Assert-MainBoardUpdateTask {
    param(
        [object]$Task,
        [string]$ExpectedMode = 'MAIN_BOARD'
    )
    $mode = [string]$Task.mode
    $total = [int]$Task.total_stocks
    if ($mode.ToUpper() -ne $ExpectedMode.ToUpper()) {
        throw "数据更新模式异常: 期望 $ExpectedMode，实际 $mode（拒绝单股/清单模式误跑）"
    }
    if ($total -le 1) {
        throw "数据更新仅包含 $total 只股票，疑似未走主板枚举，已中止"
    }
    Write-Log "数据更新确认为主板枚举: mode=$mode, total_stocks=$total" -Level OK
}

function New-MainBoardUpdateBody {
    param(
        [string]$StartDate,
        [string]$EndDate
    )
    # 与数据管理页「主板枚举」一致：不传 stockCode / exchange / stockListText，避免落回 SINGLE
    return @{
        mode      = 'MAIN_BOARD'
        startDate = $StartDate
        endDate   = $EndDate
        delayMs   = 150
        maxStocks = 0
    }
}

function Get-ScoringPayloads {
    param([string]$TradeDate)
    return @(
        @{
            key      = 'classic'
            label    = '经典多因子'
            strategy = 'CLASSIC_SELECTOR'
            file     = 'classic_top20.csv'
            body     = @{
                strategy        = 'CLASSIC_SELECTOR'
                tradeDate       = $TradeDate
                fullHistory     = $true
                topN            = $ScorePoolSize
                minAvgAmount    = 30000
                minTrendScore   = 40
                minListingDays  = 60
                horizonProfile  = 'MEDIUM_TREND'
            }
        },
        @{
            key      = 'oversold'
            label    = '超跌反弹'
            strategy = 'OVERSOLD_REBOUND'
            file     = 'oversold_top20.csv'
            body     = @{
                strategy       = 'OVERSOLD_REBOUND'
                tradeDate      = $TradeDate
                topN           = $ScorePoolSize
                minTotalScore  = 60
                minAvgAmount   = 30000
                excludeST      = $true
                minListingDays = 60
                persist        = $true
            }
        },
        @{
            key      = 'trend'
            label    = '趋势打分'
            strategy = 'TREND'
            file     = 'trend_top20.csv'
            body     = @{
                strategy       = 'TREND'
                tradeDate      = $TradeDate
                topN           = $ScorePoolSize
                minTotalScore  = 45
                minAvgAmount   = 30000
                excludeST      = $true
                minListingDays = 60
                biasThreshold  = 5.0
                persist        = $true
            }
        },
        @{
            key      = 'comprehensive'
            label    = '综合三维度'
            strategy = 'COMPREHENSIVE'
            file     = 'comprehensive_top20.csv'
            body     = @{
                strategy       = 'COMPREHENSIVE'
                tradeDate      = $TradeDate
                topN           = $ScorePoolSize
                lookbackDays   = 120
                minTotalScore  = 55
                minAvgAmount   = 30000
                excludeST      = $true
                minListingDays = 60
                persist        = $true
            }
        },
        @{
            key      = 'multi_dim'
            label    = '多维度v1'
            strategy = 'MULTI_DIM'
            file     = 'multi_dim_top20.csv'
            body     = @{
                strategy       = 'MULTI_DIM'
                tradeDate      = $TradeDate
                topN           = $ScorePoolSize
                minTotalScore  = 55
                minAvgAmount   = 30000
                excludeST      = $true
                minListingDays = 60
                persist        = $true
            }
        }
    )
}

# ── 主流程 ────────────────────────────────────────────────────────────

try {
    $window = Get-TradingDayWindow -EndDate (Get-Date) -Count $TradingDayCount
    $tradeDate = $window.End.ToString('yyyy-MM-dd')
    $startDate = $window.Start.ToString('yyyy-MM-dd')
    $endDate = $window.End.ToString('yyyy-MM-dd')

    $runDir = Join-Path $outputRoot $tradeDate
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $script:logFile = Join-Path $runDir 'pipeline.log'

    Write-Log '========================================' 
    Write-Log '量化盘后一条龙流水线 开始'
    Write-Log "项目根目录: $projectRoot"
    Write-Log "交易日: $tradeDate | 数据窗口: $startDate ~ $endDate ($TradingDayCount 个交易日)"
    Write-Log "输出目录: $runDir"

    Ensure-MySql
    Ensure-Backend

    # Step 1: 数据更新
    if ($SkipDataUpdate) {
        Write-Log '跳过数据更新 (-SkipDataUpdate)' -Level WARN
    } else {
        Write-Log "Step 1/2: 创建历史数据更新任务 ($DataUpdateMode 主板枚举, 近7交易日)..."
        $updateBody = New-MainBoardUpdateBody -StartDate $startDate -EndDate $endDate
        $updateTask = Invoke-QuantApi -Method POST -Path '/api/data/update/run' -Body $updateBody -TimeoutSec 60
        Assert-MainBoardUpdateTask -Task $updateTask -ExpectedMode $DataUpdateMode
        $updateTaskId = [string]$updateTask.task_id
        if ([string]::IsNullOrWhiteSpace($updateTaskId)) {
            throw '数据更新任务创建失败：未返回 task_id'
        }
        Write-Log "数据更新任务已创建: $updateTaskId (主板枚举 $($updateTask.total_stocks) 只)" -Level OK
        $updateResult = Wait-DataUpdateTask -TaskId $updateTaskId -TimeoutSec $DataUpdateTimeoutSec
        Write-Log ("数据更新完成: success={0} failed={1} inserted={2}" -f `
            $updateResult.success_stocks, $updateResult.failed_stocks, $updateResult.inserted_count) -Level OK
    }

    # Step 2: 五维度打分
    Write-Log 'Step 2/2: 五维度打分 (默认参数, 打分池 Top500 → 关注清单 Top20)...'
    $summaryLines = New-Object System.Collections.Generic.List[string]
    $summaryLines.Add("量化盘后打分汇总")
    $summaryLines.Add("生成时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
    $summaryLines.Add("交易日: $tradeDate")
    $summaryLines.Add("数据窗口: $startDate ~ $endDate")
    $summaryLines.Add("打分池: Top$ScorePoolSize | 关注清单: Top$FocusTopN")
    $summaryLines.Add('')

    $payloads = Get-ScoringPayloads -TradeDate $tradeDate
    $allOk = $true

    foreach ($cfg in $payloads) {
        Write-Log "── $($cfg.label) ($($cfg.strategy)) ──"
        try {
            $created = Invoke-QuantApi -Method POST -Path '/api/scoring/unified/tasks/run' -Body $cfg.body -TimeoutSec 120
            $taskId = [string]$created.task_id
            if ([string]::IsNullOrWhiteSpace($taskId)) {
                throw '未返回 task_id'
            }
            if ($created.reused_result -eq $true) {
                Write-Log "复用已有结果 (reused_result=true), task=$taskId" -Level WARN
            }

            $done = Wait-ScoringTask -TaskId $taskId -TimeoutSec $ScoringTaskTimeoutSec
            $resultId = [string]$done.result_id
            if ([string]::IsNullOrWhiteSpace($resultId)) {
                throw "任务成功但未返回 result_id: $taskId"
            }

            $result = Invoke-QuantApi -Method GET -Path "/api/scoring/unified/result/$resultId?page=1&size=$ScorePoolSize" -TimeoutSec 120
            $items = @()
            if ($result.items) { $items += $result.items }
            if ($result.list) { $items += $result.list }
            if ($items.Count -gt 1) {
                $seen = @{}
                $items = @($items | Where-Object {
                    $code = "$($_.stock_code)|$($_.exchange_type)"
                    if ($seen.ContainsKey($code)) { return $false }
                    $seen[$code] = $true
                    return $true
                })
            }

            $topRows = Get-TopFocusRows -Items $items -TopN $FocusTopN -MinScore 0
            $csvPath = Join-Path $runDir $cfg.file
            $meta = @{
                trade_date       = $result.trade_date
                strategy         = $cfg.strategy
                strategy_label   = $cfg.label
                factor_version   = $result.factor_version
                result_id        = $resultId
                task_id          = $taskId
                total_items      = $result.total_items
                qualified_items  = $result.qualified_items
            }
            Export-TopCsv -FilePath $csvPath -Rows $topRows -Meta $meta
            Write-Log "$($cfg.label) 导出 $($topRows.Count) 条 -> $csvPath" -Level OK

            $summaryLines.Add("=== $($cfg.label) ($($cfg.strategy)) ===")
            $summaryLines.Add("result_id: $resultId | task_id: $taskId")
            $summaryLines.Add("qualified: $($result.qualified_items) | pool: $($result.total_items)")
            $summaryLines.Add("csv: $($cfg.file)")
            if ($topRows.Count -eq 0) {
                $summaryLines.Add('(无符合质量闸的 Top20)')
            } else {
                $rank = 1
                foreach ($row in $topRows) {
                    $score = Get-ItemScore $row
                    $name = if ($row.stock_name) { $row.stock_name } else { '' }
                    $summaryLines.Add("{0,2}. {1} {2} {3}  score={4}" -f $rank, $row.stock_code, $name, $row.exchange_type, $score)
                    $rank++
                }
            }
            $summaryLines.Add('')
        } catch {
            $allOk = $false
            Write-Log "$($cfg.label) 失败: $($_.Exception.Message)" -Level ERROR
            $summaryLines.Add("=== $($cfg.label) FAILED ===")
            $summaryLines.Add($_.Exception.Message)
            $summaryLines.Add('')
        }
    }

    $summaryPath = Join-Path $runDir 'summary.txt'
    $utf8Bom = New-Object System.Text.UTF8Encoding $true
    [System.IO.File]::WriteAllLines($summaryPath, $summaryLines.ToArray(), $utf8Bom)

    Write-Log '========================================'
    if ($allOk) {
        Write-Log "全部完成！结果目录: $runDir" -Level OK
        Write-Log "汇总文件: $summaryPath" -Level OK
        if ($startedBackend) {
            Write-Log '本次由脚本启动了后端，进程将在后台继续运行' -Level WARN
        }
        exit 0
    } else {
        Write-Log "部分维度失败，请查看 $summaryPath 与 pipeline.log" -Level ERROR
        exit 1
    }
} catch {
    Write-Log $_.Exception.Message -Level ERROR
    if ($_.ScriptStackTrace) {
        Write-Log $_.ScriptStackTrace -Level ERROR
    }
    exit 1
}
