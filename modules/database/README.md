# 数据库脚本说明

本目录存放量化交易系统所有数据库初始化及维护脚本。

## 执行顺序

1. **01_init_schema.sql** - 主架构（必执行）
   - 创建 quant_db 数据库
   - 创建股票信息、行情、技术指标、交易记录、策略、回测、用户、账户、持仓等表

2. **02_additional_indexes.sql** - 性能优化索引（可选）
   - 常用查询组合索引

3. **03_legacy_tables.sql** - 兼容旧版表结构（可选）
   - 若 Spring Boot 应用使用 account/position/order/strategy 表，需执行

## 执行方式

```bash
# 方式一：命令行
mysql -u root -p < modules/database/01_init_schema.sql
mysql -u root -p quant_db < modules/database/02_additional_indexes.sql
mysql -u root -p quant_db < modules/database/03_legacy_tables.sql

# 方式二：进入 MySQL 后
source E:/Project/quant-trading-system/modules/database/01_init_schema.sql;
```

## 字段命名说明

- **market** 已统一改为 **exchange_type**（交易所类型，如 SZSE/SSE/US/HK）
- **market_name** 已改为 **exchange_type_name**
- **market_list** 已改为 **exchange_list**

## 数据导入

### A股代码映射表导入 (stock_info)

将 `A股代码名称映射表_*.csv`（字段：code, name, market）导入到 `stock_info` 表。

**方式一：Python 脚本（推荐）**

```bash
# 需先安装: pip install pymysql
python modules/database/import_stock_mapping.py "e:\Download\A股代码名称映射表_20260227.csv"
```

**方式二：生成 SQL 后执行**

```bash
# 生成 SQL 文件
python modules/database/generate_stock_insert_sql.py "e:\Download\A股代码名称映射表_20260227.csv"

# 执行导入
mysql -u root -p quant_db < modules/database/stock_info_import.sql
```

**市场映射关系：**

| CSV market | exchange_type | exchange_type_name |
|------------|---------------|---------------------|
| 深市主板   | SZSE          | 深交所              |
| 创业板     | SZSE          | 深交所              |
| 沪市主板   | SSE           | 上交所              |
| 科创板     | SSE           | 上交所              |
| 北交所     | BSE           | 北交所              |

### query.stock_daily 迁移到 quant_db

将 `query.stock_daily` 表迁移到 quant_db 的 stock_info、market_data、technical_indicators。

**字段映射：**

| query.stock_daily | quant_db.market_data | quant_db.technical_indicators |
|-------------------|----------------------|------------------------------|
| init_date (20240102) | trade_date (DATE) | trade_date |
| stock_code | stock_code | stock_code |
| - | exchange_type (由代码推断) | exchange_type |
| open_price, high_price, low_price, close_price | 同名 | - |
| change | → pre_close (close-change) | - |
| change_percent | change_pct | - |
| volume | volume | - |
| volume_balance | amount | - |
| turnover_rate | turnover_rate | - |
| ma5, ma10, ma20, ma30 | - | 同名 |
| rsi14 | - | rsi14 |

**执行：**

```bash
# 依赖: pip install pymysql
python modules/database/migrate_stock_daily.py

# 测试(仅迁移2000条)
python modules/database/migrate_stock_daily.py --limit 2000

# 预检查(不写入)
python modules/database/migrate_stock_daily.py --dry-run
```

环境变量可覆盖数据库连接：`SRC_DB_HOST`, `SRC_DB_PASSWORD` 等。

## 后续扩展

可在此目录新增脚本，建议命名：04_xxx.sql, 05_xxx.sql ...
