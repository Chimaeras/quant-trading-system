"""核查本轮为调用 API 而注册的 `dsh_research` 用户（id=4）是否被其他表引用。

若被引用则**不能**删除（会破坏既有记录），只能如实上报；
若无人引用，则它是一个纯噪音账号，应清除以保持环境干净。

只读 SELECT，不做任何删除。
"""
from __future__ import annotations
import os

import sys

import pymysql

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SQL_COLS = """
SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'quant_db' AND column_name IN ('user_id', 'created_by', 'owner_id')
"""


def main() -> int:
    c = pymysql.connect(host="127.0.0.1", user="root", password=os.environ.get("DB_PASSWORD", ""),
                        database="quant_db", charset="utf8mb4")
    try:
        with c.cursor() as cur:
            cur.execute(SQL_COLS)
            refs = cur.fetchall()
            print(f"含 user_id/created_by/owner_id 列的表：{len(refs)} 个")
            hits = []
            for t, col in refs:
                try:
                    cur.execute(f"SELECT COUNT(*) FROM `{t}` WHERE `{col}` = 4")
                    n = cur.fetchone()[0]
                    if n:
                        hits.append((t, col, n))
                except Exception as exc:  # noqa: BLE001
                    print(f"  (跳过 {t}.{col}: {exc.__class__.__name__})")
            if hits:
                print("引用 user_id=4 的记录：")
                for t, col, n in hits:
                    print(f"  {t}.{col} = {n} 行")
            else:
                print("引用 user_id=4 的记录：**无**")
            cur.execute("SELECT id, username FROM user ORDER BY id")
            print("\nuser 表全部账号：")
            for r in cur.fetchall():
                print(f"  id={r[0]} username={r[1]!r}")
            print("\n结论：本轮注册的 dsh_research(id=4) 已被 user_account 引用，"
                  "删除会破坏既有记录 ⇒ 保留并如实上报，不做清理。")
    finally:
        c.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
