<template>
  <PageShell>
    <PageHeader title="账户 · 交易终端" subtitle="盯盘账户与历史演练 / 手工模拟账户相互隔离、互不参与合计；T+1 依据交易日历（V2-MP-021）" />

    <WatchAccountPanel />

    <SectionCard title="历史演练 / 手工模拟账户">
      <template #extra>
        <span v-if="activeSession" class="header-meta">
          <el-tag type="warning" effect="plain" size="small">演练会话 #{{ activeSession.sessionId }}</el-tag>
          <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
        </span>
        <span v-else class="header-meta">
          <el-button size="small" :loading="loading" @click="loadAll">刷新</el-button>
        </span>
      </template>

      <div v-if="activeSession" class="session-banner">
        <span>历史演练：{{ activeSession.name }} · 起始 {{ activeSession.startDate }} · 状态 {{ activeSession.status }}
          · 现金 {{ fmtMoney(activeSession.cash) }}</span>
        <span>
          <el-button size="small" @click="clearSession">返回在线账户</el-button>
          <el-button size="small" type="danger" plain :loading="closingSession" @click="closeActiveSession">关闭会话</el-button>
        </span>
      </div>

      <div v-loading="loading" class="account-status-area">
        <StatGrid v-if="account">
          <StatCard label="总资产" :value="fmtMoney(account.totalAsset)" />
          <StatCard label="可用资金" :value="fmtMoney(account.cash)" />
          <StatCard label="持仓市值" :value="fmtMoney(account.positionValue)" />
          <StatCard label="浮动盈亏">
            <template #value><span :class="pnlClass(account.totalProfit)">{{ fmtMoney(account.totalProfit) }}</span></template>
          </StatCard>
          <StatCard label="今日盈亏">
            <template #value><span :class="pnlClass(account.todayPnl)">{{ fmtMoney(account.todayPnl) }}</span></template>
          </StatCard>
          <StatCard label="持仓数" :value="account.positionCount" />
        </StatGrid>
        <EmptyState v-else-if="!loading" message="暂无账户数据，请检查后端服务后刷新" />
      </div>

      <div class="section-title">历史演练会话（V2-MP-021：T+1 依据交易日历，与在线账户隔离）</div>
      <el-form inline class="session-form">
        <el-form-item label="名称"><el-input v-model="sessionForm.name" style="width: 120px" placeholder="演练" /></el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="sessionForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" />
        </el-form-item>
        <el-form-item label="期初资金">
          <el-input-number v-model="sessionForm.cash" :min="0" :precision="2" :controls="false" style="width: 130px" placeholder="默认取在线现金" />
        </el-form-item>
        <el-form-item>
          <el-button :loading="creatingSession" @click="createSession">创建会话</el-button>
        </el-form-item>
      </el-form>
      <div v-if="sessions.length" class="session-list">
        <el-tag
          v-for="s in sessions"
          :key="s.sessionId"
          :type="sessionTagType(s)"
          effect="plain"
          class="session-chip"
          :class="{ 'chip-active': activeSession && activeSession.sessionId === s.sessionId }"
          role="button"
          tabindex="0"
          @click="activateSession(s)"
          @keydown.enter.prevent="activateSession(s)"
          @keydown.space.prevent="activateSession(s)"
        >
          #{{ s.sessionId }} {{ s.name }} · {{ s.startDate }} · 现金 {{ fmtMoney(s.cash) }} · {{ s.status }}
        </el-tag>
      </div>
      <EmptyState v-else-if="!creatingSession" icon="🗂" message="暂无历史演练会话，可通过上方表单创建" />

      <div class="section-title">下单</div>
      <el-alert
        v-if="activeSession"
        type="warning"
        :closable="false"
        show-icon
        class="session-hint"
        title="演练会话：tradeDate 为必填仿真交易日；同会话内按交易日顺序演练，T+1 依据交易日历（含节假日）。"
      />
      <el-form inline>
        <el-form-item label="代码">
          <el-input
            v-model="order.symbol"
            style="width: 100px"
            @input="orderStockName = ''"
            @blur="loadOrderStockName"
          />
        </el-form-item>
        <el-form-item label="名称">
          <span class="security-name">{{ orderStockName || '--' }}</span>
        </el-form-item>
        <el-form-item label="交易所">
          <el-select v-model="order.exchangeType" style="width: 90px" @change="loadOrderStockName">
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
        <el-form-item label="交易日">
          <el-date-picker v-model="order.tradeDate" type="date" value-format="YYYY-MM-DD" style="width: 150px" placeholder="省略价时必填" />
        </el-form-item>
        <el-form-item><el-button type="primary" :loading="placing" @click="place">提交</el-button></el-form-item>
      </el-form>
      <el-alert
        v-if="lastReject"
        type="error"
        :closable="true"
        show-icon
        class="reject-alert"
        :title="`订单拒绝：${lastReject}`"
        @close="lastReject = ''"
      />

      <div v-if="lastOrder && lastOrder.status === 'FILLED'" class="order-result">
        <div class="order-result-head">
          <span class="text-success">成交：{{ lastOrder.orderId }} · {{ lastOrder.action === 'buy' ? '买入' : '卖出' }}</span>
          <el-button size="small" text @click="lastOrder = null">收起</el-button>
        </div>
        <el-table :data="orderResultRows" size="small" border class="order-result-table">
          <el-table-column label="成交价" prop="price" width="110" />
          <el-table-column label="数量" prop="volume" width="90" />
          <el-table-column label="来源" prop="price_source" min-width="120" />
          <el-table-column label="仿真交易日" prop="operation_date" width="120" />
          <el-table-column label="佣金" prop="commission" width="100" />
          <el-table-column label="印花税" prop="stamp_duty" width="100" />
          <el-table-column label="最低佣金(起)" prop="min_commission" width="110" />
          <el-table-column label="滑点" prop="slippage" width="90" />
        </el-table>
        <div v-if="lastOrder.lot_breakdown && lastOrder.lot_breakdown.length" class="lot-detail">
          <div class="section-title">批次明细（T+1：仅建仓日早于目标交易日的批次可卖）</div>
          <el-table :data="lastOrder.lot_breakdown" size="small" border>
            <el-table-column prop="orderId" label="批次单号" min-width="150" />
            <el-table-column prop="acquisition_trade_date" label="建仓日" width="120" />
            <el-table-column prop="volume" label="本单量" width="90" />
            <el-table-column label="剩余" width="90">
              <template #default="{ row }">{{ row.remaining_volume }}</template>
            </el-table-column>
            <el-table-column label="成本价" width="100">
              <template #default="{ row }">{{ fmt4(row.cost_price) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>

      <div class="section-title">持仓列表</div>
      <el-table v-loading="loading" :data="positions" stripe>
        <el-table-column prop="symbol" label="代码" width="100" />
        <el-table-column label="名称" min-width="120">
          <template #default="{ row }">{{ row.name || '--' }}</template>
        </el-table-column>
        <el-table-column prop="volume" label="持仓" width="90" />
        <el-table-column label="可卖" width="100">
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
        <el-table-column label="首买" width="110">
          <template #default="{ row }">{{ row.firstBuyDate || '--' }}</template>
        </el-table-column>
        <el-table-column label="批次" width="70">
          <template #default="{ row }">{{ row.lotCount ?? 0 }}</template>
        </el-table-column>
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
        <el-table-column label="名称" min-width="100">
          <template #default="{ row }">{{ row.stockName || '--' }}</template>
        </el-table-column>
        <el-table-column label="方向" width="80">
          <template #default="{ row }">
            <span :class="row.action === 'buy' ? 'text-up' : 'text-down'">{{ row.action === 'buy' ? '买入' : '卖出' }}</span>
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
        <el-table-column prop="priceSource" label="来源" min-width="120">
          <template #default="{ row }">{{ row.priceSource || '--' }}</template>
        </el-table-column>
        <el-table-column prop="orderId" label="订单号" min-width="150" />
      </el-table>
    </SectionCard>
  </PageShell>
