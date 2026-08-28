# -*- coding: utf-8 -*-
"""
每日 5 点选股打分自动流水线（无第三方依赖，仅标准库）。

复刻「选股打分工作台」(modules/web/src/views/market/StockPicker.vue) 的手动流程：
  1) 在数据管理页创建「历史数据更新任务」，拉取最近 N 个交易日的行情；
  2) 等更新任务完成；
  3) 依次对 5 个策略，用默认参数创建「统一打分任务」并轮询到完成；
  4) 拉取每个策略的 TopN 结果，写成本地 CSV + 一份合并 TXT 摘要。

服务端接口（modules/backend-java）：
  POST /api/data/update/run                # 数据更新任务，返回 task_id
  GET  /api/data/update/tasks/{taskId}     # 轮询更新任务状态
  POST /api/scoring/unified/tasks/run      # 打分任务，返回 task_id
  GET  /api/scoring/unified/tasks/{taskId} # 轮询打分任务状态
  GET  /api/scoring/unified/result/latest  # 拉取某策略某交易日的完整结果
  POST /api/auth/login                     # 若开启鉴权，先拿 token

用法：
  python daily_scoring_pipeline.py                  # 全部默认
  python daily_scoring_pipeline.py --mode MAIN_BOARD
  python daily_scoring_pipeline.py --days 7 --topn 20
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

# ---------------------------------------------------------------------------
# 配置（可用环境变量覆盖）
# ---------------------------------------------------------------------------
BASE_URL = os.environ.get("QTS_BASE_URL", "http://localhost:8080/api")
# 鉴权：docker 部署默认开启；本地 dev 默认关闭。脚本默认不开鉴权，
# 只在设置了 --auth（或 QTS_AUTH=1）时才登录。账号密码用环境变量覆盖。
AUTH_ENABLED = os.environ.get("QTS_AUTH", "").strip() == "1"
AUTH_USERNAME = os.environ.get("QTS_USERNAME", "admin")
AUTH_PASSWORD = os.environ.get("QTS_PASSWORD", "admin123")
# 项目根 = scripts/..，结果默认输出到 <项目根>/scoring_results/
OUTPUT_DIR = os.environ.get("QTS_OUTPUT_DIR", "")

# 数据更新默认口径（对齐前端 数据管理 > 快捷区间「近7个交易日」）
DEFAULT_UPDATE_MODE = "ALL"   # SINGLE / ALL / LIST / MAIN_BOARD
DEFAULT_TRADING_DAYS = 7

# 打分层级
DEFAULT_POOL_SIZE = 500       # 打分池上限（前端 scoreTopN 默认 500）
DEFAULT_TOP_N = 20            # 关注的 TopN（前端 focusForm.topN 默认 20）

# 轮询超时（秒）
UPDATE_TIMEOUT_S = 3 * 3600
SCORING_TIMEOUT_S = 2 * 3600
POLL_INTERVAL_S = 5

# 更新任务的终态
UPDATE_TERMINAL = {"SUCCESS", "PARTIAL", "FAILED", "STOPPED"}
SCORING_TERMINAL = {"SUCCESS", "FAILED", "CANCELLED"}

# ---------------------------------------------------------------------------
# 5 个策略及其「默认参数」请求体（与前端工作台默认值逐一对应）
# ---------------------------------------------------------------------------
def strategy_payloads(trade_date: str, pool: int) -> list[tuple[str, dict]]:
    return [
        ("CLASSIC_SELECTOR", {
            "strategy": "CLASSIC_SELECTOR",
            "tradeDate": trade_date,
            "fullHistory": True,
            "topN": pool,
            "minAvgAmount": 30_000_000,   # 单位元（=3000万元日均成交额）；修正「万元→元」口径，与后端默认一致
            "minTrendScore": 40,
            "minListingDays": 60,
            "horizonProfile": "MEDIUM_TREND",
            "persist": True,
        }),
        ("OVERSOLD_REBOUND", {
            "strategy": "OVERSOLD_REBOUND",
            "tradeDate": trade_date,
            "topN": pool,
            "minTotalScore": 60,
            "minAvgAmount": 30_000_000,   # 单位元（=3000万元日均成交额）；修正「万元→元」口径
            "excludeST": True,
            "minListingDays": 60,
            "persist": True,
        }),
        ("TREND", {
            "strategy": "TREND",
            "tradeDate": trade_date,
            "topN": pool,
            "minTotalScore": 45,
            "minAvgAmount": 30_000_000,   # 单位元（=3000万元日均成交额）；修正「万元→元」口径
            "excludeST": True,
            "minListingDays": 60,
            "persist": True,
            "biasThreshold": 5.0,
        }),
        ("COMPREHENSIVE", {
            "strategy": "COMPREHENSIVE",
            "tradeDate": trade_date,
            "topN": pool,
            "minTotalScore": 55,
            "minAvgAmount": 30_000_000,   # 单位元（=3000万元日均成交额）；修正「万元→元」口径
            "excludeST": True,
            "minListingDays": 60,
            "persist": True,
            "lookbackDays": 120,
        }),
        ("MULTI_DIM", {
            "strategy": "MULTI_DIM",
            "tradeDate": trade_date,
            "topN": pool,
            "minTotalScore": 55,
            "minAvgAmount": 30_000_000,   # 单位元（=3000万元日均成交额）；修正「万元→元」口径
            "excludeST": True,
            "minListingDays": 60,
            "persist": True,
        }),
    ]


# ---------------------------------------------------------------------------
# HTTP 帮助函数（urllib，无第三方依赖）
# ---------------------------------------------------------------------------
class HttpError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def _http(method: str, path: str, body=None, params=None, token=None, timeout=120):
    url = BASE_URL + path
    if params:
        url += "?" + urllib.parse.urlencode(params)
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        raise HttpError(e.code, None, raw)
    except urllib.error.URLError as e:
        # 连接被拒/DNS/网络瞬断/超时：统一包装为 HttpError，避免在长轮询中未捕获崩溃（调用方只捕获 HttpError）。
        raise HttpError(None, None, f"网络错误: {e.reason}")
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        raise HttpError(None, None, "非 JSON 响应: " + raw[:300])
    if payload.get("code") != 200:
        raise HttpError(None, payload.get("code"), payload.get("message", "业务失败"))
    return payload.get("data")


def login():
    data = _http("POST", "/auth/login", {"username": AUTH_USERNAME, "password": AUTH_PASSWORD})
    return data.get("token")


# ---------------------------------------------------------------------------
# 交易日计算（对齐前端 utils/tradingDays.ts）
# ---------------------------------------------------------------------------
def latest_weekday(d: date) -> date:
    while d.weekday() >= 5:  # 0=Mon..4=Fri, 5/6=Sat/Sun
        d -= timedelta(days=1)
    return d


def trading_window(end: date, n: int) -> tuple[date, date]:
    """闭区间 [start, end] 恰好含 n 个工作日（含 end）。"""
    cur = end
    remaining = max(1, n) - 1
    while remaining > 0:
        cur -= timedelta(days=1)
        if cur.weekday() < 5:
            remaining -= 1
    return cur, end


# ---------------------------------------------------------------------------
# 数据更新任务
# ---------------------------------------------------------------------------
def create_update_task(mode, start, end, token):
    body = {
        "mode": mode,
        "startDate": start.isoformat(),
        "endDate": end.isoformat(),
    }
    if mode == "MAIN_BOARD":
        body["delayMs"] = 150
    data = _http("POST", "/data/update/run", body, token=token)
    task_id = data.get("task_id")
    if not task_id:
        raise HttpError(None, None, "数据更新任务未返回 task_id: " + json.dumps(data, ensure_ascii=False))
    return task_id


def poll_update(task_id, token, timeout_s):
    deadline = time.time() + timeout_s
    while True:
        if time.time() > deadline:
            raise HttpError(None, None, f"数据更新任务 {task_id} 轮询超过 {timeout_s}s，放弃等待")
        data = _http("GET", f"/data/update/tasks/{task_id}", token=token)
        status = data.get("status", "")
        if status in UPDATE_TERMINAL:
            return data
        time.sleep(POLL_INTERVAL_S)


# ---------------------------------------------------------------------------
# 打分任务
# ---------------------------------------------------------------------------
def create_scoring_task(payload, token):
    data = _http("POST", "/scoring/unified/tasks/run", payload, token=token)
    if not data.get("task_id"):
        raise HttpError(None, None, "打分任务未返回 task_id: " + json.dumps(data, ensure_ascii=False))
    return data


def poll_scoring(task_id, token, timeout_s):
    deadline = time.time() + timeout_s
    while True:
        if time.time() > deadline:
            raise HttpError(None, None, f"打分任务 {task_id} 轮询超过 {timeout_s}s，放弃等待")
        data = _http("GET", f"/scoring/unified/tasks/{task_id}", token=token)
        status = data.get("status", "")
        if status == "SUCCESS":
            return data
        if status in SCORING_TERMINAL:   # FAILED / CANCELLED
            raise HttpError(None, None, f"打分任务 {task_id} 状态 {status}: {data.get('message', '')}")
        time.sleep(POLL_INTERVAL_S)


def fetch_result(strategy, trade_date, pool, token):
    data = _http(
        "GET", "/scoring/unified/result/latest",
        params={"strategy": strategy, "tradeDate": trade_date, "page": 1, "size": pool},
        token=token,
    )
    return data


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------
CSV_HEADERS = ["rank_no", "stock_code", "stock_name", "final_score",
               "trend_score", "momentum_score", "volume_score", "ret20", "ret60"]


def write_csv(path, rows, meta):
    lines = [
        f"# trade_date={meta.get('trade_date', '')}",
        f"# strategy={meta.get('strategy', '')}",
        f"# factor_version={meta.get('factor_version', '')}",
        ",".join(CSV_HEADERS),
    ]
    for r in rows:
        lines.append(",".join(str(r.get(h, "") or "") for h in CSV_HEADERS))
    with open(path, "w", encoding="utf-8-sig", newline="") as f:  # utf-8-sig 便于 Excel 打开中文
        f.write("\n".join(lines) + "\n")


def write_summary(path, per_strategy, meta):
    lines = [f"选股打分 5 个维度 Top{meta.get('top_n', '')} 汇总",
             f"交易日: {meta.get('trade_date', '')}", ""]
    for name, rows, err in per_strategy:
        lines.append("-" * 60)
        lines.append(f"[{name}]  {'失败: ' + err if err else ''}")
        lines.append(f"{'排名':<6}{'代码':<10}{'名称':<16}{'综合分':>8}{'趋势':>8}{'动量':>8}{'量能':>8}")
        for i, r in enumerate(rows[: meta.get("top_n", 20)], 1):
            lines.append(f"{i:<6}{str(r.get('stock_code','')):<10}{str(r.get('stock_name','')):<16}"
                         f"{fmt(r.get('final_score')):>8}{fmt(r.get('trend_score')):>8}"
                         f"{fmt(r.get('momentum_score')):>8}{fmt(r.get('volume_score')):>8}")
        lines.append("")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def fmt(v):
    if v in (None, ""):
        return "—"
    try:
        return f"{float(v):.2f}"
    except (TypeError, ValueError):
        return str(v)


def main():
    ap = argparse.ArgumentParser(description="每日选股打分自动流水线")
    ap.add_argument("--mode", default=os.environ.get("QTS_UPDATE_MODE", DEFAULT_UPDATE_MODE),
                    choices=["SINGLE", "ALL", "LIST", "MAIN_BOARD"])
    ap.add_argument("--days", type=int, default=DEFAULT_TRADING_DAYS, help="要拉取/覆盖的交易日个数")
    ap.add_argument("--topn", type=int, default=DEFAULT_TOP_N, help="每个维度输出的 TopN")
    ap.add_argument("--pool", type=int, default=DEFAULT_POOL_SIZE, help="打分池上限")
    ap.add_argument("--offline", action="store_true", help="跳过数据更新，直接对已有数据打分")
    ap.add_argument("--auth", action="store_true", help="开启鉴权登录（docker 部署默认开启）")
    args = ap.parse_args()

    use_auth = AUTH_ENABLED or args.auth

    root = Path(__file__).resolve().parent.parent
    out_dir = Path(OUTPUT_DIR) if OUTPUT_DIR else (root / "scoring_results")
    out_dir.mkdir(parents=True, exist_ok=True)

    # 鉴权（默认关闭；docker 部署用 --auth 或 QTS_AUTH=1 开启）
    token = None
    if use_auth:
        if not (AUTH_USERNAME and AUTH_PASSWORD):
            print("[auth] 已开启鉴权但缺少账号密码（QTS_USERNAME/QTS_PASSWORD）", file=sys.stderr)
            sys.exit(2)
        try:
            token = login()
            print(f"[auth] 已登录: {AUTH_USERNAME}")
        except HttpError as e:
            print(f"[auth] 登录失败: {e.message}", file=sys.stderr)
            sys.exit(2)

    end = latest_weekday(date.today())
    start, end = trading_window(end, args.days)
    print(f"[交易日] {start} ~ {end}（{args.days} 个交易日窗口），模式={args.mode}")

    # 1) 数据更新
    if not args.offline:
        try:
            task_id = create_update_task(args.mode, start, end, token)
            print(f"[数据更新] 任务 {task_id} 已创建，轮询中...")
            info = poll_update(task_id, token, UPDATE_TIMEOUT_S)
            status = info.get("status")
            print(f"[数据更新] {status} 插入={info.get('inserted_count', 0)} "
                  f"跳过={info.get('skipped_count', 0)} 成功={info.get('success_stocks', 0)} "
                  f"失败={info.get('failed_stocks', 0)}")
            if status in ("FAILED", "STOPPED"):
                print("[数据更新] 未成功，中止后续打分以避免基于过期数据", file=sys.stderr)
                sys.exit(2)
        except HttpError as e:
            # 更新失败/超时：跳过数据更新，继续对已有数据打分（原逻辑一致）
            print(f"[数据更新] 未完成（{e.message}），继续对已有数据打分", file=sys.stderr)

    # 2) 依次打分 5 个维度
    strategies = strategy_payloads(end.isoformat(), args.pool)
    per_strategy = []
    for name, payload in strategies:
        print(f"[打分] {name} 提交中...")
        try:
            created = create_scoring_task(payload, token)
            tid = created.get("task_id")
            if created.get("status") == "SUCCESS":
                print(f"[打分] {name} 命中同参历史结果，直接复用 result_id={created.get('result_id')}")
            else:
                print(f"[打分] {name} 任务 {tid}（{created.get('status')}），轮询中...")
                done = poll_scoring(tid, token, SCORING_TIMEOUT_S)
                print(f"[打分] {name} 完成 result_id={done.get('result_id')}")
            data = fetch_result(name, end.isoformat(), args.pool, token)
            rows = data.get("list") or data.get("items") or []
            per_strategy.append((name, rows, None))
            print(f"[打分] {name} 返回 {len(rows)} 条")
        except HttpError as e:
            print(f"[打分] {name} 失败: {e.message}", file=sys.stderr)
            per_strategy.append((name, [], e.message))

    # 3) 输出
    meta = {"trade_date": end.isoformat(), "top_n": args.topn}
    summary_rows = []
    for name, rows, err in per_strategy:
        csv_path = out_dir / f"score-picker-{name.lower()}-{end.isoformat()}.csv"
        write_csv(csv_path, rows, {"trade_date": end.isoformat(), "strategy": name,
                                   "factor_version": "v1"})
        print(f"[输出] {csv_path}")
        summary_rows.append((name, rows, err))
    summary_path = out_dir / f"score-picker-top{args.topn}-{end.isoformat()}.txt"
    write_summary(summary_path, summary_rows, meta)
    print(f"[输出] {summary_path}")

    failed = [n for n, _, err in per_strategy if err]
    if failed:
        print(f"[提示] 以下策略失败: {', '.join(failed)}", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
