<template>
  <el-card shadow="never" class="watch-account-panel">
    <template #header>
      <div class="panel-head">
        <div>
          <strong>盯盘账户</strong>
          <span class="muted">独立于历史演练/手工模拟，不参与合计</span>
        </div>
        <div class="scope-bar">
          <el-select v-model="selectedScope" size="small" placeholder="选择盯盘账户" @change="refresh">
            <el-option
              v-for="item in scopes" :key="item.account_scope"
              :label="`${modeLabel(item.execution_mode)} · ${item.session_id}`"
              :value="item.account_scope"
            />
          </el-select>
          <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
        </div>
      </div>
    </template>

    <el-empty v-if="!account?.available" description="暂无盯盘账户投影；请先启动 watch/只读服务" />
    <template v-else>
      <div class="source-line">
        <el-tag size="small" effect="plain">{{ modeLabel(account.execution_mode) }}</el-tag>
        <el-tag size="small" effect="plain" :type="sourceType(account.account_source)">{{ sourceLabel(account.account_source) }}</el-tag>
        <el-tag size="small" effect="dark" :type="statusType(account.source_status)">{{ statusLabel(account.source_status) }}</el-tag>
        <span>会话 {{ account.session_id }}</span>
        <span>同步 {{ fmtTime(account.captured_at) }}</span>
        <span>新鲜度 {{ account.freshness_seconds == null ? '—' : `${Math.round(account.freshness_seconds)} 秒` }}</span>
      </div>
      <el-alert
        v-if="account.errors?.length" type="warning" :closable="false" show-icon
        :title="account.errors.join('；')" class="account-warning"
      />

      <div class="metrics">
        <div v-for="item in metrics" :key="item.label" class="metric">
          <span>{{ item.label }}</span>
          <strong :class="signedClass(item.signed ? item.value : null)">{{ item.format(item.value) }}</strong>
        </div>
      </div>

      <el-tabs v-model="tab">
        <el-tab-pane label="持仓" name="positions">
          <div class="table-host">
            <el-table :data="account.positions || []" size="small" border height="100%">
              <el-table-column prop="code" label="代码" width="82" />
              <el-table-column prop="name" label="名称" min-width="100" />
              <el-table-column prop="qty" label="持仓" width="72" align="right" />
              <el-table-column prop="available_qty" label="可卖" width="72" align="right" />
              <el-table-column label="冻结" width="72" align="right"><template #default="{ row }">{{ fmt(row.frozen_qty, 0) }}</template></el-table-column>
              <el-table-column label="成本" width="88" align="right"><template #default="{ row }">{{ fmt(row.avg_cost) }}</template></el-table-column>
              <el-table-column label="现价" width="88" align="right"><template #default="{ row }">{{ fmt(row.last_price) }}</template></el-table-column>
              <el-table-column label="市值" width="110" align="right"><template #default="{ row }">{{ money(row.market_value) }}</template></el-table-column>
              <el-table-column label="盈亏" width="110" align="right"><template #default="{ row }"><span :class="signedClass(row.profit)">{{ signedMoney(row.profit) }}</span></template></el-table-column>
              <el-table-column label="盈亏率" width="90" align="right"><template #default="{ row }">{{ percent(row.profit_rate) }}</template></el-table-column>
              <el-table-column label="今日盈亏" width="105" align="right"><template #default="{ row }">{{ signedMoney(row.day_profit) }}</template></el-table-column>
              <el-table-column label="仓位" width="82" align="right"><template #default="{ row }">{{ percent(row.position_pct) }}</template></el-table-column>
              <el-table-column prop="price_source" label="价格来源" min-width="145" />
              <el-table-column label="更新时间" width="155"><template #default="{ row }">{{ fmtTime(row.source_updated_at) }}</template></el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="委托" name="orders">
          <div class="filter-row">
            <el-select v-model="orderFilter" size="small" clearable placeholder="全部状态">
              <el-option v-for="s in orderStatuses" :key="s" :label="s" :value="s" />
            </el-select>
          </div>
          <div class="table-host">
            <el-table :data="filteredOrders" size="small" border height="100%">
              <el-table-column prop="client_order_id" label="本地订单号" min-width="185"><template #default="{ row }">{{ row.client_order_id || '—' }}</template></el-table-column>
              <el-table-column prop="broker_order_id" label="妙想订单号" min-width="145"><template #default="{ row }">{{ row.broker_order_id || '—' }}</template></el-table-column>
              <el-table-column prop="code" label="代码" width="82" />
              <el-table-column label="方向" width="60"><template #default="{ row }">{{ row.side === 'buy' ? '买' : '卖' }}</template></el-table-column>
              <el-table-column label="委托价" width="88" align="right"><template #default="{ row }">{{ fmt(row.limit_price) }}</template></el-table-column>
              <el-table-column prop="qty" label="委托量" width="78" align="right" />
              <el-table-column prop="filled_qty" label="已成" width="68" align="right" />
              <el-table-column prop="remaining_qty" label="剩余" width="68" align="right" />
              <el-table-column label="状态" width="118"><template #default="{ row }"><el-tag size="small" :type="orderType(row.normalized_status)">{{ row.normalized_status }}</el-tag></template></el-table-column>
              <el-table-column prop="origin" label="来源" width="105" />
              <el-table-column prop="broker_status" label="柜台原态" width="82" />
              <el-table-column label="时间" min-width="155"><template #default="{ row }">{{ fmtTime(row.broker_time || row.created_at) }}</template></el-table-column>
              <el-table-column label="错误" min-width="150"><template #default="{ row }">{{ row.last_error_message || '—' }}</template></el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="成交" name="fills">
          <div class="table-host">
            <el-table :data="fills" size="small" border height="100%">
              <el-table-column prop="filled_at" label="成交时间" min-width="155" />
              <el-table-column prop="code" label="代码" width="82" />
              <el-table-column prop="name" label="名称" min-width="100" />
              <el-table-column prop="qty" label="数量" width="72" align="right" />
              <el-table-column label="成交价" width="90" align="right"><template #default="{ row }">{{ fmt(row.price) }}</template></el-table-column>
              <el-table-column label="费用" width="110" align="right"><template #default="{ row }">{{ row.fee_unknown ? '费用未提供' : money(row.total_fee) }}</template></el-table-column>
              <el-table-column prop="price_source" label="证据来源" min-width="190" />
              <el-table-column label="估算" width="70"><template #default="{ row }">{{ row.estimated ? '是' : '否' }}</template></el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <el-tab-pane label="收益" name="pnl">
          <el-alert v-if="pnl?.errors?.length" type="info" :closable="false" :title="pnl.errors.join('；')" />
          <div class="table-host">
            <el-table :data="pnl?.per_code || []" size="small" border height="100%">
              <el-table-column prop="code" label="代码" width="82" />
              <el-table-column prop="name" label="名称" min-width="100" />
              <el-table-column prop="qty" label="持仓" width="72" align="right" />
              <el-table-column label="已实现" width="110" align="right"><template #default="{ row }">{{ signedMoney(row.realized) }}</template></el-table-column>
              <el-table-column label="未实现" width="110" align="right"><template #default="{ row }">{{ signedMoney(row.unrealized) }}</template></el-table-column>
              <el-table-column label="合计" width="110" align="right"><template #default="{ row }">{{ signedMoney(row.total) }}</template></el-table-column>
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  fetchAccount, fetchAccountScopes, fetchFills, fetchOrders, fetchPnl,
  type WatchAccount, type WatchAccountScope, type WatchFillRow,
  type WatchOrderRow, type WatchPnl
} from '@/api/watch'

