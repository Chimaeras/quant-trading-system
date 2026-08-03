"""
量化脚本统一入口（CLI）。

当前模块独立于 Java 后端运行，用于指标口径验证与短周期研究。
子命令：
    python main.py indicators    # 指标口径自检（EMA/MACD/BOLL/RSI 与 Java 一致）
    python main.py ic            # 合成数据 Rank IC 烟雾测试
    python main.py label-smoke   # T+2/T+3 前瞻标签构造烟雾测试
    python main.py --help
"""
from __future__ import annotations

import argparse
import sys


def cmd_indicators(_args) -> int:
    """运行技术指标口径自检（EMA/MACD/BOLL/RSI 与 Java TechnicalIndicators 一致的断言）。"""
    import numpy as np
    import pandas as pd

    from utils.indicators import bollinger_bands, ema, macd, rsi

    def java_ema(values, span):
        alpha = 2.0 / (span + 1)
        out = [0.0] * len(values)
        seed_len = min(span, len(values))
        out[0] = sum(values[:seed_len]) / seed_len
        for i in range(1, len(values)):
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
        return out

    def java_rsi(close, period):
        n = len(close)
        out = [50.0] * n
        if n <= period:
            return out
        avg_gain = avg_loss = 0.0
        for i in range(1, period + 1):
            d = close[i] - close[i - 1]
            if d > 0:
                avg_gain += d
            else:
                avg_loss += -d
        avg_gain /= period
        avg_loss /= period
        for i in range(period, n):
            if i > period:
                d = close[i] - close[i - 1]
                avg_gain = (avg_gain * (period - 1) + max(d, 0.0)) / period
                avg_loss = (avg_loss * (period - 1) + max(-d, 0.0)) / period
            if avg_loss < 1e-12:
                out[i] = 100.0
            elif avg_gain < 1e-12:
                out[i] = 0.0
            else:
                out[i] = 100.0 - 100.0 / (1.0 + avg_gain / avg_loss)
        return out

    close = [10 + i * 0.1 + np.sin(i) for i in range(60)]
    py_ema = ema(pd.Series(close), 12)
    assert np.allclose(py_ema, java_ema(close, 12), atol=1e-9), "EMA 与 Java 口径不一致"

    dif_line, dea_line, hist = macd(pd.Series(close), 12, 26, 9)
    java_dif = np.array(java_ema(close, 12)) - np.array(java_ema(close, 26))
    java_dea = np.array(java_ema(java_dif.tolist(), 9))
    assert np.allclose(hist, (java_dif - java_dea) * 2, atol=1e-9), "MACD 柱线与 Java 口径不一致"

    upper, ma, lower = bollinger_bands(pd.Series(close), 20, 2.0)
    window = close[-20:]
    exp_ma = sum(window) / 20
    exp_std = (sum((p - exp_ma) ** 2 for p in window) / 19) ** 0.5
    assert abs(ma.iloc[-1] - exp_ma) < 1e-9, "BOLL 中轨不一致"
    assert abs(upper.iloc[-1] - (exp_ma + 2 * exp_std)) < 1e-9, "BOLL 上轨（样本标准差）不一致"

    # RSI：对拍 Wilder 递推 + 强上涨/强下跌边界
    assert np.allclose(rsi(pd.Series(close), 14).to_numpy(), java_rsi(close, 14), atol=1e-9), "RSI 与 Java 口径不一致"
    up_only = [10 + i * 0.5 for i in range(30)]
    assert rsi(pd.Series(up_only), 14).iloc[-1] == 100.0, "RSI 强上涨应=100"
    down_only = [30 - i * 0.5 for i in range(30)]
    assert rsi(pd.Series(down_only), 14).iloc[-1] == 0.0, "RSI 强下跌应=0"

    print("指标口径自检通过（EMA/MACD/BOLL/RSI 与 Java TechnicalIndicators 一致）")
    return 0


def cmd_ic(_args) -> int:
    """合成数据 Rank IC 烟雾测试：5 日动量因子 vs T+5 前瞻收益。"""
    import numpy as np
    import pandas as pd

    from research.evaluation import group_returns, rank_ic
    from research.labels import forward_return

    rng = np.random.default_rng(42)
    n = 300
    close = pd.Series(10.0 + np.cumsum(rng.standard_normal(n) * 0.2))
    factor = close.pct_change(5)  # 5 日动量
    fwd = forward_return(close, 5)
    ic = rank_ic(factor, fwd)
    print(f"Rank IC (mom5 vs T+5 fwd, n={n}): {ic:.4f}")
    print("分组收益（等频 5 组）：")
    print(group_returns(factor, fwd, 5).to_string(index=False))
    return 0


def cmd_label_smoke(_args) -> int:
    """T+2/T+3 前瞻标签构造烟雾测试：打印尾部 NaN 行数与上涨占比。"""
    import numpy as np
    import pandas as pd

    from research.labels import forward_return, label_up

    rng = np.random.default_rng(0)
    close = pd.Series(10.0 + np.cumsum(rng.standard_normal(50) * 0.1))
    for h in (2, 3):
        fwd = forward_return(close, h)
        up = label_up(close, h, 0.0)
        print(f"--- horizon T+{h} ---")
        print(f"尾部 NaN 行数: {fwd.isna().sum()} (应 = {h})")
        print(f"非NaN 上涨占比: {up.dropna().mean():.3f}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="量化脚本统一入口")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("indicators", help="指标口径自检（EMA/MACD/BOLL/RSI 与 Java 对拍）")
    sub.add_parser("ic", help="合成数据 Rank IC 烟雾测试")
    sub.add_parser("label-smoke", help="T+2/T+3 前瞻标签构造烟雾测试")
    args = parser.parse_args(argv)

    if args.cmd == "indicators":
        return cmd_indicators(args)
    if args.cmd == "ic":
        return cmd_ic(args)
    if args.cmd == "label-smoke":
        return cmd_label_smoke(args)
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
