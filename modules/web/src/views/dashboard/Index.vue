<template>
  <div class="page-wrap dashboard">
    <section class="stat-cards">
      <Card label="总资产" :value="`¥${formatNumber(accountInfo.totalAsset)}`" hint="+2.5%" hint-type="success" />
      <Card label="今日盈亏" :value="`¥${formatNumber(accountInfo.todayPnl)}`" hint="+5.2%" hint-type="success" />
      <Card label="持仓市值" :value="`¥${formatNumber(accountInfo.positionValue)}`" />
      <Card label="可用资金" :value="`¥${formatNumber(accountInfo.cash)}`" />
      <Card label="持仓数量" :value="accountInfo.positionCount ?? positions.length" />
    </section>

    <section class="grid-3">
      <Panel title="持仓收益趋势" class="panel-wide">
        <div ref="chartRef" class="chart" />
      </Panel>
      <Panel title="持仓分布">
        <div ref="pieRef" class="chart" />
      </Panel>
    </section>

    <Panel title="实时行情" subtitle="持仓股票 + 自选股票">
      <template #header>
        <div class="action-row">
          <UiInput v-model="inputCode" placeholder="股票代码" @enter="addStock" />
          <UiSelect v-model="inputExchange" :options="exchangeOptions" />
          <UiButton variant="primary" @click="addStock">添加</UiButton>
          <UiButton variant="ghost" :loading="quoteLoading" @click="fetchAllQuotes">刷新</UiButton>
        </div>
      </template>
      <DataTable :columns="columns" :rows="realtimeTableData" row-key="symbol">
        <template #cell-currentPrice="{ row }">
          <span :class="getPriceClass(row)">{{ formatPrice(row.currentPrice) }}</span>
        </template>
        <template #cell-changePct="{ row }">
          <span :class="getPriceClass(row)">{{ formatChangePct(row.changePct) }}</span>
        </template>
        <template #cell-source="{ row }">
          <StatusChip :status="row.isPosition ? 'success' : 'idle'" :label="row.isPosition ? '持仓' : '自选'" />
        </template>
        <template #cell-action="{ row }">
          <div class="table-action">
            <button class="text-btn" @click="goKline(row)">K线</button>
            <button class="text-btn danger" @click="removeStock(row)">移除</button>
          </div>
        </template>
      </DataTable>
    </Panel>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from "vue"
import { useRouter } from "vue-router"
import * as echarts from "echarts"
import { getAccountInfo } from "@/api/account"
import { getPositionList } from "@/api/position"
import { marketApi } from "@/api/market"
import Panel from "@/components/ui/Panel.vue"
import Card from "@/components/ui/Card.vue"
import UiButton from "@/components/ui/Button.vue"
import UiInput from "@/components/ui/Input.vue"
import UiSelect from "@/components/ui/Select.vue"
import DataTable from "@/components/ui/DataTable.vue"
import StatusChip from "@/components/ui/StatusChip.vue"

const WATCH_LIST_KEY = "realtime_watch_list"
const HIDDEN_POSITIONS_KEY = "dashboard_hidden_positions"

const router = useRouter()
let lineChartInst: echarts.ECharts | null = null
let pieChartInst: echarts.ECharts | null = null

const exchangeOptions = [
  { label: "自动", value: "" },
  { label: "上交所", value: "SSE" },
  { label: "深交所", value: "SZSE" },
  { label: "北交所", value: "BSE" }
]

const columns = [
  { key: "symbol", title: "代码" },
  { key: "name", title: "名称" },
  { key: "currentPrice", title: "最新价" },
  { key: "changePct", title: "涨跌幅" },
  { key: "volume", title: "成交量" },
  { key: "holdingVolume", title: "持仓量" },
  { key: "source", title: "来源" },
  { key: "action", title: "操作" }
] as const

const inputCode = ref("")
const inputExchange = ref("")
const accountInfo = ref({
  totalAsset: 500000,
  cash: 150000,
  positionValue: 350000,
  todayPnl: 12500,
  positionCount: 15
})
const positions = ref<any[]>([])
const watchList = ref<Array<{ code: string; exchange?: string }>>([])
const quoteMap = ref<Record<string, any>>({})
const quoteLoading = ref(false)
const removeTrigger = ref(0)
const chartRef = ref<HTMLElement>()
const pieRef = ref<HTMLElement>()

const formatNumber = (n: number | undefined) => (n == null ? "0" : Number(n).toLocaleString())
const formatPrice = (v: number | undefined) => (v == null ? "--" : Number(v).toFixed(2))
const formatChangePct = (v: number | undefined) => {
  if (v == null) return "--"
  const p = Number(v).toFixed(2)
  return `${p.startsWith("-") ? "" : "+"}${p}%`
}
const formatVolume = (v: number | undefined) => {
  if (v == null) return "--"
  const n = Number(v)
  if (n >= 1e8) return `${(n / 1e8).toFixed(2)}亿`
  if (n >= 1e4) return `${(n / 1e4).toFixed(2)}万`
  return String(n)
}

const getPriceClass = (row: { changePct?: number }) => {
  if (row.changePct == null) return ""
  return row.changePct >= 0 ? "up" : "down"
}

function loadWatchList() {
  try {
    watchList.value = JSON.parse(localStorage.getItem(WATCH_LIST_KEY) || "[]")
  } catch {
    watchList.value = []
  }
}

function loadHiddenPositions() {
  try {
    return new Set<string>(JSON.parse(localStorage.getItem(HIDDEN_POSITIONS_KEY) || "[]"))
  } catch {
    return new Set<string>()
  }
}