const props = withDefaults(defineProps<{ tradeDate?: string; pollMs?: number }>(), { pollMs: 5000 })
const scopes = ref<WatchAccountScope[]>([])
const selectedScope = ref('')
const account = shallowRef<WatchAccount | null>(null)
const orders = shallowRef<WatchOrderRow[]>([])
const fills = shallowRef<WatchFillRow[]>([])
const pnl = shallowRef<(WatchPnl & { errors?: string[] }) | null>(null)
const loading = ref(false)
const tab = ref('positions')
const orderFilter = ref('')
const orderStatuses = ['SUBMITTED', 'PARTIAL', 'FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'SUBMITTING', 'CREATED', 'CANCEL_PENDING', 'UNKNOWN']
let timer: ReturnType<typeof setInterval> | undefined

const scope = computed(() => scopes.value.find((item) => item.account_scope === selectedScope.value))
const filteredOrders = computed(() => orderFilter.value
  ? orders.value.filter((row) => row.normalized_status === orderFilter.value)
  : orders.value)
const metrics = computed(() => {
  const a = account.value!
  return [
    { label: '总资产', value: a.total_assets, format: money },
    { label: '可用资金', value: a.avail_balance, format: money },
    { label: '冻结资金', value: a.frozen_cash, format: money },
    { label: '持仓市值', value: a.market_value, format: money },
    { label: '总盈亏', value: a.total_profit, format: signedMoney, signed: true },
    { label: '总收益率', value: a.total_return_pct, format: percent, signed: true },
    { label: '当日盈亏', value: a.day_profit, format: signedMoney, signed: true },
    { label: '当日收益率', value: a.day_return_pct, format: percent, signed: true },
    { label: '已实现盈亏', value: a.realized_pnl, format: signedMoney, signed: true },
    { label: '未实现盈亏', value: a.unrealized_pnl, format: signedMoney, signed: true },
    { label: '持仓数', value: a.position_count, format: (v: number | null) => fmt(v, 0) },
    { label: '在途委托', value: a.open_order_count, format: (v: number | null) => fmt(v, 0) },
  ]
})

async function refreshScopes() {
  const result = await fetchAccountScopes()
  scopes.value = result.accounts || []
  if (!selectedScope.value || !scopes.value.some((item) => item.account_scope === selectedScope.value)) {
    selectedScope.value = scopes.value[0]?.account_scope || ''
  }
}

async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    if (!scopes.value.length) await refreshScopes()
    const current = scope.value
    if (!current) return
    const filters = {
      session_id: current.session_id,
      execution_mode: current.execution_mode,
      limit: 500,
      trade_date: props.tradeDate || undefined,
    }
    const [a, o, f, p] = await Promise.all([
      fetchAccount(current.session_id, current.execution_mode),
      fetchOrders(filters), fetchFills(filters),
      fetchPnl(current.session_id, current.execution_mode),
    ])
    if (a.available) account.value = a
    orders.value = o.orders || []
    fills.value = f.fills || []
    pnl.value = p.available ? p : null
  } catch {
    // 只读服务短暂不可用时保留上一成功投影，避免账户闪成空值。
  } finally {
    loading.value = false
  }
}

