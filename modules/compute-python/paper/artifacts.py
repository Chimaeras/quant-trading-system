"""PaperRun 本地审计落盘：logs/paper/{trade_date}/{run_id}/。

manifest 声明 contains_secret=false；密钥与 uid 类字段不落盘。
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

from .config import PaperConfig
from .models import PaperRun
from .mx_http import sanitize_payload


def config_hash(config: PaperConfig) -> str:
    """配置快照哈希（confirm 前后一致性校验用）。"""
    return hashlib.sha256(
        json.dumps(config.public_snapshot(), sort_keys=True).encode("utf-8")
    ).hexdigest()[:16]


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
        "config_hash": config_hash(config),
        "api_base": config.api_base,
        "contains_secret": False,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "candidates.json").write_text(json.dumps(run.candidates, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "account_before.json").write_text(
        json.dumps(sanitize_payload(run.account_before), ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    (out / "account_after.json").write_text(
        json.dumps(sanitize_payload(run.account_after), ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    (out / "intents.json").write_text(
        json.dumps(run.intents, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "risk.json").write_text(
        json.dumps(run.risk_rejects, ensure_ascii=False, indent=2), encoding="utf-8")
    (out / "submits.json").write_text(
        json.dumps(run.submits, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    for code, quote_payload in run.quotes.items():
        (out / "quotes" / f"{code}.json").write_text(
            json.dumps(quote_payload, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
    (out / "daily.md").write_text(render_daily_md(run), encoding="utf-8")
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
