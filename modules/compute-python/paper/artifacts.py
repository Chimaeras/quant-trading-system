"""PaperRun 本地审计落盘：logs/paper/{trade_date}/{run_id}/。

manifest 声明 contains_secret=false；密钥与 uid 类字段不落盘。
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

from .config import PaperConfig
from .models import PaperRun
from .mx_http import sanitize_payload


def _dumps(payload) -> str:
    """统一落盘序列化：先递归脱敏（P1-10），再缩进输出。"""
    return json.dumps(sanitize_payload(payload), ensure_ascii=False, indent=2, default=str)


def atomic_write_text(path: Path, text: str) -> None:
    """原子落盘：先写同目录临时文件（fsync），再 os.replace 覆盖目标。

    消费方按整文件解析 JSON（reconcile 读 submits.json、confirm 读 preview.json），
    而 write_text 在进程中断/磁盘满时会留下半截文件，使这些读取方解析失败、对账被迫 BLOCKED。
    临时文件必须与目标同目录，才能保证 os.replace 是同文件系统内的原子重命名。
    """
    tmp = path.with_name(path.name + ".tmp")
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write(text)
            fh.flush()
            os.fsync(fh.fileno())
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    os.replace(tmp, path)


def config_hash(config: PaperConfig) -> str:
    """配置快照哈希（confirm 前后一致性校验用，完整 SHA-256）。"""
    return hashlib.sha256(
        json.dumps(config.public_snapshot(), sort_keys=True).encode("utf-8")
    ).hexdigest()


def run_dir(base: Path, trade_date: str, run_id: str) -> Path:
    """本次 run 的落盘目录。"""
    return base / "paper" / trade_date / run_id


def write_run(base: Path, run: PaperRun, config: PaperConfig) -> Path:
    """写出全部 artifacts，返回 run 目录。

    Writes:
        manifest.json / candidates.json / quotes/*.json / account_before.json
        intents.json / risk.json / submits.json / account_after.json / daily.md
    """
    out = run_dir(base, run.trade_date, run.run_id)
    (out / "quotes").mkdir(parents=True, exist_ok=True)

    manifest = {
        "run_id": run.run_id,
        "mode": run.mode,
        "signal_date": run.signal_date,
        "trade_date": run.trade_date,
        "candidate_source": run.candidate_source,
        "strategy_version": run.strategy_version,
        "status": run.status,
        "preview_run_id": run.preview_run_id,
        "config_hash": config_hash(config),
        "api_base": config.api_base,
        "contains_secret": False,
    }
    atomic_write_text(out / "manifest.json", _dumps(manifest))
    atomic_write_text(out / "candidates.json", _dumps(run.candidates))
    atomic_write_text(out / "account_before.json", _dumps(run.account_before))
    atomic_write_text(out / "account_after.json", _dumps(run.account_after))
    atomic_write_text(out / "intents.json", _dumps(run.intents))
    atomic_write_text(out / "risk.json", _dumps(run.risk_rejects))
    atomic_write_text(out / "submits.json", _dumps(run.submits))
    for code, quote_payload in run.quotes.items():
        atomic_write_text(out / "quotes" / f"{code}.json", _dumps(quote_payload))
    atomic_write_text(out / "daily.md", render_daily_md(run))
    return out


def render_daily_md(run: PaperRun) -> str:
    """人读日报告。"""
    lines = [
        f"# PaperRun {run.run_id}",
        "",
        f"- 模式: {run.mode}（模拟 / paper，非实盘）",
        f"- 信号日 {run.signal_date} → 交易日 {run.trade_date}，候选来源 {run.candidate_source}",
        f"- 候选 {len(run.candidates)} 只，行情 {len(run.quotes)} 条，意图 {len(run.intents)} 个",
        f"- 风控拒绝 {len(run.risk_rejects)} 条，提交 {len(run.submits)} 笔",
    ]
    if run.errors:
        lines.append(f"- 错误: {'; '.join(run.errors)}")
    lines += ["", "## Intents", ""]
    for it in run.intents:
        lines.append(
            f"- {it.get('stock_code')}: {it.get('intent_type')} qty={it.get('qty')} "
            f"px={it.get('limit_price')} risk={it.get('risk_status')} "
            f"reasons={','.join(it.get('reason_codes', []))}"
            f"{' reject=' + str(it.get('risk_reject_reason')) if it.get('risk_reject_reason') else ''}"
        )
    return "\n".join(lines) + "\n"
