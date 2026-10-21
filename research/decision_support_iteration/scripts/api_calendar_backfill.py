"""驱动本地后端 API：登录 + 触发交易日历回填 + 查询候选。

授权说明：使用者已就 (1)(2)(3)(4) 全部授权，其中 (3) 即本次交易日历回填。
本脚本只调用项目**自带**的幂等接口 `/api/data/trading-calendar/init`
（源码 `DataController.java:164-169` → `TradingCalendarService.deriveFromShIndex`），
该接口 `source=eastmoney_derived`、`reliability=limited`，同 trade_date 已存在则跳过。

先备份：`trading_calendar` 已用 mysqldump 备份到 `%TEMP%\\trading_calendar_backup_*.sql`。
"""
from __future__ import annotations

import json
import pathlib
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://127.0.0.1:8080"
USER = "dsh_research"
PWD = "dsh_research_2026"
TOKEN_FILE = pathlib.Path(__file__).resolve().parents[1] / ".api_token"


def call(method: str, path: str, body=None, token: str | None = None, timeout=300):
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        try:
            return exc.code, json.loads(raw)
        except Exception:  # noqa: BLE001
            return exc.code, {"raw": raw[:400]}


def login_or_register() -> str:
    if TOKEN_FILE.exists():
        tok = TOKEN_FILE.read_text(encoding="utf-8").strip()
        st, _ = call("GET", "/api/research/t1/recent?limit=1", token=tok)
        if st == 200:
            print(f"[auth] 复用已存 token（{TOKEN_FILE.name}）")
            return tok
    st, r = call("POST", "/api/auth/login", {"username": USER, "password": PWD})
    if st != 200 or not (r.get("data") or {}).get("token"):
        print(f"[auth] 登录失败/无此用户（{st}），尝试注册 {USER}")
        st, r = call("POST", "/api/auth/register", {"username": USER, "password": PWD})
    data = r.get("data") or {}
    tok = data.get("token")
    if not tok:
        print(f"[auth] 失败: {st} {json.dumps(r, ensure_ascii=False)[:300]}")
        sys.exit(1)
    TOKEN_FILE.write_text(tok, encoding="utf-8")
    print(f"[auth] OK user={data.get('username')} token 已存入 {TOKEN_FILE.name}")
    return tok


def main() -> int:
    tok = login_or_register()

    print("\n=== 1) 回填前：候选端点对 2026-09-07（旧日历边界外）的判定 ===")
    st, r = call("GET", "/api/research/t1/candidates/2026-09-07", token=tok)
    d = r.get("data") or {}
    print(f"  HTTP {st} status={d.get('status')} calendar_trading_day={d.get('calendar_trading_day')}")
    print(f"  message={d.get('message')}")

    print("\n=== 2) 交易日历幂等回填 2024-08-19 .. 2026-10-12 ===")
    st, r = call("POST",
                 "/api/data/trading-calendar/init?startDate=2024-08-19&endDate=2026-10-12",
                 token=tok)
    print(f"  HTTP {st}")
    print("  " + json.dumps(r.get("data") or r, ensure_ascii=False, indent=1)[:900])

    print("\n=== 3) 回填后：同一日期判定 ===")
    st, r = call("GET", "/api/research/t1/candidates/2026-09-07", token=tok)
    d = r.get("data") or {}
    print(f"  HTTP {st} status={d.get('status')} calendar_trading_day={d.get('calendar_trading_day')}")
    print(f"  message={d.get('message')}")

    print("\n=== 4) 回填后：2026-10-12（数据断崖日）判定 ===")
    st, r = call("GET", "/api/research/t1/candidates/2026-10-12", token=tok)
    d = r.get("data") or {}
    print(f"  HTTP {st} status={d.get('status')} calendar_trading_day={d.get('calendar_trading_day')}")
    print(f"  message={d.get('message')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
