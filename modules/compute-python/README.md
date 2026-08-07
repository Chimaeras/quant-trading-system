# compute-python（离线研究）

与 Java 后端通过 **feature/model manifest** 协作（MP-012/021）。完整 Parquet 特征矩阵流水线为后续工作；当前提供契约层与 CLI stub。

## CLI

```bash
# 演示烟雾（产物带 DEMO 水印）
python main.py demo indicators
python main.py demo ic
python main.py demo label-smoke

# 正式研究 runner（缺数据 exit 2；契约失败 exit 3）
python main.py research run \
  --config /path/to/config.json \
  --feature-manifest /path/to/feature_manifest.json \
  --output-dir /path/to/out

# 显式 demo：允许缺数据并写 DEMO 标志
python main.py research run --config ... --feature-manifest ... --output-dir ... --demo
```

## 契约

- `contracts/feature_manifest.schema.json`
- `contracts/model_manifest.schema.json`

必填字段：`data_snapshot_id`、`feature_set_version`、`schema_hash`、`columns`。

生产成交标签以 Java `LabelFactoryService` 为准；本仓库 `research/labels.py` 中 `direction_label` 为 close-to-close，`traded_net_return_label` 仅为文档化近似。

## 依赖

见 `requirements.txt`。正式环境请使用 lock 文件锁定版本。
