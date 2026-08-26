@echo off
setlocal EnableExtensions
chcp 65001 >nul 2>&1

rem 量化盘后一条龙：启动后端 + 拉数据 + 五维度打分 + 导出 CSV
rem 双击本文件即可，无需事先打开 IDEA 或手动启动项目。

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%daily_scoring_pipeline.ps1"

if not exist "%PS_SCRIPT%" (
    echo [错误] 找不到脚本: %PS_SCRIPT%
    pause
    exit /b 1
)

echo.
echo ========================================
echo   量化盘后一条龙流水线
echo   启动后端 -^> 主板枚举拉数(近7日) -^> 五维度Top20
echo ========================================
echo.
echo 提示: 首次启动后端可能需要 1~3 分钟，数据更新可能需 30~120 分钟。
echo       请勿关闭本窗口，完成后会显示结果目录。
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if "%EXIT_CODE%"=="0" (
    echo [完成] 请打开 output\scoring\{日期}\ 查看 CSV 与 summary.txt
) else (
    echo [失败] 退出码 %EXIT_CODE%，请查看 output\scoring\{日期}\pipeline.log
)
echo.
pause
exit /b %EXIT_CODE%