</template>

<script setup lang="ts">
import WatchAccountPanel from '@/components/WatchAccountPanel.vue'
import PageShell from '@/components/ui/PageShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import StatGrid from '@/components/ui/StatGrid.vue'
import StatCard from '@/components/ui/StatCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import {
  accountApi,
  type AccountInfo,
  type PositionRow,
  type OrderHistoryRow,
  type SimulationSession,
  type PlaceOrderResult
} from '@/api/account'
import { errorText } from '@/api/request'
import { useToast } from '@/composables/useToast'
import dayjs from 'dayjs'
import { resolveSecurityName } from '@/utils/security'

const toast = useToast()
const loading = ref(false)
const placing = ref(false)
const account = ref<AccountInfo | null>(null)
const positions = ref<PositionRow[]>([])
const orders = ref<OrderHistoryRow[]>([])
const ordersLoading = ref(false)
const lastReject = ref('')
const lastOrder = ref<PlaceOrderResult | null>(null)

const sessions = ref<SimulationSession[]>([])
const activeSession = ref<SimulationSession | null>(null)
const creatingSession = ref(false)
const closingSession = ref(false)
const sessionForm = reactive({
  name: '',
  startDate: dayjs().format('YYYY-MM-DD'),
  cash: undefined as number | undefined
})

