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

    <el-card shadow="hover" style="margin-top: 20px" class="realtime-card">
      <template #header>
        <div class="realtime-card-header">
          <span>实时行情</span>
          <div class="realtime-header-actions">
          <el-input v-model="inputCode" placeholder="股票代码" style="width: 110px" size="small" clearable @keyup.enter="addStock" />
          <el-select v-model="inputExchange" placeholder="交易所" style="width: 95px" size="small" clearable>
            <el-option label="自动" value="" />
            <el-option label="上交所" value="SSE" />
            <el-option label="深交所" value="SZSE" />
            <el-option label="北交所" value="BSE" />
          </el-select>
          <el-button type="primary" size="small" @click="addStock">添加</el-button>
          <el-button type="primary" size="small" :loading="quoteLoading" @click="fetchAllQuotes">刷新</el-button>
          </div>
        </div>
      </template>
      <div class="realtime-tip">持仓股票 + 实时盯盘自选 · 数据来自实时接口</div>
      <el-table :data="realtimeTableData" stripe style="width: 100%" class="realtime-table">
        <el-table-column prop="symbol" label="代码" min-width="85" />
        <el-table-column prop="name" label="名称" min-width="100" />
        <el-table-column label="最新价" min-width="90">
          <template #default="{ row }">
            <span :class="getPriceClass(row)">{{ formatPrice(row.currentPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="涨跌幅" min-width="90">
          <template #default="{ row }">
            <span :class="getPriceClass(row)">{{ formatChangePct(row.changePct) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="成交量" min-width="90">
          <template #default="{ row }">{{ formatVolume(row.volume) }}</template>
        </el-table-column>
        <el-table-column label="持仓量" min-width="85">
          <template #default="{ row }">{{ row.holdingVolume ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="来源" min-width="75">
          <template #default="{ row }">
            <el-tag v-if="row.isPosition" type="success" size="small">持仓</el-tag>
            <el-tag v-else type="info" size="small">自选</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goKline(row)">K线</el-button>
            <el-button link type="danger" size="small" @click="removeStock(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { getAccountInfo } from '@/api/account'
import { getPositionList } from '@/api/position'
import { marketApi } from '@/api/market'

const WATCH_LIST_KEY = 'realtime_watch_list'
const HIDDEN_POSITIONS_KEY = 'dashboard_hidden_positions'

const router = useRouter()
const inputCode = ref('')
const inputExchange = ref('')
const accountInfo = ref({
  totalAsset: 500000,
  cash: 150000,
  positionValue: 350000,
  todayPnl: 12500,
  positionCount: 15
})
const positions = ref([])
const watchList = ref([])
const quoteMap = ref({})
const quoteLoading = ref(false)
const removeTrigger = ref(0)
const chartRef = ref()
const pieRef = ref()

const formatNumber = (n) => {
  if (n == null) return '0'
  return Number(n).toLocaleString()
}
const formatPrice = (v) => {
  if (v == null) return '--'
  return Number(v).toFixed(2)
}
const formatChangePct = (v) => {
  if (v == null) return '--'
  const p = Number(v).toFixed(2)
  return (p >= 0 ? '+' : '') + p + '%'
}
const formatVolume = (v) => {
  if (v == null) return '--'
  const n = Number(v)
  if (n >= 1e8) return (n / 1e8).toFixed(2) + '亿'
  if (n >= 1e4) return (n / 1e4).toFixed(2) + '万'
  return String(n)
}
const getPriceClass = (row) => {
  const pct = row.changePct
  if (pct == null) return ''
  return pct >= 0 ? 'up' : 'down'
}

function loadWatchList() {
  try {
    const raw = localStorage.getItem(WATCH_LIST_KEY)
    if (raw) {
      const arr = JSON.parse(raw)
      watchList.value = Array.isArray(arr) ? arr : []
    } else {
      watchList.value = []
    }
  } catch {
    watchList.value = []
  }
}

function loadHiddenPositions() {
  try {
    const raw = localStorage.getItem(HIDDEN_POSITIONS_KEY)
    return raw ? new Set(JSON.parse(raw)) : new Set()
  } catch {
    return new Set()
  }
}

function saveHiddenPositions(set) {
  localStorage.setItem(HIDDEN_POSITIONS_KEY, JSON.stringify([...set]))
}

function addStock() {
  const code = inputCode.value?.trim()
  if (!code) {
    ElMessage.warning('请输入股票代码')
    return
  }
  const exchange = inputExchange.value || undefined
  const key = `${code}_${exchange || 'auto'}`
  if (watchList.value.some((s) => `${s.code}_${s.exchange || 'auto'}` === key)) {
    ElMessage.info('该股票已在列表中')
    return
  }
  watchList.value.push({ code, exchange })
  localStorage.setItem(WATCH_LIST_KEY, JSON.stringify(watchList.value))
  inputCode.value = ''
  fetchAllQuotes()
  ElMessage.success('已添加')
}

function removeStock(row) {
  if (row.isPosition) {
    const hidden = loadHiddenPositions()
    hidden.add(row.symbol)
    saveHiddenPositions(hidden)
  } else {
    const rowKey = `${row.symbol}_${row.exchange || 'auto'}`
    watchList.value = watchList.value.filter((s) => `${s.code}_${s.exchange || 'auto'}` !== rowKey)
    localStorage.setItem(WATCH_LIST_KEY, JSON.stringify(watchList.value))
    delete quoteMap.value[rowKey]
  }
  removeTrigger.value++
  ElMessage.success('已移除')
}

function mergedStockList() {
  const seen = new Set()
  const list = []
  const hidden = loadHiddenPositions()
  for (const p of positions.value) {
    const code = (p.symbol || '').split('.')[0] || p.symbol
    if (code && !seen.has(code) && !hidden.has(code)) {
      seen.add(code)
      list.push({
        code,
        exchange: p.exchange,
        name: p.name,
        holdingVolume: p.volume,
        isPosition: true,
        currentPrice: p.currentPrice
      })
    }
  }
  for (const w of watchList.value) {
    const code = (w.code || '').split('.')[0] || w.code
    if (code && !seen.has(code)) {
      seen.add(code)
      list.push({
        code,
        exchange: w.exchange,
        name: null,
        holdingVolume: null,
        isPosition: false,
        currentPrice: null
      })
    }
  }
  return list
}

const realtimeTableData = computed(() => {
  void removeTrigger.value // 依赖，用于移除持仓后触发展示刷新
  const list = mergedStockList()
  return list.map((item) => {
    const key = `${item.code}_${item.exchange || 'auto'}`
    const q = quoteMap.value[key] || {}
    return {
      symbol: item.code,
      exchange: item.exchange || 'auto',
      name: (q.stock_name ?? item.name ?? item.code) || item.code,
      currentPrice: q.close_price ?? item.currentPrice,
      changePct: q.change_pct,
      volume: q.volume,
      holdingVolume: item.holdingVolume,
      isPosition: item.isPosition
    }
  })
})

async function fetchAllQuotes() {
  const list = mergedStockList()
  if (list.length === 0) return
  quoteLoading.value = true
  const next = { ...quoteMap.value }
  try {
    for (const { code, exchange } of list) {
      const key = `${code}_${exchange || 'auto'}`
      try {
        const res = await marketApi.getRealtimeQuote(code, exchange)
        const d = res?.data || res
        if (d && Object.keys(d).length) next[key] = d
      } catch {
        // 保留旧数据
      }
    }
    quoteMap.value = next
  } finally {
    quoteLoading.value = false
  }
}

function goKline(row) {
  router.push({ path: '/market/kline', query: { code: row.symbol, exchange: '', name: row.name } })
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
  loadWatchList()
  await fetchAllQuotes()

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
  background: #ffffff;
  border: 1px solid #e2e8f0;
}
.stat-card :deep(.el-card__body) {
  color: #334155;
}
.stat-label {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 22px;
  font-weight: bold;
  color: #1e40af;
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
.realtime-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.realtime-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.realtime-header-actions .el-input,
.realtime-header-actions .el-select {
  flex-shrink: 0;
}
.realtime-tip {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 12px;
}
.realtime-table :deep(.up) { color: #f56c6c; }
.realtime-table :deep(.down) { color: #67c23a; }
</style>
