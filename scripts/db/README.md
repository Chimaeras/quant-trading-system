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
mysql -u root -p < scripts/db/01_init_schema.sql
mysql -u root -p quant_db < scripts/db/02_additional_indexes.sql
mysql -u root -p quant_db < scripts/db/03_legacy_tables.sql

# 方式二：进入 MySQL 后
source E:/Project/quant-trading-system/scripts/db/01_init_schema.sql;
```

## 字段命名说明

- **market** 已统一改为 **exchange_type**（交易所类型，如 SZSE/SSE/US/HK）
- **market_name** 已改为 **exchange_type_name**
- **market_list** 已改为 **exchange_list**

## 后续扩展

可在此目录新增脚本，建议命名：04_xxx.sql, 05_xxx.sql ...
