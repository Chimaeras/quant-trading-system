# 每日选股打分自动流水线

把「数据管理 → 历史数据更新 + 选股打分工作台 5 个维度」的手动流程自动化：
先拉取最近 N 个交易日行情，再依次对 5 个策略用默认参数打分，最后输出每个维度的 TopN。

## 文件

- `daily_scoring_pipeline.py` — 核心脚本（仅标准库，无第三方依赖）
- `run_daily_scoring.bat` — 双击即可运行的包装

## 运行

双击 `run_daily_scoring.bat`，或命令行：

```bash
cd scripts
python daily_scoring_pipeline.py
```

结果输出到项目根目录 `scoring_results\`：
- `score-picker-<策略>-<交易日>.csv`（每个维度一个,UTF-8-SIG，Excel 打开中文不乱码）
- `score-picker-top20-<交易日>.txt`（五个维度 Top20 汇总摘要）

## 参数

| 参数 | 含义 | 默认 |
|------|------|------|
| `--mode SINGLE\|ALL\|LIST\|MAIN_BOARD` | 数据更新范围 | `ALL` |
| `--days N` | 拉取/覆盖的交易日个数 | `7`（近7个交易日） |
| `--topn N` | 每个维度输出 TopN | `20` |
| `--pool N` | 打分池上限 | `500` |
| `--offline` | 跳过数据更新，直接对已有数据打分 | 关 |
| `--auth` | 开启鉴权登录（docker 部署默认开启） | 关 |

环境变量（可选）：`QTS_BASE_URL`（默认 `http://localhost:8080/api`）、`QTS_USERNAME`、`QTS_PASSWORD`、`QTS_OUTPUT_DIR`、`QTS_AUTH=1`。

> 说明：当前本地 dev 实例鉴权关闭，直接匿名调用即可；docker 部署需加 `--auth` 并在
> 环境变量里给出账号密码（默认 admin / admin123）。

## 5 个维度及其默认参数（与前端工作台一致）

| 策略 | strategy | 关键默认参数 |
|------|----------|--------------|
| 经典多因子 | `CLASSIC_SELECTOR` | fullHistory、topN=500、minTrendScore=40、minListingDays=60 |
| 超跌反弹 | `OVERSOLD_REBOUND` | minTotalScore=60、excludeST |
| 趋势打分 | `TREND` | minTotalScore=45、biasThreshold=5.0 |
| 综合三维度 | `COMPREHENSIVE` | minTotalScore=55、lookbackDays=120 |
| 多维度v1 | `MULTI_DIM` | minTotalScore=55 |

## Windows 定时任务（每天 17:00 自动跑）

1. `Win+R` → `taskschd.msc` 打开任务计划程序。
2. 「创建基本任务」→ 名称 `每日选股打分`。
3. 触发器 → 每天 17:00。
4. 操作 → 启动程序 → 浏览选 `scripts\run_daily_scoring.bat`。
   - 若想后台运行：把「起始于」设为 `scripts` 目录，程序填 `python` 参数填
     `daily_scoring_pipeline.py`。
5. 建议同时配置「已在计划时间运行」时唤醒计算机（可选），并在系统设置里允许任务唤醒。
6. 完成后可查 `scoring_results\` 下的 CSV / TXT。

## 注意

- 打分任务在服务端是**单线程队列**，5 个策略会依次执行，全程耗时取决于行情库规模。
- 服务端会对「同策略 + 同参数 + 同交易日」做指纹复用：当天已手跑出结果时，会命中复用，速度快。
- 数据更新失败会中止打分（避免基于过期数据）；部分策略失败时仍会输出其余策略结果。
