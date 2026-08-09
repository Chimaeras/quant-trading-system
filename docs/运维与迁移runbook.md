# 运维与迁移 Runbook（V2-MP-029）

> 目标：任何一台新机器（`git clone` 后）能按本手册安全地**迁移 schema、备份/恢复数据、启动副本并做健康验收**。
> 环境约束声明：本文所述命令均以副本/本地为准；生产操作前必须在副本演练一遍。

- 数据库：MySQL 8.0，库名 `quant_db`（docker 容器名 `quant-mysql`）
- 迁移框架：Flyway（`locations=classpath:db/migration`，`baseline-on-migrate=true`）
- 迁移脚本位置：`modules/backend-java/src/main/resources/db/migration/V*.sql`（当前 V1…V21）
- 权威初始 schema：`modules/database/01_init_schema.sql`（docker 首次启动经 `/docker-entrypoint-initdb.d` 导入，其余表由 Hibernate `ddl-auto:update` 补齐）

---

## 1. 迁移（Migrate）

### 1.1 新库首次启动（自动迁移）

方式一 · Docker 一键（推荐副本/日常）：

```bash
cd docker
docker-compose up -d --build
```

- MySQL 容器首次启动执行 `01_init_schema.sql` 建权威表；
- backend 容器启动时 Flyway 自动执行 `db/migration/V*.sql` 补齐至最新；
- 健康检查：`docker compose ps` 显示 backend `healthy`（探测 `GET /api/data/overview`）。

方式二 · 本地已装 MySQL（手动迁移）：

```bash
# 建库（首次）
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS quant_db CHARACTER SET utf8mb4;"
# 导入权威 schema（仅首次；已有库跳过，避免与 Flyway 冲突）
mysql -uroot -p quant_db < modules/database/01_init_schema.sql
# 启动后端，Flyway 自动 migrate
cd modules/backend-java && mvn spring-boot:run
```

### 1.2 已有库升级（存量库安全接管）

- Flyway `baseline-on-migrate=true`：若目标库无 `flyway_schema_history` 表，Flyway 会以当前 V1 为 baseline 并跳过 V1 直接执行后续版本。
- 升级命令（进入 backend 后）：

```bash
mvn spring-boot:run          # 启动即 migrate；或
mvn flyway:migrate           # 仅执行迁移，不启动应用
```

- 迁移校验：启动日志出现 `Migrating schema ... to version "Vxx"`，无 ERROR；`SELECT * FROM flyway_schema_history ORDER BY installed_rank;` 最后一行为 success=1。

### 1.3 迁移注意事项

- **禁止手工改历史 `V*.sql`**：一旦被 Flyway 记录，改动会使 checksum 校验失败。需要调整时新增 `V22__...sql`。
- **禁止在迁移脚本里放 DML 业务数据**（除非幂等 + 注释说明）：迁移只做 schema 变更与必要回填。
- 新增列/表一律走 Flyway（Batch 2 出口：存量库安全接管，V2-MP-024）。

---

## 2. 备份（Backup）

### 2.1 全库逻辑备份（推荐每日）

```bash
# Docker 环境
docker exec quant-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --set-gtid-purged=OFF quant_db' \
  > backup/quant_db_$(date +%Y%m%d_%H%M%S).sql
```

### 2.2 关键表分组备份（含业务依赖顺序）

| 组 | 表 | 说明 |
|----|----|------|
| 主数据 | `stock_info`, `security_master_history` | 证券主数据 + PIT 历史 |
| 行情 | `market_data`, `market_price_raw`, `adjust_factor`, `index_quote` | 前复权/不复权/因子/指数 |
| 日历 | `trading_calendar_day` | 交易日历 |
| 选股与打分 | `selector_result`, `selector_result_item`, `stock_trend_score`, 各 scoring 结果表 | 策略结果 |
| 预测与研究 | `prediction_snapshot`, `prediction_snapshot_item`, `label_snapshot`, `model_registry`, `calibrator_registry`, `experiment_run`, `feature_snapshot_manifest`, `feature_snapshot_row`, `rolling_wfo_*` | 概率研究产物 |
| 回测 | `backtest_run` 及组合回测规范化表（V20） | 回测历史 |
| 日报 | `daily_report`（不可变投影，V19） | 报告 |
| 账户 | `sim_account`, `sim_position`, `sim_order`, `sim_session`（V21） | 模拟账户 |
| 快照 | `data_snapshot` | 可复现性注册表 |
| 任务 | `data_update_task`, `research_task` | 任务状态 |

按组备份：

