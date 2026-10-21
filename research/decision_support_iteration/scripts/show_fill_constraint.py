"""读取 fill_constraint.json 并打印摘要（避免 PowerShell 内联引号问题）。"""
import json
import pathlib
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
p = pathlib.Path(__file__).resolve().parents[1] / "fill_constraint.json"
d = json.loads(p.read_text(encoding="utf-8"))

print("=== coverage_and_fill ===")
for k, v in d["coverage_and_fill"].items():
    print(f"  {k:34} {v}")

print("=== open_vs_limit quantiles (开盘价/理论涨停价) ===")
for k, v in d["open_vs_limit_quantiles"].items():
    print(f"  q{k:<5} {v:.4f}")

print("=== gap_open quantiles (T+1 开盘 / T 收盘 - 1) ===")
for k, v in d["gap_open_quantiles"].items():
    print(f"  q{k:<5} {v:+.5f}")
print(f"  mean  {d['gap_open_mean']:+.5f}")

print("=== slippage ===")
for r in d["slippage"]:
    lo, hi = r["ci95_block_bootstrap"]
    print(f"  {r['slippage']*100:>4.1f}%  days={r['days']:>3} "
          f"mean={r['mean_net_ret']:+.5f} ci=[{lo:+.5f},{hi:+.5f}] "
          f"win={r['win_rate_daily']:.3f}")

print("=== gap_filter ===")
for k, v in d["gap_filter"].items():
    lo, hi = v["ci95_block_bootstrap"]
    print(f"  {k:18} days={v['days']:>3} dropped={v['dropped_picks']:>3} "
          f"mean={v['mean_net_ret']:+.5f} ci=[{lo:+.5f},{hi:+.5f}]")

b = d["baseline_no_slippage"]
print("=== baseline_no_slippage (口径校验) ===")
print(f"  days={b['days']} mean={b['mean_net_ret']!r}")
print("  冻结基线 B0 mean_net_ret = 0.015303550444946206")
print("  一致:", d["baseline_matches_frozen"])

print("=== slippage_paired_vs_zero (同批日、同批标的，只有入场价不同) ===")
print("  注：这是**机械恒等式**（entry 同乘 (1+s)），不是统计发现。")
print("      置信区间退化为极窄是结构性的，不可读成「显著」。")
for r in d["slippage_paired_vs_zero"]:
    print(f"  {r['slippage']*100:>4.1f}%  days={r['days']:>3} "
          f"mean_diff={r['mean_diff']:+.5f}")

print(f"=== 保本入场滑点 ≈ {d['breakeven_slippage_approx']*100:.2f}% "
      f"（超过此值组合均值转负）===")

print("=== gap_filter（剔除高开标的；paired 为同批日配对差）===")
for k, v in d["gap_filter"].items():
    p = v["paired_vs_unfiltered"]
    lo, hi = p["ci95_block_bootstrap"]
    verdict = "无显著差异" if lo <= 0 <= hi else "显著差异"
    print(f"  {k:18} dropped={v['dropped_picks']:>3} mean={v['mean_net_ret']:+.5f} "
          f"paired_diff={p['mean_diff']:+.5f} ci=[{lo:+.5f},{hi:+.5f}]  {verdict}")
