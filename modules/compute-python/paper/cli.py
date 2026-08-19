"""Paper Live CLI 入口。

只读命令：ping / quote / account
研判命令：dry-run（生成意图与审计，绝不发单）
交易命令：run --confirm（人工确认码）/ cancel / reconcile
盘中命令：watch-once / watch（默认 dry-run；live 需双重确认）
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import replace
from datetime import datetime
from pathlib import Path

from .config import PaperConfig

# 默认审计根目录：compute-python/logs
LOG_BASE = Path(__file__).resolve().parent.parent / "logs"

# 本进程创建的 HTTP 客户端（P2-03：命令结束时统一关闭 Session）
_open_http_clients: list = []


def _build_clients(config: PaperConfig):
    """构建 http/quote/broker 客户端。"""
    from .broker_client import BrokerClient
    from .mx_http import MxHttpClient
    from .quote_client import QuoteClient

    http = MxHttpClient(config, artifact_dir=LOG_BASE / "paper" / "raw")
    _open_http_clients.append(http)
    return http, QuoteClient(http, config), BrokerClient(http)


def _close_clients() -> None:
    """关闭本进程创建的全部 HTTP Session。"""
    while _open_http_clients:
        _open_http_clients.pop().close()


def _trading_calendar():
    """P1-03：交易日历；未提供数据文件时返回 None（confirm 将阻断）。"""
    import os

    path = os.environ.get("PAPER_TRADING_CALENDAR_FILE", "")
    if not path:
        return None
    from .trading_calendar import load_calendar_from_file

    return load_calendar_from_file(path)


def _load_config() -> PaperConfig:
    try:
        return PaperConfig.from_env()
    except ValueError as exc:
        print(f"[paper] 配置错误: {exc}", file=sys.stderr)
        raise SystemExit(2)


def _print_run_summary(run, artifact_path) -> None:
    """打印 run 摘要。"""
    print(f"[paper][模拟] PaperRun {run.run_id} mode={run.mode} 候选来源={run.candidate_source}")
    for it in run.intents:
        print(
            f"  {it['stock_code']}: {it['intent_type']} qty={it.get('qty')} px={it.get('limit_price')} "
            f"risk={it.get('risk_status')} reasons={','.join(it.get('reason_codes', []))}"
            + (f" 拒绝={it.get('risk_reject_reason')}" if it.get("risk_reject_reason") else "")
        )
    for s in run.submits:
        print(f"  提交 {s['stock_code']} qty={s['qty']} status={s['status']} orderId={s.get('order_id', '')}")
    for err in run.errors:
        print(f"  错误: {err}")
    print(f"  artifacts: {artifact_path}")


def cmd_ping(_args: argparse.Namespace) -> int:
    """绑定与资金健康检查（P0 验收命令）。"""
    config = _load_config()
    _http, _quote, broker = _build_clients(config)
    balance, result = broker.balance()
    if balance is None:
        print(f"[paper] ping 失败 code={result.code} kind={result.kind.value} message={result.message}")
        return 1
    print("[paper][模拟] ping OK")
    print(f"  组合名称: {balance.acc_name}")
    print(f"  总资产  : {balance.total_assets:,.2f} 元")
    print(f"  可用资金: {balance.avail_balance:,.2f} 元")
    print(f"  冻结    : {balance.frozen:,.2f} 元  持仓市值: {balance.market_value:,.2f} 元")
    return 0


def cmd_quote(args: argparse.Namespace) -> int:
    """查询观察池行情（一标的一请求）。"""
    config = _load_config()
    _http, quote_client, _broker = _build_clients(config)
    codes = [c.strip() for c in args.codes.split(",") if c.strip()]
    if len(codes) > config.max_observe_codes:
        print(f"[paper] 观察池超限: {len(codes)} > {config.max_observe_codes}", file=sys.stderr)
        return 2
    exit_code = 0
    for code in codes:
        quote, result = quote_client.snapshot(code)
        if quote is None:
            print(f"[paper] {code} 行情失败: {result.message}")
            exit_code = 1
            continue
        freshness = f"{quote.freshness_seconds:.0f}s" if quote.freshness_seconds is not None else "行情时间缺失"
        print(
            f"[paper][模拟] {quote.name}({quote.code}.{quote.exchange}) "
            f"最新价 {quote.last}  涨跌幅 {quote.pct_chg}%  成交量 {quote.volume:.0f}股  "
            f"量比 {quote.volume_ratio}  涨停价 {quote.limit_up_price}  跌停价 {quote.limit_down_price}  "
            f"行情时间 {quote.market_time}  "
            f"有效性 {quote.validity.value}(新鲜度 {freshness})"
        )
    return exit_code


def cmd_account(_args: argparse.Namespace) -> int:
    """打印资金、持仓与委托。"""
    config = _load_config()
    _http, _quote, broker = _build_clients(config)
    balance, result = broker.balance()
    if balance is None:
        print(f"[paper] balance 失败: {result.message}", file=sys.stderr)
        return 1
    print("[paper][模拟] 账户快照")
    print(f"  总资产 {balance.total_assets:,.2f}  可用 {balance.avail_balance:,.2f}  "
          f"冻结 {balance.frozen:,.2f}  持仓市值 {balance.market_value:,.2f}")
    positions, pos_result = broker.positions()
    print(f"  持仓 {len(positions)} 条 (查询 {'OK' if pos_result.ok else pos_result.message})")
    for p in positions:
        print(f"    {p.code} {p.name} 数量 {p.qty} 可用 {p.avail_qty} 市值 {p.market_value:,.2f} 盈亏 {p.profit:,.2f}")
    orders, ord_result = broker.orders()
    print(f"  委托 {len(orders)} 条 (查询 {'OK' if ord_result.ok else ord_result.message})")
    for o in orders:
        print(f"    {o.order_id} {o.code} {o.side} 价 {o.price} 量 {o.qty} 状态 {o.status}")
    return 0


def _runner(config: PaperConfig, now=None):
    from .runner import PaperRunner

    http, quote_client, broker = _build_clients(config)
    return PaperRunner(config, quote_client, broker, log_base=LOG_BASE, now=now,
                       trading_calendar=_trading_calendar())


def cmd_dry_run(args: argparse.Namespace) -> int:
    """dry-run：生成意图与审计，绝不发单。"""
    config = _load_config()
    runner = _runner(config)
    override = None
    if args.candidates_file:
        from .candidates import load_from_file

        override = load_from_file(args.candidates_file)
    run, abort = runner.dry_run(args.signal_date, candidates_override=override)
    from .artifacts import run_dir

    path = run_dir(LOG_BASE, run.trade_date, run.run_id)
    _print_run_summary(run, path)
    if abort:
        print(f"[paper] dry-run 中止: {abort}", file=sys.stderr)
        return 1
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    """run：必须 --confirm；dry-run 生成不可变 Preview 后凭一次性确认码提交。"""
    config = _load_config()
    if not args.confirm:
        print("[paper] run 需要 --confirm（人工确认，禁止无人值守）", file=sys.stderr)
        return 2
    runner = _runner(config)
    override = None
    if args.candidates_file:
        from .candidates import load_from_file

        override = load_from_file(args.candidates_file)
    # 第一步：dry-run 预览（生成不可变 Preview 计划 + 一次性 confirm_token）
    preview, abort = runner.dry_run(args.signal_date, candidates_override=override)
    if abort:
        print(f"[paper] 预检中止: {abort}", file=sys.stderr)
        return 1
    token = runner.preview_token()
    print(f"\n[paper][模拟] === 订单摘要（preview_run_id={preview.run_id}，10 分钟内有效）===")
    buyable = [i for i in preview.intents if i["intent_type"] == "BUY_LIMIT"]
    if not buyable:
        print("[paper] 无可买意图，结束（不调用 trade）")
        return 0
    for it in buyable:
        print(f"  买入 {it['stock_code']} qty={it['qty']} 限价={it['limit_price']}")
    ack = input(f"\n确认提交以上 {len(buyable)} 笔模拟委托？输入一次性确认码 {token} 继续: ").strip()
    if ack != token:
        print("[paper] 确认码不匹配，已取消（未发任何委托）")
        return 1
    # 第二步：confirm 执行 Preview 不可变计划（不重新生成 intents）
    run, abort2 = runner.run_confirm(
        args.signal_date, ack, preview_run_id=preview.run_id
    )
    from .artifacts import run_dir

    _print_run_summary(run, run_dir(LOG_BASE, run.trade_date, run.run_id))
    if abort2:
        print(f"[paper] run 中止: {abort2}", file=sys.stderr)
        return 1
    return 0


def cmd_cancel(args: argparse.Namespace) -> int:
    """撤单：--order-id + --stock-code 指定或 --all 全撤（均须 --confirm）。"""
    config = _load_config()
    if not args.confirm:
        print("[paper] cancel 需要 --confirm", file=sys.stderr)
        return 2
    _http, _quote, broker = _build_clients(config)
    if args.all:
        result = broker.cancel_all()
    elif args.order_id:
        if not args.stock_code:
            print("[paper] 单笔撤单必须提供 --stock-code（经 orders 查询验证的证券代码）", file=sys.stderr)
            return 2
        result = broker.cancel_order(args.order_id, args.stock_code)
    else:
        print("[paper] 需要 --order-id 或 --all", file=sys.stderr)
        return 2
    print(f"[paper] cancel ok={result.ok} code={result.code} message={result.message}")
    return 0 if result.ok else 1


def cmd_reconcile(args: argparse.Namespace) -> int:
    """对账：拉最新账户并汇总当日 submits。"""
    config = _load_config()
    _http, _quote, broker = _build_clients(config)
    from .reconcile import reconcile_day

    report = reconcile_day(broker, LOG_BASE, args.trade_date)
    print(f"[paper][模拟] 对账 {args.trade_date}: {report['status']}")
    print(f"  本地提交 {report['local_submits']} 笔，外部委托 {report['external_orders']} 条，"
          f"匹配 {report['matched']} 笔，未知 {report['unknown']} 笔")
    for diff in report["differences"]:
        print(f"  差异: {diff}")
    return 0 if report["status"] == "OK" else 1


def _watch_loop(config: PaperConfig, args: argparse.Namespace):
    from .watch.kline_client import KlineClient
    from .watch.loop import WatchLoop

    http, quote_client, broker = _build_clients(config)
    return WatchLoop(
        config,
        quote_client,
        broker,
        kline_client=KlineClient(http, config),
        log_base=LOG_BASE,
    )


def _watch_config(config: PaperConfig, args: argparse.Namespace) -> PaperConfig:
    strategy = getattr(args, "strategy", "") or config.watch_strategy
    return replace(config, watch_strategy=strategy.upper())


def _watch_override(args):
    if not getattr(args, "candidates_file", ""):
        return None
    from .candidates import load_from_file

    return load_from_file(args.candidates_file, source="watch_override")


def _print_watch_tick(tick) -> None:
    print(
        f"[paper][模拟][watch] {tick.finished_at} status={tick.status} "
        f"live={tick.live} bars={len(tick.new_closed_bars)} "
        f"signals={len(tick.signals)} intents={len(tick.intents)} submits={len(tick.submits)}"
    )
    for intent in tick.intents:
        print(
            f"  {intent.get('stock_code')} {intent.get('intent_type')} "
            f"qty={intent.get('qty')} px={intent.get('limit_price')} "
            f"risk={intent.get('risk_status')} "
            f"reason={','.join(intent.get('reason_codes', []))}"
        )
    for error in tick.errors:
        print(f"  错误: {error}", file=sys.stderr)


def cmd_watch_once(args: argparse.Namespace) -> int:
    """只执行一轮盘中已收盘 bar 检查。"""
    config = _watch_config(_load_config(), args)
    override = _watch_override(args)
    loop = _watch_loop(config, args)
    signal_date = args.signal_date or datetime.now().strftime("%Y-%m-%d")
    tick = loop.run_once(
        signal_date,
        live=args.live,
        confirm=args.confirm,
        candidates_override=override,
    )
    _print_watch_tick(tick)
    return 0 if tick.status not in {"BLOCKED"} else 1


def cmd_watch(args: argparse.Namespace) -> int:
    """持续盘中盯盘；默认 dry-run，live 必须 --live --confirm 且环境变量开启。"""
    config = _watch_config(_load_config(), args)
    override = _watch_override(args)
    loop = _watch_loop(config, args)
    signal_date = args.signal_date or datetime.now().strftime("%Y-%m-%d")
    try:
        ticks = loop.run(
            signal_date,
            live=args.live,
            confirm=args.confirm,
            candidates_override=override,
            max_cycles=args.max_cycles or None,
        )
    except KeyboardInterrupt:
        print("[paper][模拟][watch] 已退出，未自动撤单", file=sys.stderr)
        return 0
    for tick in ticks:
        _print_watch_tick(tick)
    return 0 if all(tick.status != "BLOCKED" for tick in ticks) else 1


def build_parser() -> argparse.ArgumentParser:
    """CLI 参数定义。"""
    parser = argparse.ArgumentParser(prog="paper.cli", description="妙想模拟盘 Paper Live（模拟交易，非实盘）")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("ping", help="绑定与资金健康检查")
    q = sub.add_parser("quote", help="查询实时行情（只读）")
    q.add_argument("--codes", required=True, help="逗号分隔的 6 位代码，如 600519,000001")
    sub.add_parser("account", help="查询资金/持仓/委托（只读）")

    d = sub.add_parser("dry-run", help="生成意图与审计（绝不发单）")
    d.add_argument("--signal-date", required=True, help="信号日 YYYY-MM-DD")
    d.add_argument("--candidates-file", default="", help="候选文件覆盖（默认取 PAPER_CANDIDATE_FILE/T1）")

    r = sub.add_parser("run", help="人工确认后模拟下单")
    r.add_argument("--signal-date", required=True)
    r.add_argument("--confirm", action="store_true", help="人工确认开关")
    r.add_argument("--candidates-file", default="")

    c = sub.add_parser("cancel", help="撤单")
    c.add_argument("--order-id", default="")
    c.add_argument("--stock-code", default="", help="单笔撤单必须提供（6 位证券代码）")
    c.add_argument("--all", action="store_true")
    c.add_argument("--confirm", action="store_true")

    rc = sub.add_parser("reconcile", help="收盘对账")
    rc.add_argument("--trade-date", required=True)

    def add_watch_args(parser):
        parser.add_argument("--signal-date", default="", help="观察池信号日，默认今日")
        parser.add_argument("--strategy", choices=["MA_CROSS", "MACD", "BOLL"], default="")
        parser.add_argument("--candidates-file", default="", help="离线观察池覆盖文件")
        parser.add_argument("--live", action="store_true", help="允许进入模拟盘提交分支")
        parser.add_argument("--confirm", action="store_true", help="会话级人工确认")

    wo = sub.add_parser("watch-once", help="执行一轮盘中盯盘（默认 dry-run）")
    add_watch_args(wo)
    w = sub.add_parser("watch", help="持续盘中盯盘（默认 dry-run）")
    add_watch_args(w)
    w.add_argument("--max-cycles", type=int, default=0, help="测试/短跑用循环次数，0 表示持续运行")
    return parser


def main(argv: list[str] | None = None) -> int:
    """CLI 入口，返回进程退出码。"""
    args = build_parser().parse_args(argv)
    handlers = {
        "ping": cmd_ping,
        "quote": cmd_quote,
        "account": cmd_account,
        "dry_run": cmd_dry_run,
        "run": cmd_run,
        "cancel": cmd_cancel,
        "reconcile": cmd_reconcile,
        "watch-once": cmd_watch_once,
        "watch": cmd_watch,
    }
    try:
        return handlers[args.command](args)
    finally:
        _close_clients()


if __name__ == "__main__":
    sys.exit(main())
