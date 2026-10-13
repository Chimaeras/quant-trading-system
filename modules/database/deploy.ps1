# 量化交易系统 - Windows 一键部署脚本
Write-Host "=========================================="
Write-Host "  量化交易系统 - Docker 一键部署"
Write-Host "=========================================="

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

if (!(Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host "错误: 未安装 Docker" -ForegroundColor Red
    exit 1
}

Set-Location "docker"
docker compose up -d
Write-Host "`n部署完成! 访问 http://localhost" -ForegroundColor Green
