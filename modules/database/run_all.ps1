# 执行所有数据库脚本
# 用法: .\run_all.ps1  或  .\run_all.ps1 -User root -Password 123456
# 若 mysql 不在 PATH，请手动执行: mysql -u root -p < scripts/db/01_init_schema.sql

param(
    [string]$User = "root",
    [string]$Password = "123456",
    [string]$Host = "localhost",
    [int]$Port = 3306
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# 尝试查找 mysql 命令
$mysqlCmd = $null
if (Get-Command mysql -ErrorAction SilentlyContinue) {
    $mysqlCmd = "mysql"
} elseif (Test-Path "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe") {
    $mysqlCmd = "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
} elseif (Test-Path "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe") {
    $mysqlCmd = "C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"
}

if (-not $mysqlCmd) {
    Write-Host "未找到 mysql 命令，请手动执行:" -ForegroundColor Red
    Write-Host "  mysql -u $User -p < $ScriptDir\01_init_schema.sql" -ForegroundColor Yellow
    Write-Host "  mysql -u $User -p quant_db < $ScriptDir\02_additional_indexes.sql" -ForegroundColor Yellow
    Write-Host "  mysql -u $User -p quant_db < $ScriptDir\03_legacy_tables.sql" -ForegroundColor Yellow
    exit 1
}

Write-Host "========== 量化交易系统 - 数据库初始化 ==========" -ForegroundColor Cyan
Write-Host "MySQL: ${Host}:${Port}" -ForegroundColor Gray

$files = @(
    @{ File = "01_init_schema.sql"; Db = $null },
    @{ File = "02_additional_indexes.sql"; Db = "quant_db" },
    @{ File = "03_legacy_tables.sql"; Db = "quant_db" }
)

foreach ($item in $files) {
    $path = Join-Path $ScriptDir $item.File
    if (Test-Path $path) {
        Write-Host "`n执行: $($item.File)" -ForegroundColor Yellow
        try {
            $content = Get-Content $path -Raw -Encoding UTF8
            if ($item.Db) {
                $content | & $mysqlCmd -h $Host -P $Port -u $User -p$Password $item.Db 2>&1
            } else {
                $content | & $mysqlCmd -h $Host -P $Port -u $User -p$Password 2>&1
            }
            Write-Host "  完成" -ForegroundColor Green
        } catch {
            Write-Host "  错误: $_" -ForegroundColor Red
        }
    }
}

Write-Host "`n========== 完成 ==========" -ForegroundColor Cyan
