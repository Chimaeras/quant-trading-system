<template>
  <div class="watchlist-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <div class="header">
          <span>自选股池</span>
          <el-button type="primary" size="small" :loading="loading" @click="loadList">刷新</el-button>
        </div>
      </template>

      <el-form inline class="add-form">
        <el-form-item label="代码">
          <el-input v-model="form.stockCode" placeholder="000001" style="width: 130px" clearable />
        </el-form-item>
        <el-form-item label="交易所">
          <el-select v-model="form.exchangeType" style="width: 100px">
            <el-option label="沪市" value="SSE" />
            <el-option label="深市" value="SZSE" />
            <el-option label="北交所" value="BSE" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" placeholder="可选" style="width: 200px" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="adding" @click="addItem">加入自选</el-button>
        </el-form-item>
      </el-form>

      <p class="hint">
        自选股池记录关注标的；异动扫描基于最新入库行情（非实时），点击下方「异动扫描」查看当日异动信号。
      </p>

      <el-table :data="items" size="small" stripe border v-loading="loading">
        <el-table-column type="index" label="#" width="56" />
        <el-table-column prop="stock_code" label="代码" min-width="90" />
        <el-table-column prop="exchange_type" label="交易所" min-width="80" />
        <el-table-column prop="stock_name" label="名称" min-width="100" />
        <el-table-column prop="note" label="备注" min-width="160" show-overflow-tooltip />
        <el-table-column prop="created_at" label="添加时间" min-width="150">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" :loading="removingKey === row.stock_code + row.exchange_type" @click="removeItem(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !items.length" description="暂无自选股，添加关注标的开始跟踪" />
    </el-card>

    <el-card shadow="hover" class="alert-card">
      <template #header>
        <div class="header">
          <span>异动扫描</span>
          <el-button type="primary" size="small" :loading="scanning" @click="scanAlerts">异动扫描</el-button>
        </div>
      </template>

      <div v-loading="scanning">
        <template v-if="scan">
          <p class="scan-meta">
            信号日：{{ scan.signal_date || '—' }} · 扫描 {{ scan.total_scanned }} 只 · 异动 {{ scan.total_alerted }} 只
            <span class="basis">（{{ scan.basis }}）</span>
          </p>
          <el-table v-if="scan.alerts.length" :data="scan.alerts" size="small" stripe border>
            <el-table-column type="index" label="#" width="56" />
            <el-table-column prop="stock_code" label="代码" min-width="90" />
            <el-table-column prop="stock_name" label="名称" min-width="100" />
            <el-table-column prop="signal_date" label="信号日" min-width="110" />
            <el-table-column label="最新收盘" min-width="90">
              <template #default="{ row }">{{ row.latest_close != null ? row.latest_close.toFixed(2) : '—' }}</template>
            </el-table-column>
            <el-table-column label="涨跌幅" min-width="90">
              <template #default="{ row }">
                <span :class="pctClass(row.pct_change)">{{ pctText(row.pct_change) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="量比" min-width="80">
              <template #default="{ row }">{{ row.volume_ratio != null ? row.volume_ratio.toFixed(2) : '—' }}</template>
            </el-table-column>
            <el-table-column label="异动信号" min-width="240">
              <template #default="{ row }">
                <el-tag v-for="s in row.signals" :key="s" size="small" class="signal-tag">{{ s }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else-if="!scanning" :description="scan.note" />
        </template>
        <el-empty v-else-if="!scanning" description="点击异动扫描查看当日异动信号" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { listWatchlist, addWatchlist, removeWatchlist, scanWatchlistAlerts, type WatchlistItem, type WatchlistAlertScan } from '@/api/watchlist'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const loading = ref(false)
const adding = ref(false)
const removingKey = ref('')
const items = ref<WatchlistItem[]>([])
const scanning = ref(false)
const scan = ref<WatchlistAlertScan | null>(null)

const form = reactive({
  stockCode: '',
  exchangeType: 'SZSE',
  note: ''
})

function formatTime(value: string): string {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 16)
}

async function loadList() {
  loading.value = true
  try {
    items.value = await listWatchlist()
  } catch (e: any) {
    toast.error(e?.message || '加载自选股失败')
  } finally {
    loading.value = false
  }
}

async function addItem() {
  if (!form.stockCode.trim()) {
    toast.warning('请输入股票代码')
    return
  }
  adding.value = true
  try {
    await addWatchlist(form.stockCode.trim(), form.exchangeType, form.note.trim() || undefined)
    toast.success('已加入自选')
    form.stockCode = ''
    form.note = ''
    await loadList()
  } catch (e: any) {
    toast.error(e?.message || '加入自选失败')
  } finally {
    adding.value = false
  }
}

async function removeItem(row: WatchlistItem) {
  removingKey.value = row.stock_code + row.exchange_type
  try {
    await removeWatchlist(row.stock_code, row.exchange_type)
    toast.success('已移除')
    await loadList()
  } catch (e: any) {
    toast.error(e?.message || '移除失败')
  } finally {
    removingKey.value = ''
  }
}

function pctText(v: number): string {
  return Number.isFinite(v) ? (v * 100).toFixed(2) + '%' : '—'
}
function pctClass(v: number): string {
  if (!Number.isFinite(v)) return ''
  return v > 0 ? 'text-danger' : v < 0 ? 'text-success' : ''
}

async function scanAlerts() {
  scanning.value = true
  try {
    scan.value = await scanWatchlistAlerts()
  } catch (e: any) {
    toast.error(e?.message || '异动扫描失败')
  } finally {
    scanning.value = false
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.watchlist-page {
  max-width: 1200px;
  margin: 0 auto;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.add-form {
  margin-bottom: 8px;
}
.hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--text-secondary);
}
.alert-card {
  margin-top: 16px;
}
.scan-meta {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-secondary);
}
.basis {
  color: var(--text-muted);
  font-size: 12px;
}
.signal-tag {
  margin: 0 4px 4px 0;
}
.text-danger { color: #f56c6c; }
.text-success { color: #67c23a; }
</style>
