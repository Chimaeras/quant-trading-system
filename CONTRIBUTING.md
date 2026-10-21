# 参与贡献

欢迎提交可复现的问题、文档改进和代码修复。请先检查现有 Issue，说明使用版本、复现步骤、期望行为和实际结果。

## 开发环境

- Java 21、Maven 3.9+、Node.js 18+、MySQL 8、Python 3.9+。
- Docker 部署先复制 `docker/.env.example` 为 `docker/.env` 并填写必需配置。
- 本地后端读取环境变量 `DB_PASSWORD`、`DB_USERNAME`；不要把真实凭据写入源码。

## 验证

```bash
# Java 单元测试（不启动真实服务）
mvn -pl modules/backend-java -Dquant.skipFrontendBuild=true test
# 前端类型检查和构建
cd modules/web
npm ci
npm run build
# Python：在模块目录下创建虚拟环境并安装开发依赖后运行
cd ../compute-python
python -m pytest
```

根据改动范围运行相关检查，在 PR 中说明结果。涉及评分、标签、交易费用或回测口径的改动，应附可复现输入、前后对比和防止未来数据泄漏的说明。

## 提交内容

不要提交 `.env`、访问令牌、数据库口令、账户响应、个人自选列表、日志、下载的论文或行情数据、`node_modules` 和本机 IDE/AI 会话配置。示例请使用虚构数据。

保持变更聚焦，并在提交前检查暂存区。贡献代码沿用项目 MIT 许可；第三方内容应保留原有许可和来源。