const order = reactive({
  symbol: '000001',
  exchangeType: 'SZSE',
  action: 'buy' as 'buy' | 'sell',
  volume: 100,
  price: undefined as number | undefined,
  tradeDate: dayjs().format('YYYY-MM-DD')
})
const orderStockName = ref('')

const orderResultRows = computed(() => {
  const o = lastOrder.value
  if (!o) return []
  return [
    {
      price: fmt4(o.price),
      volume: String(o.volume ?? ''),
      price_source: o.price_source || '--',
      operation_date: o.operation_date || '--',
      commission: fmt4(o.commission),
      stamp_duty: fmt4(o.stamp_duty),
      min_commission: fmt4(feeValue('min_commission')),
      slippage: fmt4(feeValue('slippage_rate'))
    }
  ]
})

function fmtMoney(v?: number) {
  if (v == null) return '--'
  return Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function fmt4(v?: unknown) {
  if (v == null) return '--'
  const n = Number(v)
  return Number.isNaN(n) ? '--' : n.toFixed(4)
}

function fmtPct(v?: number) {
  if (v == null || !Number.isFinite(Number(v))) return '--'
  return `${(Number(v) * 100).toFixed(2)}%`
}

function pnlClass(v?: number) {
  // 区分 null（无盈亏数据，不着色）与 0（持平）
  if (v == null || !Number.isFinite(Number(v))) return ''
  return Number(v) >= 0 ? 'text-up' : 'text-down'
}

function feeValue(key: string): unknown {
  return lastOrder.value?.fee_breakdown?.[key]
}

function frozenVolume(row: PositionRow) {
  const total = Number(row.volume ?? 0)
  const available = Number(row.availableVolume ?? row.volume ?? 0)
  return Math.max(0, total - available)
}

async function loadOrderStockName() {
  orderStockName.value = await resolveSecurityName(order.symbol, order.exchangeType)
}

function sessionTagType(s: SimulationSession): 'success' | 'info' {
  if (activeSession.value && activeSession.value.sessionId === s.sessionId) return 'success'
  return 'info'
}

async function loadAll() {
  loading.value = true
  try {
    const sid = activeSession.value?.sessionId
    account.value = await accountApi.getInfo(undefined, sid)
    positions.value = await accountApi.listPositions(undefined, sid)
    await loadOrders()
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function loadOrders() {
  ordersLoading.value = true
  try {
    const sid = activeSession.value?.sessionId
    orders.value = await accountApi.getOrderHistory(undefined, sid)
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    ordersLoading.value = false
  }
}

async function loadSessions() {
  try {
    sessions.value = await accountApi.listSimSessions()
  } catch (e: unknown) {
    toast.error(errorText(e))
  }
}

async function createSession() {
  if (!sessionForm.startDate) {
    toast.error('请选择会话起始交易日')
    return
  }
  creatingSession.value = true
  try {
    const s = await accountApi.createSimSession({
      name: sessionForm.name || '历史演练',
      startDate: sessionForm.startDate,
      cash: sessionForm.cash
    })
    toast.success(`已创建演练会话 #${s.sessionId}（${s.startDate} 起）`)
    sessionForm.name = ''
    sessionForm.cash = undefined
    await loadSessions()
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    creatingSession.value = false
  }
}

async function activateSession(s: SimulationSession) {
  activeSession.value = s
  await loadAll()
}

function clearSession() {
  activeSession.value = null
  loadAll()
}

async function closeActiveSession() {
  const s = activeSession.value
  if (!s) return
  closingSession.value = true
  try {
    await accountApi.closeSimSession(s.sessionId)
    toast.success('会话已关闭')
    if (activeSession.value && activeSession.value.sessionId === s.sessionId) {
      activeSession.value = null
    }
    await loadSessions()
    await loadAll()
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    closingSession.value = false
  }
}

async function place() {
  placing.value = true
  lastReject.value = ''
  lastOrder.value = null
  try {
    if (!order.symbol?.trim()) {
      toast.error('请输入股票代码')
      return
    }
    if (!order.volume || order.volume <= 0) {
      toast.error('请输入有效的委托数量（大于 0）')
      return
    }
    const body: {
      symbol: string
      exchangeType?: string
      action: 'buy' | 'sell'
      volume: number
      price?: number
      tradeDate?: string
      sessionId?: number
    } = {
      symbol: order.symbol,
      exchangeType: order.exchangeType,
      action: order.action,
      volume: order.volume,
      price: order.price || undefined
    }
    const sid = activeSession.value?.sessionId
    if (sid != null) {
      body.sessionId = sid
      if (!order.tradeDate) {
        toast.error('演练会话必须指定 tradeDate（仿真交易日）')
        return
      }
      body.tradeDate = order.tradeDate
    } else {
      body.tradeDate = order.price ? undefined : (order.tradeDate || undefined)
    }
    const ret = await accountApi.placeOrder(body)
    if (String(ret.status || '').toUpperCase() === 'REJECTED') {
      const reason = String(ret.reject_reason || ret.reject_code || '订单被拒绝')
      lastReject.value = reason
      toast.error(reason)
      return
    }
    lastOrder.value = ret
    const src = ret.price_source ? ` · ${ret.price_source}` : ''
    toast.success(`下单成功：${ret.orderId}${src}`)
    await loadAll()
    if (sid != null) await loadSessions()
  } catch (e: unknown) {
    const msg = errorText(e)
    lastReject.value = msg
    toast.error(msg)
  } finally {
    placing.value = false
  }
}

onMounted(() => {
  loadOrderStockName()
  loadAll()
  loadSessions()
})
</script>

<style scoped>
.header-meta { display: flex; align-items: center; gap: 8px; }
.security-name { color: var(--text-secondary); min-width: 72px; display: inline-block; }
.session-banner {
  display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap;
  background: var(--accent-soft); border: 1px solid var(--glass-border-strong);
  border-radius: var(--r-sm); padding: 6px 10px; margin-bottom: 10px; font-size: var(--fz-sm);
}
.session-form { margin-bottom: 4px; }
.session-list { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 4px; }
.session-chip { cursor: pointer; outline: none; }
.session-chip:focus-visible { border-color: var(--brand); }
.session-chip.chip-active { border-color: var(--success); }
.session-hint { margin: 6px 0 10px; }
.account-status-area { margin-bottom: 4px; }
.frozen-hint { margin-left: 4px; font-size: 12px; color: var(--text-secondary); }
.reject-alert { margin: 8px 0 12px; }
.order-result { margin: 8px 0 12px; }
.order-result-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.order-result-table { margin-bottom: 8px; }
.lot-detail { margin-top: 8px; }
</style>
