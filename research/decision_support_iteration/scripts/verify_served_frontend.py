"""浏览器级验证：确认 8080 实际**下发**的前端产物包含本轮改动。

第 8 轮用 Vue SSR harness 证明了 MonitorView 的**装配逻辑**正确（17/17）。
但那不等于「用户浏览器拿到的东西是对的」——中间还隔着
`vite build` → `dist` → `target/classes/static` → Spring 静态服务四道关。
本脚本验证的就是这四道关，取的是**真实 HTTP 响应体**，而非源码或本地文件。

断言：
  1. `/` 的 index.html 引用的 bundle 与本地 `dist` 构建产物一致（哈希一致）；
  2. 实际下发的 JS 里出现 D-2/D-4 的关键判定串（覆盖率口径、信号状态映射）；
  3. 下发的 JS **不再**是改动前的旧哈希；
  4. 引用的每个 asset 都能 200 取到（无 404 断链）。
"""
from __future__ import annotations

import hashlib
import pathlib
import re
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = "http://127.0.0.1:8080"
ROOT = pathlib.Path(__file__).resolve().parents[3]
DIST = ROOT / "modules" / "web" / "dist"

failures: list[str] = []


def get(path: str) -> tuple[int, bytes]:
    try:
        with urllib.request.urlopen(BASE + path, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
    if not ok:
        failures.append(label)


def main() -> int:
    print("=== 1) index.html 引用的 bundle ===")
    st, body = get("/")
    html = body.decode("utf-8", errors="replace")
    check("GET / 返回 200", st == 200, f"status={st}")
    refs = re.findall(r'(?:src|href)="(/assets/[^"]+)"', html)
    print(f"  引用 asset {len(refs)} 个")

    local_names = {p.name for p in (DIST / "assets").iterdir()} if (DIST / "assets").is_dir() else set()
    served_names = {r.split("/")[-1] for r in refs}
    missing_local = served_names - local_names
    check("index.html 引用的 asset 都存在于本地 dist", not missing_local,
          f"缺失={sorted(missing_local)}" if missing_local else "")

    print("\n=== 2) 每个引用 asset 可取到（无 404 断链）===")
    assets: dict[str, str] = {}
    for ref in refs:
        s, b = get(ref)
        check(f"GET {ref} -> 200", s == 200, f"status={s} bytes={len(b)}")
        if s == 200:
            assets[ref] = b.decode("utf-8", errors="replace")

    print("\n=== 3) 下发的 JS 与本地 dist 逐字节一致 ===")
    for ref, text in assets.items():
        if not ref.endswith(".js"):
            continue
        local = DIST / ref.lstrip("/")
        if not local.is_file():
            continue
        same = hashlib.sha256(local.read_bytes()).hexdigest() == \
               hashlib.sha256(text.encode("utf-8")).hexdigest()
        check(f"{ref.split('/')[-1]} 与本地 dist 一致", same)

    print("\n=== 4) 下发的 MonitorView chunk 含本轮 D-2/D-4 关键判定串 ===")
    # MonitorView 是**路由懒加载 chunk**，index.html 不会引用它：
    # 入口 JS 里只有它的文件名，浏览器到访问该路由时才去取。
    # 因此必须在「入口 JS 文本」里找出 chunk 名，再单独请求它。
    entry_text = "\n".join(t for r, t in assets.items() if r.endswith(".js"))
    names = re.findall(r"assets/(MonitorView-[A-Za-z0-9_\-]+\.js)", entry_text)
    check("入口 JS 中存在 MonitorView chunk 引用", bool(names),
          f"找到={sorted(set(names))}" if names else "未找到")
    if not names:
        print("\n（无法定位 MonitorView chunk，后续文案断言无法进行）")
        return 1

    monitor_ref = f"/assets/{names[0]}"
    st, body = get(monitor_ref)
    check(f"GET {monitor_ref} -> 200", st == 200, f"status={st} bytes={len(body)}")
    target = body.decode("utf-8", errors="replace")

    local_mv = DIST / "assets" / names[0]
    if local_mv.is_file():
        check("下发的 MonitorView chunk 与本地 dist 逐字节一致",
              hashlib.sha256(local_mv.read_bytes()).hexdigest() ==
              hashlib.sha256(body).hexdigest())

    # 覆盖率口径三分支 + 信号状态标签映射：都是第 5/8 轮新增的可见文案
    needles = {
        "口径=全市场": "全市场覆盖率文案",
        "池内模式": "UNIVERSE_NOT_FULL_MARKET 分支文案",
        "全市场池构建失败": "FULL_UNIVERSE_UNAVAILABLE 分支文案",
        "口径未标注": "缺失字段兜底文案",
        "观察中": "OBSERVE 默认状态标签",
        "预热不足": "WARMUP 状态标签",
        "已失效": "INVALIDATED 状态标签",
        "不可执行": "预热不足→不可执行文案",
    }
    for needle, desc in needles.items():
        check(f"含「{needle}」（{desc}）", needle in target)

    print("\n=== 5) 下发产物不是改动前的旧构建 ===")
    old_hashes = ("C3fZuN8P", "c6E4O4DZ")
    stale = [h for h in old_hashes if h in html or any(h in r for r in refs)]
    check("不含旧构建哈希", not stale, f"发现={stale}" if stale else "")

    print("\n" + "=" * 60)
    if failures:
        print(f"结果：{len(failures)} 项失败")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("结果：全部通过 —— 8080 实际下发的前端产物确实包含本轮改动")
    return 0


if __name__ == "__main__":
    sys.exit(main())
