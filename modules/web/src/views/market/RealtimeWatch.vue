<template>
  <div class="realtime-watch terminal-page">
    <el-card shadow="hover">
      <template #header>
        <span>实时盯盘</span>
        <div class="header-actions">
          <el-input
            v-model="inputCode"
            placeholder="股票代码 000001"
            style="width: 120px"
            clearable
            @keyup.enter="addStock"
          />
          <el-select v-model="inputExchange" placeholder="交易所" style="width: 100px" clearable>
            <el-option label="自动" value="" />
            <el-option label="上交所" value="SSE" />
            <el-option label="深交所" value="SZSE" />
            <el-option label="北交所" value="BSE" />
          </el-select>
          <el-button type="primary" size="small" @click="addStock">添加</el-button>
          <el-switch v-model="autoRefresh" active-text="自动刷新" inactive-text="暂停" />
          <span class="muted">每 {{ refreshInterval }} 秒</span>
        </div>
      </template>
      <el-table :data="tableData" stripe max-height="520" size="small" class="watch-table">
        <el-table-column prop="stock_code" label="代码" min-width="75" />
        <el-table-column prop="stock_name" label="名称" min-width="95" show-overflow-tooltip />
        <el-table-column label="最新价" min-width="85">
          <template #default="{ row }">
            <span :class="priceClass(row)">{{ formatPrice(row.close_price) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="涨跌额" min-width="78">
          <template #default="{ row }">
            <span :class="priceClass(row)">
              {{ row.change_amount != null ? (row.change_amount >= 0 ? '+' : '') + row.change_amount.toFixed(2) : '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="涨跌幅" min-width="82">
          <template #default="{ row }">
            <span :class="priceClass(row)">
              {{ row.change_pct != null ? (row.change_pct >= 0 ? '+' : '') + row.change_pct.toFixed(2) + '%' : '--' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="成交量" min-width="90">
          <template #default="{ row }">
            {{ row.volume != null ? formatVolume(row.volume) : '--' }}
          </template>
        </el-table-column>
        <el-table-column label="成交额" min-width="90">
          <template #default="{ row }">
            {{ row.amount != null ? formatAmount(row.amount) : '--' }}
          </template>
        </el-table-column>
        <el-table-column label="今开" min-width="75">
          <template #default="{ row }">{{ formatPrice(row.open_price) }}</template>
        </el-table-column>
        <el-table-column label="最高" min-width="75">
          <template #default="{ row }">{{ formatPrice(row.high_price) }}</template>
        </el-table-column>
        <el-table-column label="最低" min-width="75">
          <template #default="{ row }">{{ formatPrice(row.low_price) }}</template>
        </el-table-column>
        <el-table-column label="昨收" min-width="75">
          <template #default="{ row }">{{ formatPrice(row.pre_close) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="95" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="goKline(row)">K线</el-button>
            <el-button link type="danger" size="small" @click="removeStock(row._idx)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="watchList.length === 0" class="empty-tip">
        暂无自选股，输入股票代码并点击「添加」开始盯盘
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { marketApi } from '@/api/market'
import { useToast } from '@/composables/useToast'

const STORAGE_KEY = 'realtime_watch_list'
const DEFAULT_INTERVAL = 5

const router = useRouter()
const toast = useToast()
const inputCode = ref('')
const inputExchange = ref('')
const autoRefresh = ref(true)
const refreshInterval = ref(DEFAULT_INTERVAL)
const watchList = ref<Array<{ code: string; exchange?: string }>>([])
const quoteMap = ref<Record<string, Record<string, unknown>>>({})

const tableData = computed(() => {
  return watchList.value.map(({ code, exchange }, idx) => {
    const key = `${code}_${exchange || 'auto'}`
    const q = quoteMap.value[key] || {}
    return {
      _idx: idx,
      stock_code: code,
      stock_name: (q.stock_name as string) || code,
      exchange_type: exchange || (q.exchange_type as string),
      close_price: q.close_price,
      open_price: q.open_price,
      high_price: q.high_price,
      low_price: q.low_price,
      pre_close: q.pre_close,
      change_amount: q.change_amount,
      change_pct: q.change_pct,
      volume: q.volume,
      amount: q.amount
    }
  })
})

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const arr = JSON.parse(raw)
      if (Array.isArray(arr)) watchList.value = arr
    }
  } catch {
    watchList.value = []
  }
}

function saveToStorage() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(watchList.value))
}

function addStock() {
  const code = inputCode.value?.trim()
  if (!code) {
    toast.warning('请输入股票代码')
    return
  }
  const exchange = inputExchange.value || undefined
  const key = `${code}_${exchange || 'auto'}`
  if (watchList.value.some((s) => `${s.code}_${s.exchange || 'auto'}` === key)) {
    toast.info('该股票已在列表中')
    return
  }
  watchList.value.push({ code, exchange })
  saveToStorage()
  inputCode.value = ''
  fetchQuotes()
}

function removeStock(index: number) {
  if (index < 0 || index >= watchList.value.length) return
  const item = watchList.value[index]
  const key = `${item.code}_${item.exchange || 'auto'}`
  watchList.value = watchList.value.filter((_, i) => i !== index)
  saveToStorage()
  delete quoteMap.value[key]
}

function goKline(row: { stock_code: string; exchange_type?: string }) {
  router.push({
    path: '/market/kline',
    query: { code: row.stock_code, exchange: row.exchange_type || '', name: row.stock_name }
  })
}

async function fetchQuotes() {
  const next: Record<string, Record<string, unknown>> = { ...quoteMap.value }
  for (const { code, exchange } of watchList.value) {
    const key = `${code}_${exchange || 'auto'}`
    try {
      const res = await marketApi.getRealtimeQuote(code, exchange)
      const d = (res as { data?: Record<string, unknown> })?.data
      if (d && Object.keys(d).length > 0) next[key] = d
    } catch {
      next[key] = next[key] || { stock_code: code, stock_name: code }
    }
  }
  quoteMap.value = next
}

function priceClass(row: { change_pct?: number }) {
  if (row.change_pct == null) return ''
  return row.change_pct >= 0 ? 'up' : 'down'
}

function formatPrice(v: number | undefined | null): string {
  if (v == null) return '--'
  return v.toFixed(2)
}

function formatVolume(v: number): string {
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
  return String(v)
}

function formatAmount(v: number): string {
  if (v >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(2) + '万'
  return String(v)
}

let timer: ReturnType<typeof setInterval> | null = null

watch(autoRefresh, (on) => {
  if (on && watchList.value.length > 0) {
    timer = setInterval(fetchQuotes, refreshInterval.value * 1000)
  } else if (timer) {
    clearInterval(timer)
    timer = null
  }
})

onMounted(() => {
  loadFromStorage()
  fetchQuotes()
  if (autoRefresh.value && watchList.value.length > 0) {
    timer = setInterval(fetchQuotes, refreshInterval.value * 1000)
  }
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.realtime-watch {
  padding: 0;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.header-actions .muted {
  color: var(--text-secondary);
  font-size: 12px;
}
.watch-table .up {
  color: #f56c6c;
}
.watch-table .down {
  color: #67c23a;
}
.empty-tip {
  padding: 40px;
  text-align: center;
  color: var(--text-secondary);
}
</style>
