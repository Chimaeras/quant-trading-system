"""
量化脚本统一入口（CLI）。

当前模块独立于 Java 后端运行，用于指标口径验证与策略研究。
示例：
    python main.py indicators   # 运行指标口径对拍自检
    python main.py --help
"""
from __future__ import annotations

import argparse
import sys


def cmd_indicators(_args) -> int:
    """运行技术指标口径自检（EMA/MACD/BOLL 与 Java 口径一致的断言）。"""
    import numpy as np
    import pandas as pd

    from utils.indicators import bollinger_bands, ema, macd

    def java_ema(values, span):
        alpha = 2.0 / (span + 1)
        out = [0.0] * len(values)
        seed_len = min(span, len(values))
        out[0] = sum(values[:seed_len]) / seed_len
        for i in range(1, len(values)):
            out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
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

    print("指标口径自检通过（EMA/MACD/BOLL 与 Java TechnicalIndicators 一致）")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="量化脚本统一入口")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("indicators", help="指标口径自检")
    args = parser.parse_args(argv)

    if args.cmd == "indicators":
        return cmd_indicators(args)
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
