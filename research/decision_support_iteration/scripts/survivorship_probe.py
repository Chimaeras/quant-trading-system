"""幸存者偏差「可测性」核查（只读）。

背景
----
`security_master_history` 为空 ⇒ `PointInTimeUniverseProvider.loadAsOf` 走
`stock_info` 当前截面回退（`RELIABILITY_LIMITED`/`pit_limited=true`）。
该回退路径已在 HEAD 的 D-PIT-01（commit 34098346）中补上 list_date 前视过滤，
但**幸存者偏差**在代码注释里被明确标为「本路径无法修复」。

问题
----
幸存者偏差到底有多大？能否用本地数据量化？

判据（本脚本要验证的命题）
--------------------------
若 `market_data` 中出现的每个 (stock_code, exchange_type) 键**都**能在
`stock_info` 中找到且 status=1，则说明本地行情库**只含截至今天仍存续的标的**，
即：期内已退市标的的行情**整体缺失**——幸存者偏差不是「被过滤掉了多少行」
（那可以计数），而是「从未存在」（不可计数）。

这解释了为什么离线评价的 drop_reasons 里
`NOT_IN_UNIVERSE` / `NOT_YET_LISTED` 都是 0。

仅执行 SELECT；任何非 SELECT 语句直接拒绝。
"""
from __future__ import annotations
import os

import datetime as dt
import json
import pathlib
import re
import sys

import pymysql

OUT = pathlib.Path(__file__).resolve().parents[1] / "survivorship_probe.json"

DSN = dict(host="127.0.0.1", port=3306, user="root", password=os.environ.get("DB_PASSWORD", ""),
           database="quant_db", charset="utf8mb4", cursorclass=pymysql.cursors.Cursor)

ALLOWED = re.compile(r"^\s*(SELECT|SHOW|DESCRIBE|DESC|EXPLAIN)\b", re.I)


def q(cur, sql, args=None):
    if not ALLOWED.match(sql):
        raise RuntimeError(f"拒绝非只读语句: {sql[:60]}")
    cur.execute(sql, args or ())
    return cur.fetchall()


def main() -> int:
    conn = pymysql.connect(**DSN)
    out: dict = {}
    try:
        cur = conn.cursor()

        # 1) 行情库中的全部键 vs 主表键
        md_keys = {f"{a}|{b}" for a, b in q(
            cur, "SELECT DISTINCT stock_code, exchange_type FROM market_data")}
        si_keys = {f"{a}|{b}" for a, b in q(
            cur, "SELECT DISTINCT stock_code, exchange_type FROM stock_info")}
        si_active = {f"{a}|{b}" for a, b in q(
            cur, "SELECT DISTINCT stock_code, exchange_type FROM stock_info WHERE status=1")}

        out["market_data_keys"] = len(md_keys)
        out["stock_info_keys"] = len(si_keys)
        out["stock_info_active_keys"] = len(si_active)

        # 核心命题：行情键是否全部在主表中
        out["md_keys_missing_from_stock_info"] = sorted(md_keys - si_keys)
        out["count_md_missing"] = len(out["md_keys_missing_from_stock_info"])
        out["count_md_not_active"] = len(md_keys - si_active)

        # 2) 主表中是否有已退市/停牌迹象
        rows = q(cur, """SELECT
                SUM(CASE WHEN status IS NULL THEN 1 ELSE 0 END),
                SUM(CASE WHEN status <> 1 THEN 1 ELSE 0 END),
                SUM(CASE WHEN list_date IS NULL THEN 1 ELSE 0 END),
                COUNT(*)
            FROM stock_info""")
        out["stock_info_status_null"], out["stock_info_status_not1"], \
            out["stock_info_listdate_null"], out["stock_info_total"] = [int(x or 0) for x in rows[0]]

        # 3) 每个代码在行情库中的最后一个交易日
        rows = q(cur, """SELECT stock_code, MAX(trade_date) AS last_d
                         FROM market_data GROUP BY stock_code""")
        last = {str(c): d for c, d in rows}
        win_start = dt.date(2026, 1, 1)
        win_end = dt.date(2026, 8, 31)
        # 在评价窗口内「中途消失」的代码（最后一根 bar 落在窗口内且早于窗口末尾 20 天）
        vanished = sorted(
            (c, str(d)) for c, d in last.items()
            if d is not None and win_start < d < win_end - dt.timedelta(days=20)
        )
        out["codes_last_bar_inside_window_then_stopped"] = vanished[:40]
        out["count_vanished_in_window"] = len(vanished)

        # 4) stock_info 是否存在退市相关列
        cols = q(cur, "SHOW COLUMNS FROM stock_info")
        out["stock_info_columns"] = [str(r[0]) for r in cols]
        out["has_delist_column"] = any(
            "delist" in str(r[0]).lower() or "退市" in str(r[1] or "") for r in cols)
    finally:
        conn.close()

    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in out.items()
                      if not k.startswith("codes_last_bar")}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
