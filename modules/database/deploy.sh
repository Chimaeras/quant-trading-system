#!/bin/bash
# 量化交易系统 - 一键部署脚本

set -e

echo "=========================================="
echo "  量化交易系统 - Docker 一键部署"
echo "=========================================="

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "错误: 未安装 Docker，请先安装 Docker"
    exit 1
fi

if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo "错误: 未安装 Docker Compose"
    exit 1
fi

# 项目根目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

echo ""
echo "1. 启动数据库服务 (MySQL, Redis, InfluxDB)..."
cd docker
if docker compose version &> /dev/null; then
    docker compose up -d mysql redis influxdb
else
    docker-compose up -d mysql redis influxdb
fi

echo ""
echo "2. 等待数据库就绪 (30秒)..."
sleep 30

echo ""
echo "3. 启动所有服务..."
if docker compose version &> /dev/null; then
    docker compose up -d
else
    docker-compose up -d
fi

echo ""
echo "=========================================="
echo "  部署完成!"
echo "=========================================="
echo ""
echo "访问地址:"
echo "  前端: http://localhost"
echo "  后端: http://localhost:8080/api"
echo "  InfluxDB: http://localhost:8086"
echo ""
echo "默认账号: admin / 123456"
echo ""
echo "查看日志: docker compose logs -f"
echo "停止服务: docker compose down"
echo ""
