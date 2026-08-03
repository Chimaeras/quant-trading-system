<template>
  <el-card shadow="hover" class="terminal-page">
    <template #header>
      <div class="header-row">
        <span>模拟账户</span>
        <el-button size="small" @click="loadAll">刷新</el-button>
      </div>
    </template>

    <div v-if="account" class="stats-grid">
      <div class="stat-card"><div class="label">总资产</div><div>{{ fmtMoney(account.totalAsset) }}</div></div>
      <div class="stat-card"><div class="label">可用资金</div><div>{{ fmtMoney(account.cash) }}</div></div>
      <div class="stat-card"><div class="label">持仓市值</div><div>{{ fmtMoney(account.positionValue) }}</div></div>
      <div class="stat-card"><div class="label">浮动盈亏</div><div :class="pnlClass(account.totalProfit)">{{ fmtMoney(account.totalProfit) }}</div></div>
      <div class="stat-card"><div class="label">今日盈亏</div><div :class="pnlClass(account.todayPnl)">{{ fmtMoney(account.todayPnl) }}</div></div>
      <div class="stat-card"><div class="label">持仓数</div><div>{{ account.positionCount }}</div></div>
    </div>

    <div class="section-title">下单</div>
    <el-form inline>
      <el-form-item label="代码"><el-input v-model="order.symbol" style="width: 100px" /></el-form-item>
      <el-form-item label="交易所">
        <el-select v-model="order.exchangeType" style="width: 90px">
          <el-option label="SZSE" value="SZSE" /><el-option label="SSE" value="SSE" />
        </el-select>
      </el-form-item>
      <el-form-item label="方向">
        <el-select v-model="order.action" style="width: 90px">
          <el-option label="买入" value="buy" /><el-option label="卖出" value="sell" />
        </el-select>
      </el-form-item>
      <el-form-item label="数量"><el-input-number v-model="order.volume" :min="100" :step="100" /></el-form-item>
      <el-form-item label="限价(可选)"><el-input-number v-model="order.price" :min="0" :precision="2" /></el-form-item>
      <el-form-item><el-button type="primary" :loading="placing" @click="place">提交</el-button></el-form-item>
    </el-form>

    <div class="section-title">持仓列表</div>
    <el-table v-loading="loading" :data="positions" stripe>
      <el-table-column prop="symbol" label="代码" width="100" />
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column prop="volume" label="持仓" width="90" />
      <el-table-column label="可卖" width="90">
        <template #default="{ row }">
          <span>{{ row.availableVolume ?? row.volume }}</span>
          <el-tooltip v-if="frozenVolume(row) > 0" :content="`T+1 冻结 ${frozenVolume(row)} 股`">
            <span class="frozen-hint">（冻{{ frozenVolume(row) }}）</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="avgPrice" label="成本" />
      <el-table-column prop="currentPrice" label="现价" />
      <el-table-column prop="marketValue" label="市值" />
      <el-table-column label="盈亏">
        <template #default="{ row }">
          <span :class="pnlClass(row.profitLoss)">{{ fmtMoney(row.profitLoss) }} ({{ fmtPct(row.profitRate) }})</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="section-title">订单历史（最近 50 条）</div>
    <el-table v-loading="ordersLoading" :data="orders" stripe size="small" max-height="320">
      <el-table-column prop="tradeTime" label="成交时间" min-width="160" />
      <el-table-column prop="stockCode" label="代码" width="90" />
      <el-table-column prop="stockName" label="名称" min-width="100" />
      <el-table-column label="方向" width="80">
        <template #default="{ row }">
          <span :class="row.action === 'buy' ? 'text-success' : 'text-danger'">{{ row.action === 'buy' ? '买入' : '卖出' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="价格" width="90">
        <template #default="{ row }">{{ Number(row.price).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="volume" label="数量" width="90" />
      <el-table-column label="金额" width="110">
        <template #default="{ row }">{{ fmtMoney(Number(row.amount)) }}</template>
      </el-table-column>
      <el-table-column label="佣金" width="90">
        <template #default="{ row }">{{ Number(row.commission ?? 0).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column prop="orderId" label="订单号" min-width="150" />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { accountApi, type AccountInfo, type PositionRow, type OrderHistoryRow } from '@/api/account'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const loading = ref(false)
const placing = ref(false)
const account = ref<AccountInfo | null>(null)
const positions = ref<PositionRow[]>([])
const orders = ref<OrderHistoryRow[]>([])
const ordersLoading = ref(false)

const order = reactive({
  symbol: '000001',
  exchangeType: 'SZSE',
  action: 'buy' as 'buy' | 'sell',
  volume: 100,
  price: undefined as number | undefined
})

function fmtMoney(v?: number) {
  if (v == null) return '--'
  return Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmtPct(v?: number) {
  if (v == null) return '--'
  return `${(Number(v) * 100).toFixed(2)}%`
}

function pnlClass(v?: number) {
  return v != null && Number(v) >= 0 ? 'text-success' : 'text-danger'
}

function frozenVolume(row: PositionRow) {
  const total = Number(row.volume ?? 0)
  const available = Number(row.availableVolume ?? row.volume ?? 0)
  return Math.max(0, total - available)
}

async function loadAll() {
  loading.value = true
  try {
    account.value = await accountApi.getInfo()
    positions.value = await accountApi.listPositions()
    await loadOrders()
  } catch (e: any) {
    toast.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadOrders() {
  ordersLoading.value = true
  try {
    orders.value = await accountApi.getOrderHistory()
  } catch {
    // silent
  } finally {
    ordersLoading.value = false
  }
}

async function place() {
  placing.value = true
  try {
    const ret = await accountApi.placeOrder({
      symbol: order.symbol,
      exchangeType: order.exchangeType,
      action: order.action,
      volume: order.volume,
      price: order.price || undefined
    })
    toast.success(`下单成功：${ret.orderId}`)
    await loadAll()
  } catch (e: any) {
    toast.error(e?.message || '下单失败')
  } finally {
    placing.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.header-row { display: flex; justify-content: space-between; align-items: center; }
.stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; margin-bottom: 16px; }
.stat-card { padding: 12px; border-radius: 8px; background: rgba(9, 18, 34, 0.55); border: 1px solid rgba(145, 196, 255, 0.15); }
.stat-card .label { font-size: 12px; color: var(--text-secondary); margin-bottom: 6px; }
.section-title { margin: 12px 0 8px; font-weight: 600; }
.frozen-hint { margin-left: 4px; font-size: 12px; color: var(--text-secondary); }
.text-success { color: #ef5350; }
.text-danger { color: #26a69a; }
</style>
