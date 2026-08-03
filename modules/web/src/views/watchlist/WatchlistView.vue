<template>
  <div class="watchlist-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <div class="header">
          <span>自选股池</span>
          <div class="header-actions">
            <el-button size="small" :disabled="!items.length" @click="exportCsv">导出 CSV</el-button>
            <el-button type="primary" size="small" :loading="loading" @click="loadList">刷新</el-button>
          </div>
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
          <el-button @click="batchDialog = true">批量导入</el-button>
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

    <el-card shadow="hover" class="alert-card">
      <template #header>
        <div class="header">
          <span>选股信号覆盖</span>
          <el-button type="primary" size="small" :loading="coverageLoading" @click="loadCoverage">查询覆盖</el-button>
        </div>
      </template>

      <div v-loading="coverageLoading">
        <template v-if="coverage">
          <p class="scan-meta">
            信号日：{{ coverage.trade_date || '—' }} · 自选 {{ coverage.total }} 只 · 命中 {{ coverage.hit_count }} 只
          </p>
          <el-table v-if="coverage.items.length" :data="coverage.items" size="small" stripe border>
            <el-table-column type="index" label="#" width="56" />
            <el-table-column prop="stock_code" label="代码" min-width="90" />
            <el-table-column prop="stock_name" label="名称" min-width="100" />
            <el-table-column prop="hit_count" label="命中策略数" width="100" />
            <el-table-column label="命中策略" min-width="240">
              <template #default="{ row }">
                <el-tag v-for="h in row.hit_strategies" :key="h.strategy" size="small" class="signal-tag">
                  {{ strategyLabel(h.strategy) }} #{{ h.rank_no }}
                </el-tag>
                <span v-if="!row.hit_strategies.length" class="text-muted">—</span>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else-if="!coverageLoading" :description="coverage.note" />
          <p class="scan-meta basis">{{ coverage.note }}</p>
        </template>
        <el-empty v-else-if="!coverageLoading" description="点击查询自选股被选股信号命中情况" />
      </div>
    </el-card>

    <el-dialog v-model="batchDialog" title="批量导入自选股" width="520px">
      <p class="hint">每行一个代码，可选「代码,交易所」或「代码 交易所」；省略交易所时按代码前缀推断（6→沪，0/3→深，4/8→北）。</p>
      <el-input v-model="batchText" type="textarea" :rows="8" placeholder="600000&#10;000001, SZSE&#10;300750 SZSE" />
      <template v-if="batchResult">
        <p class="scan-meta">
          新增 {{ batchResult.added }} · 跳过 {{ batchResult.skipped }}
        </p>
      </template>
      <template #footer>
        <el-button @click="batchDialog = false">关闭</el-button>
        <el-button type="primary" :loading="batching" @click="doBatchAdd">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { listWatchlist, addWatchlist, removeWatchlist, scanWatchlistAlerts, getWatchlistSignalCoverage, batchAddWatchlist, type WatchlistItem, type WatchlistAlertScan, type SignalCoverageResponse, type BatchAddResult } from '@/api/watchlist'
import { useToast } from '@/composables/useToast'
import dayjs from 'dayjs'

const STRATEGY_LABELS: Record<string, string> = {
  CLASSIC_SELECTOR: '经典多因子',
  OVERSOLD_REBOUND: '超跌反弹',
  TREND: '趋势打分',
  COMPREHENSIVE: '综合三维度',
  MULTI_DIM: '多维度 v1'
}

function strategyLabel(s: string): string {
  return STRATEGY_LABELS[s] || s
}

const toast = useToast()
const loading = ref(false)
const adding = ref(false)
const removingKey = ref('')
const items = ref<WatchlistItem[]>([])
const scanning = ref(false)
const scan = ref<WatchlistAlertScan | null>(null)
const coverageLoading = ref(false)
const coverage = ref<SignalCoverageResponse | null>(null)
const batchDialog = ref(false)
const batchText = ref('')
const batching = ref(false)
const batchResult = ref<BatchAddResult | null>(null)

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

async function loadCoverage() {
  coverageLoading.value = true
  try {
    coverage.value = await getWatchlistSignalCoverage()
  } catch (e: any) {
    toast.error(e?.message || '信号覆盖查询失败')
  } finally {
    coverageLoading.value = false
  }
}

async function doBatchAdd() {
  if (!batchText.value.trim()) {
    toast.warning('请输入至少一行代码')
    return
  }
  batching.value = true
  batchResult.value = null
  try {
    batchResult.value = await batchAddWatchlist(batchText.value)
    toast.success(`新增 ${batchResult.value.added} 只，跳过 ${batchResult.value.skipped} 只`)
    await loadList()
  } catch (e: any) {
    toast.error(e?.message || '批量导入失败')
  } finally {
    batching.value = false
  }
}

function exportCsv() {
  if (!items.value.length) return
  const lines: string[] = ['代码,交易所,名称,备注,添加时间']
  for (const it of items.value) {
    lines.push([
      it.stock_code,
      it.exchange_type,
      it.stock_name || '',
      (it.note || '').replace(/,/g, '，'),
      it.created_at || ''
    ].join(','))
  }
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `watchlist-${dayjs().format('YYYY-MM-DD')}.csv`
  a.click()
  URL.revokeObjectURL(url)
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
.header-actions {
  display: flex;
  gap: 8px;
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
.text-danger { color: var(--up); }
.text-success { color: var(--down); }
.text-muted { color: var(--text-muted); }
</style>