function saveHiddenPositions(set: Set<string>) {
  localStorage.setItem(HIDDEN_POSITIONS_KEY, JSON.stringify([...set]))
}

function addStock() {
  const code = inputCode.value?.trim()
  if (!code) return
  const exchange = inputExchange.value || undefined
  const key = `${code}_${exchange || "auto"}`
  if (watchList.value.some((s) => `${s.code}_${s.exchange || "auto"}` === key)) return
  watchList.value.push({ code, exchange })
  localStorage.setItem(WATCH_LIST_KEY, JSON.stringify(watchList.value))
  inputCode.value = ""
  fetchAllQuotes()
}

function removeStock(row: { symbol: string; exchange?: string; isPosition: boolean }) {
  if (row.isPosition) {
    const hidden = loadHiddenPositions()
    hidden.add(row.symbol)
    saveHiddenPositions(hidden)
  } else {
    const rowKey = `${row.symbol}_${row.exchange || "auto"}`
    watchList.value = watchList.value.filter((s) => `${s.code}_${s.exchange || "auto"}` !== rowKey)
    localStorage.setItem(WATCH_LIST_KEY, JSON.stringify(watchList.value))
    delete quoteMap.value[rowKey]
  }
  removeTrigger.value++
}

function mergedStockList() {
  const seen = new Set()
  const list: Array<any> = []
  const hidden = loadHiddenPositions()
  for (const p of positions.value) {
    const code = (p.symbol || "").split(".")[0] || p.symbol
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
    const code = (w.code || "").split(".")[0] || w.code
    if (code && !seen.has(code)) {
      seen.add(code)
      list.push({ code, exchange: w.exchange, name: null, holdingVolume: null, isPosition: false, currentPrice: null })
    }
  }
  return list
}

const realtimeTableData = computed(() => {
  void removeTrigger.value
  return mergedStockList().map((item) => {
    const key = `${item.code}_${item.exchange || "auto"}`
    const q = quoteMap.value[key] || {}
    return {
      symbol: item.code,
      exchange: item.exchange || "auto",
      name: (q.stock_name ?? item.name ?? item.code) || item.code,
      currentPrice: q.close_price ?? item.currentPrice,
      changePct: q.change_pct,
      volume: formatVolume(q.volume),
      holdingVolume: item.holdingVolume ?? "-",
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
      const key = `${code}_${exchange || "auto"}`
      try {
        const res = await marketApi.getRealtimeQuote(code, exchange)
        const d = res?.data || res
        if (d && Object.keys(d).length) next[key] = d
      } catch {
        // ignore
      }
    }
    quoteMap.value = next
  } finally {
    quoteLoading.value = false
  }
}

function goKline(row: { symbol: string; name: string }) {
  router.push({ path: "/market/kline", query: { code: row.symbol, name: row.name } })
}

onMounted(async () => {
  try {
    const acc = await getAccountInfo()
    accountInfo.value = acc.data
  } catch {
    // fallback
  }
  try {
    const pos = await getPositionList()
    positions.value = pos.data || []
  } catch {
    positions.value = []
  }
  loadWatchList()
  await fetchAllQuotes()

  await nextTick()
  if (!chartRef.value || !pieRef.value) return
  lineChartInst = echarts.init(chartRef.value)
  lineChartInst.setOption({
    backgroundColor: "transparent",
    tooltip: { trigger: "axis" },
    xAxis: { type: "category", data: ["1月", "2月", "3月", "4月", "5月", "6月"], axisLabel: { color: "#9db1cc" } },
    yAxis: { type: "value", axisLabel: { color: "#9db1cc" }, splitLine: { lineStyle: { color: "rgba(145,196,255,.16)" } } },
    grid: { left: 50, right: 16, top: 20, bottom: 34 },
    series: [{ data: [450000, 470000, 465000, 490000, 505000, 500000], type: "line", smooth: true, areaStyle: {}, lineStyle: { color: "#58b9ff" } }]
  })

  pieChartInst = echarts.init(pieRef.value)
  pieChartInst.setOption({
    backgroundColor: "transparent",
    tooltip: { trigger: "item" },
    series: [
      {
        type: "pie",
        radius: ["38%", "70%"],
        itemStyle: { borderColor: "#0d1422", borderWidth: 2 },
        data: [
          { value: 60, name: "股票" },
          { value: 25, name: "基金" },
          { value: 15, name: "现金" }
        ]
      }
    ]
  })
})

onBeforeUnmount(() => {
  lineChartInst?.dispose()
  pieChartInst?.dispose()
})
</script>

<style scoped>
.dashboard {
  gap: var(--sp-4);
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--sp-3);
}

.grid-3 {
  align-items: stretch;
}

.panel-wide {
  grid-column: span 2;
}

.chart {
  height: 280px;
}

.action-row {
  display: grid;
  grid-template-columns: minmax(120px, 160px) 120px auto auto;
  gap: 8px;
}

.table-action {
  display: flex;
  gap: 8px;
}

.text-btn {
  border: none;
  background: transparent;
  color: var(--accent-1);
  cursor: pointer;
}

.text-btn.danger {
  color: var(--danger);
}

.up {
  color: var(--danger);
}

.down {
  color: var(--success);
}

@media (max-width: 1199px) {
  .stat-cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .panel-wide {
    grid-column: auto;
  }
}

@media (max-width: 767px) {
  .action-row {
    grid-template-columns: 1fr;
  }
}
</style>
