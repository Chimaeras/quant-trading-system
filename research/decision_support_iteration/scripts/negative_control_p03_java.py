"""临时校验脚本（不属于交付物）：验证 P0-3 的新回归用例在原始 Java 实现下确实失败。

做法：把当前（已修复）T1CandidateService 与其测试类备份 → 用 git HEAD 版本覆盖源码
→ 在当前测试类下运行目标用例 → finally 无条件恢复两个文件。全程 Python 字节读写，
避免 PowerShell 重定向的编码损坏（此前 `>` 曾把源码写成 UTF-16 并损坏文件）。

注：预期失败形态有两种——编译错误（原实现无 coverageWindow/calendar_trading_day）
或断言失败。两者都算「用例成功捕获缺陷」，因此只要求 pytest 之外的 mvn 退出码非 0。
"""
from __future__ import annotations

import pathlib
import subprocess
import sys

# Windows 控制台默认 GBK，Maven 输出的替换字符无法编码 → 显式用 UTF-8 并容错
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

REPO = pathlib.Path(r"E:\Project\quant-trading-system")
MOD = REPO / "modules" / "backend-java"
# 只回退 T1CandidateService：保留 TradingCalendarService.coverageWindow()（纯基础设施新增），
# 这样原始实现仍能**编译**，用例失败来自真实行为差异而不是编译期缺方法——
# 编译失败只能证明「方法不存在」，行为断言失败才能证明缺陷被捕获。
SOURCES = [
    "modules/backend-java/src/main/java/com/quant/service/T1CandidateService.java",
]
# 测试类保持「修复后」版本：正是要证明新用例在原实现下失败
TEST_CLASS = "modules/backend-java/src/test/java/com/quant/service/T1CandidateServiceTest.java"

# mvn 在 PowerShell 里是 Application（mvn.cmd），子进程需显式给绝对路径
MVN = r"C:\Users\YOUR_USER\tools\apache-maven-3.9.14\bin\mvn.cmd"

CASES = (
    "T1CandidateServiceTest#missingSignalDayDoesNotFallBackOrWriteSnapshot"
    "+nonTradingDayIsReportedAsSuchNotAsMissingData"
    "+dayOutsideCalendarCoverageIsUnknownNotTreatedAsHoliday"
    "+deadDataDateFallbackMetadataIsRemoved"
)


def run_tests() -> subprocess.CompletedProcess:
    return subprocess.run(
        [MVN, "-o", "-q", "-Dquant.skipFrontendBuild=true",
         f"-Dtest={CASES}", "-DfailIfNoTests=false", "test"],
        cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )


def main() -> int:
    fixed = {rel: (REPO / rel).read_bytes() for rel in SOURCES}
    original = {
        rel: subprocess.run(["git", "show", "HEAD:" + rel], cwd=REPO,
                            capture_output=True, check=True).stdout
        for rel in SOURCES
    }
    for rel in SOURCES:
        assert b"\x00" not in original[rel], f"{rel} original blob looks corrupted"
        print(f"{rel}: fixed={len(fixed[rel])}B original={len(original[rel])}B")

    try:
        for rel in SOURCES:
            (REPO / rel).write_bytes(original[rel])
        proc = run_tests()
        out = (proc.stdout or "") + (proc.stderr or "")
        tail = [ln for ln in out.strip().splitlines() if ln.strip()][-18:]
        print("\n".join(tail))
        print(f"\n[original code] mvn exit={proc.returncode} (非 0 = 用例成功捕获缺陷)")
        return 0 if proc.returncode != 0 else 1
    finally:
        for rel in SOURCES:
            (REPO / rel).write_bytes(fixed[rel])
        ok = all((REPO / rel).read_bytes() == fixed[rel] for rel in SOURCES)
        print(f"restored fixed sources: identical={ok}")
        assert ok, "恢复失败！"


if __name__ == "__main__":
    raise SystemExit(main())
