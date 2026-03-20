<template>
  <div class="dashboard">
    <div class="stat-cards">
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">总资产</div>
        <div class="stat-value">¥{{ formatNumber(accountInfo.totalAsset) }}</div>
        <div class="stat-extra">📈 +2.5%</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">今日盈亏</div>
        <div class="stat-value green">+¥{{ formatNumber(accountInfo.todayPnl) }}</div>
        <div class="stat-extra">📈 +5.2%</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">持仓市值</div>
        <div class="stat-value">¥{{ formatNumber(accountInfo.positionValue) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">可用资金</div>
        <div class="stat-value">¥{{ formatNumber(accountInfo.cash) }}</div>
      </el-card>
      <el-card shadow="hover" class="stat-card">
        <div class="stat-label">持仓数量</div>
        <div class="stat-value">{{ accountInfo.positionCount ?? positions.length }}</div>
      </el-card>
    </div>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="16">
        <el-card shadow="hover">
          <template #header>持仓收益趋势</template>
          <div ref="chartRef" style="height: 300px"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover">
          <template #header>持仓分布</template>
          <div ref="pieRef" style="height: 300px"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="hover" style="margin-top: 20px">
      <template #header>实时行情</template>
      <el-table :data="positions" stripe style="width: 100%">
        <el-table-column prop="symbol" label="代码" width="100" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="currentPrice" label="最新价" />
        <el-table-column label="涨跌幅">
          <template #default="{ row }">
            <span :class="getChangeClass(row)">{{ getChangePercent(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="volumeStr" label="成交量" />
        <el-table-column prop="volume" label="持仓量" />
        <el-table-column label="操作" width="100">
          <template #default>
            <el-button type="danger" size="small">卖出</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as echarts from 'echarts'
import { getAccountInfo } from '@/api/account'
import { getPositionList } from '@/api/position'

const accountInfo = ref({
  totalAsset: 500000,
  cash: 150000,
  positionValue: 350000,
  todayPnl: 12500,
  positionCount: 15
})
const positions = ref([])
const chartRef = ref()
const pieRef = ref()

const formatNumber = (n) => {
  if (n == null) return '0'
  return Number(n).toLocaleString()
}
const getChangePercent = (row) => {
  if (!row.avgPrice || !row.currentPrice) return '-'
  const p = ((row.currentPrice - row.avgPrice) / row.avgPrice * 100).toFixed(2)
  return (p >= 0 ? '+' : '') + p + '%'
}
const getChangeClass = (row) => {
  if (!row.avgPrice || !row.currentPrice) return ''
  return row.currentPrice >= row.avgPrice ? 'green' : 'red'
}

onMounted(async () => {
  try {
    const acc = await getAccountInfo()
    accountInfo.value = acc.data
  } catch (e) {
    // use default
  }
  try {
    const pos = await getPositionList()
    positions.value = pos.data || []
  } catch (e) {
    positions.value = [
      { symbol: '000001', name: '平安银行', volume: 1000, avgPrice: 12.5, currentPrice: 12.8, volumeStr: '100万' },
      { symbol: '600519', name: '贵州茅台', volume: 100, avgPrice: 1750, currentPrice: 1800, volumeStr: '50万' },
      { symbol: '000858', name: '五粮液', volume: 500, avgPrice: 175, currentPrice: 180.5, volumeStr: '80万' }
    ]
  }

  const lineChart = echarts.init(chartRef.value)
  lineChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: ['1月', '2月', '3月', '4月', '5月', '6月'] },
    yAxis: { type: 'value' },
    series: [{ data: [450000, 470000, 465000, 490000, 505000, 500000], type: 'line', smooth: true }],
    grid: { left: 50, right: 20, top: 20, bottom: 30 }
  })

  const pieChart = echarts.init(pieRef.value)
  pieChart.setOption({
    tooltip: { trigger: 'item' },
    series: [{
      type: 'pie',
      radius: '60%',
      data: [
        { value: 60, name: '股票' },
        { value: 25, name: '基金' },
        { value: 15, name: '现金' }
      ]
    }]
  })
})
</script>

<style scoped>
.stat-cards {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}
.stat-card {
  background: #1d1e1f;
  border: 1px solid #2a2b2c;
}
.stat-card :deep(.el-card__body) {
  color: #e4e7ed;
}
.stat-label {
  font-size: 12px;
  color: #888;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 22px;
  font-weight: bold;
}
.stat-value.green {
  color: #67c23a;
}
.stat-extra {
  font-size: 12px;
  color: #67c23a;
  margin-top: 4px;
}
.green { color: #67c23a; }
.red { color: #f56c6c; }
</style>
