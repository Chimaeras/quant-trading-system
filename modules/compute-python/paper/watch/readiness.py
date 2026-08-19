"""自动下单前的机器可读就绪检查。"""
from __future__ import annotations

import shutil
from pathlib import Path


def build_readiness_report(
    *,
    store_health: dict,
    store_path: Path | None,
    lease_ok: bool,
    state_ok: bool,
    universe_count: int,
    strategy_ready_count: int,
    min_strategy_ready_coverage: float,
    min_store_free_mb: int,
) -> dict:
    coverage = strategy_ready_count / universe_count if universe_count else 0.0
    free_mb = None
    disk_ok = False
    try:
        if store_path is not None:
            free_mb = round(shutil.disk_usage(store_path.parent).free / 1024 / 1024, 2)
            disk_ok = free_mb >= min_store_free_mb
    except OSError:
        disk_ok = False
    checks = [
        {"name": "watch_store", "ok": bool(store_health.get("ok")), "hard": True,
         "detail": store_health},
        {"name": "live_lease", "ok": bool(lease_ok), "hard": True},
        {"name": "runtime_state", "ok": bool(state_ok), "hard": True},
        {"name": "strategy_warmup", "ok": coverage >= min_strategy_ready_coverage, "hard": True,
         "ready": strategy_ready_count, "total": universe_count,
         "coverage": round(coverage, 6), "minimum": min_strategy_ready_coverage},
        {"name": "store_disk", "ok": disk_ok, "hard": True,
         "free_mb": free_mb, "minimum_mb": min_store_free_mb},
    ]
    return {
        "ready": all(check["ok"] for check in checks if check.get("hard")),
        "checks": checks,
        "strategy_ready_coverage": round(coverage, 6),
    }
