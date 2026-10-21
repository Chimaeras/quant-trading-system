#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
R005 **事后（post-hoc）** 诊断：为什么 ret20/ret60 在冻结的 4 约定下判为 NEITHER？

⚠️ 本脚本**不参与判定**。预注册 `rounds/R005_prereg.md` §4-A6b 冻结的 4 个约定与阈值
   未被修改，`result.json` 的 A6b 判定仍是 NEITHER/UNDETERMINABLE。
   本脚本的作用**只有一个**：解释 NEITHER 的成因（量纲？窗口跨度？），
   并把结论作为报告附录的**事后勘误**登记。沿用 R003 `posthoc_board_consistency.py` 的先例：
   事后再看必须显式标注、不得回写成主判据。

关键逻辑：冻结的 LA_1 / LA_5 匹配率已经 ≈ 0 ⟹ **冻结数据本身已排除前视**；
"对不上"只能来自约定/量纲，不能来自未来数据。本脚本只是把"是哪种约定"钉死。
"""
import os
import json
from datetime import datetime, timedelta

import pymysql

HERE = os.path.dirname(os.path.abspath(__file__))
DB = {
    "host": os.environ.get("QUANT_DB_HOST", "localhost"),
    "port": int(os.environ.get("QUANT_DB_PORT", "3306")),
    "user": os.environ.get("QUANT_DB_USER", "root"),
    "password": os.environ.get("QUANT_DB_PASSWORD", os.environ.get("DB_PASSWORD", "")),
    "database": os.environ.get("QUANT_DB_NAME", "quant_db"),
    "charset": "utf8mb4",
}
PROBE_VERSION = "unified_classic_v1"
TOL = 1e-3

# post-hoc 网格（**非预注册**）
END_OFFS = (0, -1, -2)
SPANS = {"ret20": (19, 20, 21, 22), "ret60": (59, 60, 61, 62)}
SCALES = (1.0, 100.0)


def main():
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    cur.execute("""SELECT i.stock_code, r.trade_date, i.ret20, i.ret60
                   FROM selector_result_item i JOIN selector_result r ON r.result_id=i.result_id
                   WHERE r.factor_version=%s""", (PROBE_VERSION,))
    probe = [(c, d, float(a), float(b)) for c, d, a, b in cur.fetchall()]
    print("probe rows:", len(probe))

    codes = sorted({p[0] for p in probe})
    dates = sorted({p[1] for p in probe})
    d0 = (dates[0] - timedelta(days=180)).isoformat()
    d1 = (dates[-1] + timedelta(days=10)).isoformat()
    panel = {}
    CH = 400
    for i in range(0, len(codes), CH):
        chunk = codes[i:i + CH]
        ph = ",".join(["%s"] * len(chunk))
        cur.execute("SELECT stock_code, trade_date, close_price FROM market_data "
                    "WHERE stock_code IN (%s) AND trade_date BETWEEN %%s AND %%s "
                    "ORDER BY stock_code, trade_date" % ph, chunk + [d0, d1])
        for code, td, close in cur.fetchall():
            if close is None:
                continue
            panel.setdefault(code, []).append((td, float(close)))
    print("panel codes:", len(panel))

    idx = {c: {r[0]: k for k, r in enumerate(rows)} for c, rows in panel.items()}
    cells = {}          # (feature, end_off, span, scale) -> [hits, total]
    usable = {f: 0 for f in ("ret20", "ret60")}

    for code, d, r20, r60 in probe:
        rows = panel.get(code)
        if not rows:
            continue
        closes = [x[1] for x in rows]
        a0 = idx[code].get(d)
        if a0 is None:
            continue
        for feat, stored in (("ret20", r20), ("ret60", r60)):
            if stored == 0.0:
                continue
            usable[feat] += 1
            for eo in END_OFFS:
                a = a0 + eo
                if a < 0 or a >= len(closes):
                    continue
                for k in SPANS[feat]:
                    if a - k < 0:
                        continue
                    base = closes[a - k]
                    if base == 0.0:
                        continue
                    v = closes[a] / base - 1.0
                    for s in SCALES:
                        key = (feat, eo, k, s)
                        c = cells.setdefault(key, [0, 0])
                        c[1] += 1
                        sv = s * v
                        dev = abs(sv - stored) / max(abs(stored), abs(sv), 1e-12)
                        if dev <= TOL:
                            c[0] += 1

    table = {}
    for (feat, eo, k, s), (hit, tot) in cells.items():
        table.setdefault(feat, {})["end%+d_k%d_x%g" % (eo, k, s)] = {
            "hits": hit, "rows": tot,
            "rate": round(hit / tot, 6) if tot else None}
    best = {}
    for feat, d in table.items():
        ranked = sorted(d.items(), key=lambda kv: -(kv[1]["rate"] or 0.0))
        best[feat] = {"best_cell": ranked[0][0], "best_rate": ranked[0][1]["rate"],
                      "rows": usable[feat], "top3": ranked[:3]}

    out = {
        "script": "posthoc_window_probe.py",
        "disclaimer": "POST-HOC 诊断，不参与判定；冻结的 A6b 判定仍为 NEITHER/UNDETERMINABLE。",
        "probe_version": PROBE_VERSION,
        "usable_rows": usable,
        "tolerance": TOL,
        "grid_end_offsets": list(END_OFFS),
        "grid_spans": SPANS,
        "grid_scales": list(SCALES),
        "match_table": table,
        "best": best,
        "ran_at": datetime.now().isoformat(),
    }
    with open(os.path.join(HERE, "posthoc_window_probe.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=2, default=str)

    for feat, b in best.items():
        print("%-8s usable=%d  BEST %s rate=%s" % (feat, b["rows"], b["best_cell"], b["best_rate"]))
        for name, v in b["top3"]:
            print("        %-22s rate=%s (%d/%d)" % (name, v["rate"], v["hits"], v["rows"]))
    conn.close()


if __name__ == "__main__":
    main()
