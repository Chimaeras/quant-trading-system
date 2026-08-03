# 项目目录结构

> ⚠ 本文件已过时（引用部分已不存在的文件/目录）。实际结构以 [README.md](README.md) 与 [docs/审查发现与修复记录.md](docs/审查发现与修复记录.md) 为准。

```
quant-trading-system/
├── modules/
│   ├── web/                     # 前端 (Vue3)
│   ├── src/
│   │   ├── views/              # 页面
│   │   │   ├── dashboard/      # 行情看板
│   │   │   ├── strategy/       # 策略管理
│   │   │   ├── backtest/       # 回测分析
│   │   │   ├── trading/        # 实盘监控
│   │   │   └── account/        # 账户资产
│   │   ├── components/
│   │   ├── api/
│   │   ├── store/
│   │   └── router/
│   ├── package.json
│   └── vite.config.js
│
│
│   ├── backend-java/            # 后端 (Spring Boot)
│   ├── src/main/java/com/quant/
│   │   ├── controller/
│   │   ├── service/
│   │   ├── repository/
│   │   ├── entity/
│   │   ├── dto/
│   │   ├── config/
│   │   ├── security/
│   │   └── exception/
│   ├── src/main/resources/
│   │   └── application.yml
│   └── pom.xml
│
│
│   ├── compute-python/          # 量化引擎 (Python)
│   ├── strategies/
│   │   ├── base_strategy.py
│   │   ├── ma_cross_strategy.py
│   │   ├── boll_strategy.py
│   │   └── macd_strategy.py
│   ├── engines/
│   │   ├── backtest_engine.py
│   │   ├── data_engine.py
│   │   ├── signal_engine.py
│   │   └── risk_engine.py
│   ├── utils/
│   ├── requirements.txt
│   └── main.py
│
│   └── database/                # 数据库基础脚本
│       ├── 01_init_schema.sql
│       ├── 02_additional_indexes.sql
│       ├── 03_legacy_tables.sql
│       ├── init_db.sql
│       ├── create_db.sql
│       ├── stock_info_import.sql
│       ├── migrate_stock_daily.py
│       ├── import_stock_mapping.py
│       ├── generate_stock_insert_sql.py
│       └── run_all.ps1
│
├── docker/
│   ├── docker-compose.yml
│   ├── Dockerfile.backend
│   ├── Dockerfile.frontend
│   ├── Dockerfile.quant
│   └── nginx.conf
│
├── docs/
│   ├── 安装指南.md
│   ├── 策略开发指南.md
│   ├── API文档.md
│   └── 开发规范.md
│
└── README.md
```