```bash
docker exec quant-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" quant_db market_data' \
  > backup/market_data_$(date +%Y%m%d).sql
```

### 2.3 快照注册表联动

- 重大数据变更前先创建数据快照（`POST /api/data/snapshot/create`），得到 `snapshot_id`（如 `20260807-1`）与内容 hash，作为「变更前状态」的可复现凭证，再执行备份。

### 2.4 备份保留策略

- 每日全量 + 每次迁移前自动快照；保留最近 N 天 + 迁移前快照永不覆盖。

---

## 3. 恢复（Recovery）

### 3.1 全库恢复（副本演练）

```bash
# 1) 停 backend，防止并发写
docker compose stop backend
# 2) 重建库（drop 需谨慎，先确认备份存在）
docker exec quant-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE quant_db; CREATE DATABASE quant_db CHARACTER SET utf8mb4;"'
# 3) 导入备份
docker exec -i quant-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" quant_db' < backup/quant_db_YYYYMMDD_HHMMSS.sql
# 4) 重启并验收
docker compose start backend
```

### 3.2 单表恢复

```bash
docker exec quant-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" quant_db' < backup/market_data_20260809.sql
```

### 3.3 恢复到旧版本 schema（回滚迁移）

- **先备份**当前库。
- 使用迁移前的全量备份恢复（首选）。
- 若仅需撤销最近一次迁移：确认无新数据写入后，手工执行反向 DDL（新增表则 DROP，加列则 DROP COLUMN），并在 `flyway_schema_history` 删除对应行（`DELETE FROM flyway_schema_history WHERE version='Vxx';`）——**仅在确认反向 DDL 后 schema 与旧版本一致时执行**。
- 迁移 runbook 演练最低标准：能在副本完成「全库备份 → 迁移 → 恢复」三连且 `GET /api/data/overview` 返回 200。

---

## 4. 副本启动与验收（Runbook 演练）

### 4.1 副本启动

```bash
git clone <repo> replica && cd replica/docker
docker-compose up -d --build
```

### 4.2 健康验收清单

| 检查 | 命令/路径 | 期望 |
|------|----------|------|
| 后端健康 | `docker compose ps` | backend `healthy` |
| 迁移状态 | `SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;` | 最新 V21 success=1 |
| 系统就绪度 | `GET /api/system/readiness` | status=PASS 或 DEGRADED（含各基座 detail）；`note` 声明不依赖本机时间 |
| 数据门禁 | `GET /api/data/quality/gate` | 返回 gate_v2 与 decision |
| 日历 | `GET /api/data/overview` | 交易日历已初始化 |
| 前端 | 浏览器访问 `http://localhost:8080` | 顶栏健康状态与 API 一致，无「把本机时钟当系统健康」 |

### 4.3 恢复演练脚本（可留档）

```bash
# 1) 创建变更前快照
curl -s -X POST http://localhost:8080/api/data/snapshot/create
# 2) 全库备份
docker exec quant-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction quant_db' > /tmp/before.sql
# 3) 执行一次迁移（升级到新版本）
# 4) 验证 /api/system/readiness 仍 PASS/DEGRADED
# 5) 恢复到 /tmp/before.sql
# 6) 再次验证 /api/system/readiness 与 snapshot/list
```

---

## 5. 运维命令速查

| 操作 | 命令 |
|------|------|
| 启动 | `cd docker && docker-compose up -d --build` |
| 停止 | `docker compose down` |
| 查看日志 | `docker compose logs -f backend` |
| 进入 MySQL | `docker exec -it quant-mysql mysql -uroot -p"$DB_PASSWORD" quant_db` |
| 手动迁移 | `cd modules/backend-java && mvn flyway:migrate` |
| 查看迁移历史 | `SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;` |
| 备份 | 见 §2 |
| 恢复 | 见 §3 |

---

## 6. 已知运维风险（不随文档宣称消除）

1. **ddl-auto:update 与 Flyway 并存**：权威表走 Flyway/init.sql，其余由 Hibernate 补齐；改实体字段可能产生 schema 漂移，需以 Flyway 迁移收敛（V2-MP-024）。
2. **`/api/report/**` 白名单含写端点**（`POST /api/report/daily/realize`）：鉴权开启态下该写操作随只读白名单公开，收敛列为 V2-MP-030 待办。
3. **MySQL 本机端口 3306 直接暴露**：仅限本机/内网；生产应限制来源或改内部端口。
4. **数据源 reliability=limited**：复权因子/交易日历/指数/主数据均来自本地推算或 eastmoney 派生，接入官方源后需重跑验证。