function finite(value: unknown): number | null {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}
function fmt(value: unknown, digits = 2) { const n = finite(value); return n == null ? '—' : n.toFixed(digits) }
function money(value: unknown) { const n = finite(value); return n == null ? '—' : `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` }
function signedMoney(value: unknown) { const n = finite(value); return n == null ? '—' : `${n > 0 ? '+' : ''}${money(n)}` }
function percent(value: unknown) { const n = finite(value); return n == null ? '—' : `${n > 0 ? '+' : ''}${n.toFixed(2)}%` }
function fmtTime(value: unknown) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function signedClass(value: unknown) { const n = finite(value); return n == null ? '' : n > 0 ? 'positive' : n < 0 ? 'negative' : '' }
function modeLabel(v: string) { return ({ INTERNAL_PAPER: '盯盘本地模拟', EXTERNAL_PAPER: '妙想外部模拟', SHADOW: '影子分析' } as Record<string, string>)[v] || v }
function sourceLabel(v: string) { return ({ LOCAL_SIMULATION: '本地模拟账本', MX_MIRROR: '妙想模拟盘镜像', SHADOW: '非交易账户' } as Record<string, string>)[v] || v }
function statusLabel(v: string) { return ({ LIVE: '新鲜', STALE: '已过期', ERROR: '同步异常', NOT_AVAILABLE: '不可用' } as Record<string, string>)[v] || v }
function statusType(v: string) { return v === 'LIVE' ? 'success' : v === 'STALE' ? 'warning' : 'danger' }
function sourceType(v: string) { return v === 'MX_MIRROR' ? 'primary' : v === 'SHADOW' ? 'info' : 'success' }
function orderType(v: string) { return v === 'FILLED' ? 'success' : v === 'UNKNOWN' || v === 'REJECTED' ? 'danger' : v === 'CANCELLED' || v === 'EXPIRED' ? 'info' : 'warning' }

