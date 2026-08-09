"""
量化脚本统一入口（CLI）。

子命令：
    python main.py demo indicators|ic|label-smoke [--demo]
    python main.py research run --config CONFIG --feature-manifest PATH --output-dir DIR [--demo]
    python main.py indicators|ic|label-smoke   # 兼容旧烟雾入口（隐式 DEMO）
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any


REQUIRED_FEATURE_KEYS = ("data_snapshot_id", "feature_set_version", "schema_hash", "columns")


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(f"JSON 不存在: {path.resolve()}")
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"期望 JSON object: {path}")
    return data


def _validate_feature_manifest(manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    for key in REQUIRED_FEATURE_KEYS:
        if key not in manifest or manifest[key] in (None, "", []):
            errors.append(f"missing required key: {key}")
    cols = manifest.get("columns")
    if cols is not None and (not isinstance(cols, list) or not all(isinstance(c, str) and c for c in cols)):
        errors.append("columns must be a non-empty list of strings")
    return errors


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

    assert np.allclose(rsi(pd.Series(close), 14).to_numpy(), java_rsi(close, 14), atol=1e-9), "RSI 与 Java 口径不一致"
    up_only = [10 + i * 0.5 for i in range(30)]
    assert rsi(pd.Series(up_only), 14).iloc[-1] == 100.0, "RSI 强上涨应=100"
    down_only = [30 - i * 0.5 for i in range(30)]
    assert rsi(pd.Series(down_only), 14).iloc[-1] == 0.0, "RSI 强下跌应=0"

    print("指标口径自检通过（EMA/MACD/BOLL/RSI 与 Java TechnicalIndicators 一致）[DEMO]")
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
    factor = close.pct_change(5)
    fwd = forward_return(close, 5)
    ic = rank_ic(factor, fwd)
    print(f"[DEMO] Rank IC (mom5 vs T+5 fwd, n={n}): {ic:.4f}")
    print("[DEMO] 分组收益（等频 5 组）：")
    print(group_returns(factor, fwd, 5).to_string(index=False))
    return 0


def cmd_label_smoke(_args) -> int:
    """T+2/T+3 前瞻标签构造烟雾测试。"""
    import numpy as np
    import pandas as pd

    from research.labels import direction_label, forward_return

    rng = np.random.default_rng(0)
    close = pd.Series(10.0 + np.cumsum(rng.standard_normal(50) * 0.1))
    for h in (2, 3):
        fwd = forward_return(close, h)
        up = direction_label(close, h, 0.0)
        print(f"[DEMO] --- horizon T+{h} ---")
        print(f"尾部 NaN 行数: {fwd.isna().sum()} (应 = {h})")
        print(f"非NaN 上涨占比: {up.dropna().mean():.3f}")
    return 0


def cmd_research_run(args) -> int:
    """离线研究 runner：校验 feature manifest，写出 model_manifest + metrics。

    fail-closed 契约（V2-MP-011 P0 修复）：
    - demo 模式：允许缺数据，产物必须带 DEMO 水印且 production_eligible=false；
    - 正式模式（非 demo）：强制 data_path 存在且可读、schema 契约兼容；
      由于真实训练/拟合尚未实现（offline_stub），一律返回非零退出码且不产出任何
      可被注册为 production 的 model_manifest —— 绝不出现「无数据 status=ok」。
    """
    config_path = Path(args.config)
    feature_manifest_path = Path(args.feature_manifest)
    output_dir = Path(args.output_dir)
    demo = bool(args.demo)

    try:
        config = _load_json(config_path)
        feature_manifest = _load_json(feature_manifest_path)
    except FileNotFoundError as e:
        print(f"ERROR: data missing — {e}", file=sys.stderr)
        return 2

    errors = _validate_feature_manifest(feature_manifest)
    if errors:
        print("ERROR: feature_manifest contract incompatible:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 3

    columns = list(feature_manifest["columns"])
    schema_hash = str(feature_manifest["schema_hash"])
    feature_set_version = str(feature_manifest["feature_set_version"])
    data_snapshot_id = str(feature_manifest["data_snapshot_id"])
    model_version = str(config.get("model_version") or f"py_research_{feature_set_version}")
    algorithm = str(config.get("algorithm") or "offline_stub")
    label_version = str(
        config.get("label_version")
        or feature_manifest.get("label_version")
        or "unspecified"
    )

    # data_path 是正式模式硬性要求：缺数据或文件不存在 → 失败且不产 model
    data_path = config.get("data_path") or feature_manifest.get("data_path")
    data_file = Path(str(data_path)) if data_path else None

    if not demo:
        if data_file is None:
            print(
                "ERROR: formal research run requires data_path "
                "(config.data_path or feature_manifest.data_path); "
                "no data → no model (fail-closed)",
                file=sys.stderr,
            )
            return 2
        if not data_file.exists():
            print(f"ERROR: data missing — {data_file.resolve()}", file=sys.stderr)
            return 2
        if data_file.is_dir():
            print(f"ERROR: data_path points to a directory, expected a data file — {data_file.resolve()}", file=sys.stderr)
            return 2
        # 真实拟合尚未实现：禁止以 offline_stub 冒充正式训练成功（fail-closed）。
        # 此时即使数据齐全也拒绝产出 production-eligible 模型，直到训练器落地。
        print(
            "ERROR: formal training pipeline not implemented (algorithm=offline_stub); "
            "refusing to emit a production-eligible model. Use --demo for stub artifacts "
            "that are clearly watermarked and never production-eligible.",
            file=sys.stderr,
        )
        return 4

    # ---- 以下仅 demo 模式可达：产物一律打 DEMO 水印，禁止注册为 production ----
    if data_file is not None and not data_file.exists():
        print(f"[DEMO] data_path missing, continuing with synthetic smoke: {data_file}")

    # Stub metrics：真实训练留给后续；demo 产物仅为契约校验，绝不标注 production_eligible
    metrics: dict[str, Any] = {
        "status": "ok",
        "sample_size": int(config.get("sample_size") or 0),
        "note": "MP-021 stub runner (DEMO) — metrics placeholder until full train pipeline",
        "feature_set_version": feature_set_version,
        "schema_hash": schema_hash,
        "run_mode": "demo",
        "production_eligible": False,
        "DEMO": True,
        "watermark": "DEMO",
    }

    artifact_payload = {
        "weights": config.get("weights") or [0.0] * len(columns),
        "bias": float(config.get("bias") or 0.0),
        "features": columns,
        "run_mode": "demo",
        "production_eligible": False,
        "DEMO": True,
        "watermark": "DEMO",
    }
    artifact_hash = hashlib.sha256(
        json.dumps(artifact_payload, sort_keys=True).encode("utf-8")
    ).hexdigest()

    model_manifest: dict[str, Any] = {
        "data_snapshot_id": data_snapshot_id,
        "feature_set_version": feature_set_version,
        "schema_hash": schema_hash,
        "columns": columns,
        "model_version": model_version,
        "algorithm": algorithm,
        "label_version": label_version,
        "code_version": str(config.get("code_version") or "compute-python"),
        "artifact_hash": artifact_hash,
        "run_mode": "demo",
        "production_eligible": False,
        "demo": True,
        "DEMO": True,
        "watermark": "DEMO",
        "train_window": config.get("train_window") or {},
        "calibration_window": config.get("calibration_window") or {},
        "test_window": config.get("test_window") or {},
        "metrics_uri": "metrics.json",
    }

    output_dir.mkdir(parents=True, exist_ok=True)
    _write_json(output_dir / "metrics.json", metrics)
    _write_json(output_dir / "model_manifest.json", model_manifest)
    _write_json(output_dir / "artifact_stub.json", artifact_payload)

    print(f"research run wrote DEMO artifacts to {output_dir.resolve()}")
    print("[DEMO] watermark applied — NOT production-eligible; Java importer must reject")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="量化脚本统一入口（demo / research）")
    sub = parser.add_subparsers(dest="cmd")

    # --- demo group ---
    demo_parser = sub.add_parser("demo", help="烟雾/演示命令（产物带 DEMO 水印）")
    demo_sub = demo_parser.add_subparsers(dest="demo_cmd")
    demo_sub.add_parser("indicators", help="指标口径自检")
    demo_sub.add_parser("ic", help="合成数据 Rank IC 烟雾")
    demo_sub.add_parser("label-smoke", help="标签构造烟雾")

    # --- research group ---
    research_parser = sub.add_parser("research", help="离线研究 runner")
    research_sub = research_parser.add_subparsers(dest="research_cmd")
    run_parser = research_sub.add_parser("run", help="读取 config + feature_manifest，写出 model_manifest")
    run_parser.add_argument("--config", required=True, help="研究配置 JSON")
    run_parser.add_argument("--feature-manifest", required=True, help="feature_manifest.json 路径")
    run_parser.add_argument("--output-dir", required=True, help="产物输出目录")
    run_parser.add_argument(
        "--demo",
        action="store_true",
        help="允许缺数据并给产物打 DEMO 水印（禁止注册为 production）",
    )

    # 兼容旧入口（视为 demo）
    sub.add_parser("indicators", help="[兼容] 指标口径自检（DEMO）")
    sub.add_parser("ic", help="[兼容] Rank IC 烟雾（DEMO）")
    sub.add_parser("label-smoke", help="[兼容] 标签烟雾（DEMO）")

    args = parser.parse_args(argv)

    if args.cmd == "demo":
        if args.demo_cmd == "indicators":
            return cmd_indicators(args)
        if args.demo_cmd == "ic":
            return cmd_ic(args)
        if args.demo_cmd == "label-smoke":
            return cmd_label_smoke(args)
        demo_parser.print_help()
        return 2

    if args.cmd == "research":
        if args.research_cmd == "run":
            return cmd_research_run(args)
        research_parser.print_help()
        return 2

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
