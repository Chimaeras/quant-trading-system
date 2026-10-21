"""临时校验脚本（不属于交付物）：验证新增的两个回归用例在原始 loop.py 下确实失败。

做法：把当前（已修复）loop.py 备份到临时文件 → 用 git 原始版本覆盖 → 运行两个用例
→ finally 无条件恢复已修复版本。全程用 Python 字节读写，避免 PowerShell 重定向的编码损坏。
"""
from __future__ import annotations

import pathlib
import subprocess
import sys

REPO = pathlib.Path(r"E:\Project\quant-trading-system")
REL = "modules/compute-python/paper/watch/loop.py"
TARGET = REPO / REL

CASES = [
    "paper/tests/test_decision_audit.py::test_t1_pool_rejects_weekday_fallback_calendar_without_explicit_source",
    "paper/tests/test_decision_audit.py::test_warmup_does_not_erase_expired_bar_audit_trail",
    # D-2（2026-10-13）：信号状态/持有期口径/失效条件
    "paper/tests/test_decision_audit.py::test_warmup_signal_declares_why_it_is_not_actionable",
    "paper/tests/test_decision_audit.py::test_actionable_signal_declares_intraday_eod_horizon",
    # D-4（2026-10-13）：覆盖率适用范围与不适用原因
    "paper/tests/test_decision_audit.py::test_coverage_scope_distinguishes_pool_mode_from_full_market",
    "paper/tests/test_decision_audit.py::test_full_market_unavailable_is_reported_as_such_not_as_pool_mode",
]


def main() -> int:
    fixed = TARGET.read_bytes()
    original = subprocess.run(["git", "show", "HEAD:" + REL], cwd=REPO,
                              capture_output=True, check=True).stdout
    assert b"\x00" not in original and len(original) > 100_000, "original blob looks wrong"
    print(f"fixed={len(fixed)}B original={len(original)}B")
    try:
        TARGET.write_bytes(original)
        proc = subprocess.run(
            [sys.executable, "-m", "pytest", *CASES, "-q", "--no-header", "-p", "no:cacheprovider"],
            cwd=REPO / "modules/compute-python", capture_output=True, text=True,
        )
        tail = (proc.stdout + proc.stderr).strip().splitlines()
        print("\n".join(tail[-14:]))
        print(f"\n[original code] pytest exit={proc.returncode} (非 0 = 用例成功捕获缺陷)")
        return proc.returncode
    finally:
        TARGET.write_bytes(fixed)
        restored = TARGET.read_bytes()
        print(f"restored fixed loop.py: {len(restored)}B, identical={restored == fixed}")
        assert restored == fixed, "恢复失败！"


if __name__ == "__main__":
    raise SystemExit(main())