onMounted(async () => {
  try { await refreshScopes(); await refresh() } catch { /* 保留上一投影 */ }
  timer = setInterval(() => { void refresh() }, props.pollMs)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.watch-account-panel {
  margin-bottom: 0;
  height: 100%;
  max-height: 100%;
  display: flex !important;
  flex-direction: column;
  overflow: hidden;
}
.watch-account-panel :deep(.el-card__header) { flex: 0 0 auto; }
.watch-account-panel :deep(.el-card__body) {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.watch-account-panel :deep(.el-tabs) {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.watch-account-panel :deep(.el-tabs__header) { flex: 0 0 auto; }
.watch-account-panel :deep(.el-tabs__content) {
  flex: 1 1 0;
  min-height: 0;
  overflow: hidden;
}
.watch-account-panel :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-head, .scope-bar, .source-line, .filter-row { display: flex; align-items: center; gap: 10px; }
.panel-head { justify-content: space-between; flex-wrap: wrap; }
.muted { color: var(--text-secondary); font-size: 12px; margin-left: 10px; }
.source-line { flex-wrap: wrap; color: var(--text-secondary); font-size: 12px; margin-bottom: 12px; }
.account-warning, .filter-row { margin-bottom: 12px; flex: 0 0 auto; }
.metrics {
  display: grid;
  grid-template-columns: repeat(6, minmax(110px, 1fr));
  gap: 10px;
  margin-bottom: 14px;
  flex: 0 0 auto;
}
.metric {
  padding: 10px;
  border: 1px solid var(--hairline-strong);
  border-radius: 6px;
  background: var(--bg-2);
  display: flex;
  flex-direction: column;
  gap: 5px;
  color: var(--text-primary);
}
.metric span { color: var(--text-secondary); font-size: 12px; }
.metric strong { font-size: 15px; color: var(--text-primary); }
.positive { color: var(--up); }
.negative { color: var(--down); }
.table-host {
  flex: 1 1 0;
  min-height: 0;
  position: relative;
  overflow: hidden;
}
.table-host :deep(> .el-table) {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100% !important;
  min-width: 1050px;
  --el-table-bg-color: #111827;
  --el-table-tr-bg-color: #111827;
  --el-table-header-bg-color: #1e293b;
  --el-table-text-color: #e5edf7;
  --el-table-header-text-color: #cbd5e1;
  background: #111827 !important;
  color: #e5edf7 !important;
}
.table-host :deep(.el-table__body-wrapper) {
  overflow-x: auto !important;
  overflow-y: auto !important;
}
.table-host :deep(th.el-table__cell) {
  background: #1e293b !important;
  color: #cbd5e1 !important;
}
.table-host :deep(td.el-table__cell) {
  background: #111827 !important;
  color: #e5edf7 !important;
}
@media (max-width: 1100px) { .metrics { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 640px) { .metrics { grid-template-columns: repeat(2, 1fr); } }
</style>
