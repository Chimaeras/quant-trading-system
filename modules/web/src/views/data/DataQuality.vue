<template>
  <div class="quality-page terminal-page">
    <el-card shadow="hover">
      <template #header>
        <div class="header">
          <span>数据质量驾驶舱</span>
          <el-button type="primary" size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <div v-loading="loading">
        <template v-if="report">
          <el-alert
            :type="healthType"
            :closable="false"
            show-icon
            class="health-alert"
            :title="report.health.verdict"
          />

          <div class="grid">
            <el-card shadow="never" class="q-card">
              <template #header>交易日覆盖</template>
              <div class="q-meta">
                <span>区间：{{ report.earliest_date || '—' }} ~ {{ report.latest_date || '—' }}</span>
                <span>预期交易日：{{ report.trading_days.expected }}</span>
                <span>实际交易日：{{ report.trading_days.actual }}</span>
                <span :class="{ 'text-danger': report.trading_days.missing_count > 0 }">
                  缺失：{{ report.trading_days.missing_count }}
                </span>
              </div>
              <template v-if="report.trading_days.missing_dates?.length">
                <el-button link type="primary" size="small" @click="showMissingDates = !showMissingDates">
                  {{ showMissingDates ? '收起' : '查看缺失日期明细' }}（{{ report.trading_days.missing_dates.length }} 条）
                </el-button>
                <div v-if="showMissingDates" class="date-list">
                  <el-tag v-for="d in report.trading_days.missing_dates" :key="d" size="small" class="date-tag">{{ d }}</el-tag>
                </div>
              </template>
              <p class="q-note">{{ report.trading_days.note }}</p>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>更新滞后</template>
              <div class="q-meta">
                <span>今日：{{ report.update_staleness.today }}</span>
                <span>最新行情：{{ report.update_staleness.latest_date || '—' }}</span>
                <span>滞后天数：{{ report.update_staleness.days_behind ?? '—' }}</span>
                <span :class="report.update_staleness.stale ? 'text-danger' : 'text-success'">
                  {{ report.update_staleness.stale ? '滞后' : '正常' }}
                </span>
              </div>
              <p v-if="report.update_staleness.warning" class="q-note text-danger">
                {{ report.update_staleness.warning }}
              </p>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>上市日缺失</template>
              <div class="q-meta">
                <span>缺失数量：{{ report.list_date.missing_count }}</span>
                <span>可回填：{{ report.list_date.backfillable_count }}</span>
              </div>
              <p class="q-note">{{ report.list_date.note }}</p>
            </el-card>

            <el-card shadow="never" class="q-card">
              <template #header>复权口径</template>
              <div class="q-meta">
                <span>当前基准：{{ report.adjust_factor.current_basis }}</span>
              </div>
              <p class="q-note">{{ report.adjust_factor.scope }}</p>
              <p class="q-note text-warning">{{ report.adjust_factor.warning }}</p>
            </el-card>

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>不复权行情 / 复权因子覆盖</span>
                  <el-button size="small" type="primary" @click="rawDialogVisible = true">回填不复权数据</el-button>
                </div>
              </template>
              <template v-if="report.raw_and_adjust">
                <div class="raw-section">
                  <div class="raw-sub-title">不复权行情（market_price_raw）</div>
                  <div class="q-meta">
                    <span>状态：{{ report.raw_and_adjust.market_price_raw.status }}</span>
                    <span>总行数：{{ report.raw_and_adjust.market_price_raw.total_rows }}</span>
                    <span>覆盖证券：{{ report.raw_and_adjust.market_price_raw.distinct_stocks }}</span>
                    <span>覆盖交易日：{{ report.raw_and_adjust.market_price_raw.distinct_trade_dates }}</span>
                    <span>区间：{{ report.raw_and_adjust.market_price_raw.earliest_date || '—' }} ~ {{ report.raw_and_adjust.market_price_raw.latest_date || '—' }}</span>
                  </div>
                </div>
                <div class="raw-section">
                  <div class="raw-sub-title">复权因子（adjust_factor）</div>
                  <div class="q-meta">
                    <span>状态：{{ report.raw_and_adjust.adjust_factor.status }}</span>
                    <span>总行数：{{ report.raw_and_adjust.adjust_factor.total_rows }}</span>
                    <span>覆盖证券：{{ report.raw_and_adjust.adjust_factor.distinct_stocks }}</span>
                    <span>本地推算：{{ report.raw_and_adjust.adjust_factor.by_source?.derived_local ?? 0 }} 条</span>
                    <span>官方源：{{ report.raw_and_adjust.adjust_factor.by_source?.official ?? 0 }} 条</span>
                  </div>
                  <p class="q-note">{{ report.raw_and_adjust.adjust_factor.note }}</p>
                </div>
              </template>
              <el-empty v-else description="无覆盖数据" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>历史证券主数据（security_master_history）</span>
                  <el-button size="small" type="primary" :loading="smInitLoading" @click="confirmInitSecurityMaster">
                    从 stock_info 初始化
                  </el-button>
                </div>
              </template>
              <template v-if="report.security_master">
                <div class="q-meta">
                  <span>状态：{{ report.security_master.status }}</span>
                  <span>总行数：{{ report.security_master.total_rows }}</span>
                  <span>覆盖证券：{{ report.security_master.distinct_stocks }}</span>
                  <span>当前快照：{{ report.security_master.by_source?.current_snapshot ?? 0 }} 条</span>
                  <span>东方财富：{{ report.security_master.by_source?.eastmoney ?? 0 }} 条</span>
                  <span>Tushare：{{ report.security_master.by_source?.tushare ?? 0 }} 条</span>
                  <span>可靠度 limited：{{ report.security_master.by_reliability?.limited ?? 0 }} 条</span>
                  <span>可靠度 official：{{ report.security_master.by_reliability?.official ?? 0 }} 条</span>
                </div>
                <p class="q-note">{{ report.security_master.note }}</p>
              </template>
              <el-empty v-else description="无覆盖数据" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>真实交易日历（trading_calendar）</span>
                  <el-button size="small" type="primary" @click="calendarDialogVisible = true">反推交易日历</el-button>
                </div>
              </template>
              <template v-if="report.trading_calendar">
                <div class="q-meta">
                  <span>状态：{{ report.trading_calendar.status }}</span>
                  <span>总行数：{{ report.trading_calendar.total_rows }}</span>
                  <span>区间：{{ report.trading_calendar.earliest_date || '—' }} ~ {{ report.trading_calendar.latest_date || '—' }}</span>
                  <span>指数反推：{{ report.trading_calendar.by_source?.eastmoney_derived ?? 0 }} 条</span>
                  <span>Tushare：{{ report.trading_calendar.by_source?.tushare ?? 0 }} 条</span>
                </div>
                <p class="q-note">{{ report.trading_calendar.note }}</p>
                <template v-if="report.trading_calendar.diff_vs_market_data">
                  <div class="raw-section">
                    <div class="raw-sub-title">真实交易日历 vs 行情数据差异</div>
                    <div class="q-meta">
                      <span>日历交易日：{{ report.trading_calendar.diff_vs_market_data.calendar_days }}</span>
                      <span>行情交易日：{{ report.trading_calendar.diff_vs_market_data.market_data_days }}</span>
                      <span :class="{ 'text-danger': report.trading_calendar.diff_vs_market_data.missing_in_market_data > 0 }">
                        行情缺失：{{ report.trading_calendar.diff_vs_market_data.missing_in_market_data }}
                      </span>
                    </div>
                    <template v-if="report.trading_calendar.diff_vs_market_data.missing_dates?.length">
                      <el-button link type="primary" size="small" @click="showCalendarDiffMissing = !showCalendarDiffMissing">
                        {{ showCalendarDiffMissing ? '收起' : '查看行情缺失日期明细' }}（{{ report.trading_calendar.diff_vs_market_data.missing_dates.length }} 条）
                      </el-button>
                      <div v-if="showCalendarDiffMissing" class="date-list">
                        <el-tag v-for="d in report.trading_calendar.diff_vs_market_data.missing_dates" :key="d" size="small" class="date-tag">{{ d }}</el-tag>
                      </div>
                    </template>
                    <p class="q-note">{{ report.trading_calendar.diff_vs_market_data.note }}</p>
                  </div>
                </template>
              </template>
              <el-empty v-else description="无覆盖数据" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>指数数据（index_quote）</span>
                  <el-button size="small" type="primary" @click="indexDialogVisible = true">拉取指数行情</el-button>
                </div>
              </template>
              <template v-if="report.index_data">
                <div class="q-meta">
                  <span>状态：{{ report.index_data.status }}</span>
                  <span>总行数：{{ report.index_data.total_rows }}</span>
                  <span>覆盖指数：{{ report.index_data.distinct_indices }}</span>
                  <span>东方财富：{{ report.index_data.by_source?.eastmoney ?? 0 }} 条</span>
                  <span>Tushare：{{ report.index_data.by_source?.tushare ?? 0 }} 条</span>
                </div>
                <div class="raw-section">
                  <div class="raw-sub-title">各指数覆盖</div>
                  <div v-for="idx in report.index_data.indices" :key="idx.index_code" class="q-meta">
                    <span>{{ idx.index_name }}（{{ idx.index_code }}）</span>
                    <span>行数：{{ idx.rows }}</span>
                    <span>区间：{{ idx.earliest_date || '—' }} ~ {{ idx.latest_date || '—' }}</span>
                  </div>
                </div>
                <p class="q-note">{{ report.index_data.note }}</p>
              </template>
              <el-empty v-else description="无覆盖数据" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>数据快照注册表（data_snapshot）</span>
                  <el-button size="small" type="primary" :loading="snapshotCreating" @click="runCreateSnapshot">创建快照</el-button>
                </div>
              </template>
              <template v-if="report.data_snapshot">
                <div class="q-meta">
                  <span>状态：{{ report.data_snapshot.status }}</span>
                  <span>快照总数：{{ report.data_snapshot.total_snapshots }}</span>
                </div>
                <p class="q-note">{{ report.data_snapshot.note }}</p>
                <template v-if="report.data_snapshot.latest && report.data_snapshot.latest.length > 0">
                  <div class="raw-section">
                    <div class="raw-sub-title">最近快照</div>
                    <el-table :data="report.data_snapshot.latest" size="small" border>
                      <el-table-column prop="snapshot_id" label="快照ID" width="150" />
                      <el-table-column prop="cutoff_time" label="截止日" width="110" />
                      <el-table-column prop="hash" label="hash" show-overflow-tooltip>
                        <template #default="{ row }">
                          <span class="hash-cell">{{ row.hash }}</span>
                        </template>
                      </el-table-column>
                      <el-table-column prop="created_by" label="来源" width="80" />
                      <el-table-column prop="created_at" label="创建时间" width="170" />
                    </el-table>
                  </div>
                </template>
              </template>
              <el-empty v-else description="无覆盖数据" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>历史重放（PIT 股票池健康）</span>
                  <el-button size="small" type="primary" :loading="replayLoading" @click="runReplay">执行重放</el-button>
                </div>
              </template>
              <div class="replay-form">
                <span>起始日</span>
                <el-date-picker v-model="replayForm.startDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 150px" />
                <span>截止日</span>
                <el-date-picker v-model="replayForm.endDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 150px" />
              </div>
              <p class="q-note">按交易日区间重放 PIT 股票池（PIT 证券主数据 ∩ 当日有行情），统计每日池规模与缺失；幂等只读。</p>
              <template v-if="replayResult">
                <div class="raw-section" v-if="replayResult.summary">
                  <div class="raw-sub-title">汇总</div>
                  <div class="q-meta">
                    <span>交易日数：{{ replayResult.trading_days_count }}</span>
                    <span>平均池规模：{{ replayResult.summary.avg_pool_size }}</span>
                    <span>PIT 缺失合计：{{ replayResult.summary.total_pit_missing }}</span>
                    <span>行情缺失合计：{{ replayResult.summary.total_market_data_missing }}</span>
                    <span>有池日数：{{ replayResult.summary.days_with_pool }}</span>
                  </div>
                  <p class="q-note">{{ replayResult.summary.note }}</p>
                </div>
                <div class="raw-section" v-if="replayResult.daily && replayResult.daily.length > 0">
                  <div class="raw-sub-title">每日明细（前 20 日）</div>
                  <el-table :data="replayResult.daily.slice(0, 20)" size="small" border>
                    <el-table-column prop="date" label="日期" width="110" />
                    <el-table-column prop="pit_master_count" label="PIT主数据" width="100" />
                    <el-table-column prop="market_data_count" label="行情数" width="90" />
                    <el-table-column prop="pool_size" label="池规模" width="80" />
                    <el-table-column prop="pit_missing" label="PIT缺失" width="80" />
                    <el-table-column prop="market_data_missing" label="行情缺失" width="90" />
                  </el-table>
                </div>
                <p class="q-note">{{ replayResult.note }}</p>
              </template>
              <el-empty v-else-if="!replayLoading" description="点击执行重放查看股票池健康报告" :image-size="40" />
            </el-card>

            <el-card shadow="never" class="q-card raw-card">
              <template #header>
                <div class="card-header-row">
                  <span>T+2/T+3 标签工厂（label_snapshot）</span>
                  <el-button size="small" type="primary" :loading="labelBuilding" @click="runBuildLabels">生成标签</el-button>
                </div>
              </template>
              <div class="replay-form">
                <span>起始日</span>
                <el-date-picker v-model="labelForm.startDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 150px" />
                <span>截止日</span>
                <el-date-picker v-model="labelForm.endDate" type="date" value-format="YYYY-MM-DD" size="small" style="width: 150px" />
                <span>版本</span>
                <el-input v-model="labelForm.labelVersion" size="small" style="width: 120px" placeholder="label_v1" />
              </div>
              <p class="q-note">基于 PIT 股票池生成 T+2/T+3 标签（净收益/二分类/成交状态），保留所有拒单样本不净化；幂等跳过已存在。</p>
              <template v-if="labelResult">
                <div class="raw-section">
                  <div class="raw-sub-title">生成结果</div>
                  <div class="q-meta">
                    <span>状态：{{ labelResult.status }}</span>
                    <span>版本：{{ labelResult.label_version }}</span>
                    <span>交易日数：{{ labelResult.trading_days }}</span>
                    <span>生成：{{ labelResult.generated }} 条</span>
                    <span>跳过已存在：{{ labelResult.skipped_existing }} 条</span>
                  </div>
                  <template v-if="labelResult.exec_status_counts">
                    <div class="q-meta" style="margin-top: 6px">
                      <span>FILLED：{{ labelResult.exec_status_counts.FILLED ?? 0 }}</span>
                      <span>NOT_BUYABLE：{{ labelResult.exec_status_counts.NOT_BUYABLE ?? 0 }}</span>
                      <span>SUSPENDED：{{ labelResult.exec_status_counts.SUSPENDED ?? 0 }}</span>
                      <span>NOT_SELLABLE：{{ labelResult.exec_status_counts.NOT_SELLABLE ?? 0 }}</span>
                      <span>DELISTED：{{ labelResult.exec_status_counts.DELISTED ?? 0 }}</span>
                    </div>
                  </template>
                </div>
              </template>
              <el-empty v-else-if="!labelBuilding" description="点击生成标签查看成交状态分布" :image-size="40" />
            </el-card>
          </div>
        </template>
        <el-empty v-else-if="!loading" description="点击刷新查看数据质量报告" />
      </div>
    </el-card>

    <!-- 不复权数据回填对话框 -->
    <el-dialog v-model="rawDialogVisible" title="回填不复权行情 + 推算复权因子" width="460px">
      <el-form label-width="90px">
        <el-form-item label="股票代码">
          <el-input v-model="rawForm.stockCode" placeholder="如 000001" />
        </el-form-item>
        <el-form-item label="交易所">
          <el-select v-model="rawForm.exchange" style="width: 100%">
            <el-option label="深交所 SZSE" value="SZSE" />
            <el-option label="上交所 SSE" value="SSE" />
            <el-option label="北交所 BSE" value="BSE" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="rawForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="rawForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="q-note">
        将拉取东方财富不复权日 K 写入 market_price_raw，并按 qfq/raw 推算复权因子；已存在记录自动跳过（幂等）。
      </p>
      <template #footer>
        <el-button @click="rawDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="rawLoading" @click="runBackfillRaw">执行回填</el-button>
      </template>
    </el-dialog>

    <!-- 交易日历反推对话框 -->
    <el-dialog v-model="calendarDialogVisible" title="反推真实交易日历（上证指数）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="起始日">
          <el-date-picker v-model="calendarForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="calendarForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="q-note">
        将拉取上证指数日K，其 trade_date 集合即为真实交易日（覆盖法定节假日与临时休市）。已存在交易日自动跳过（幂等）。
      </p>
      <template #footer>
        <el-button @click="calendarDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="calendarLoading" @click="runInitCalendar">执行反推</el-button>
      </template>
    </el-dialog>

    <!-- 指数行情拉取对话框 -->
    <el-dialog v-model="indexDialogVisible" title="拉取指数行情" width="420px">
      <el-form label-width="90px">
        <el-form-item label="指数">
          <el-select v-model="indexForm.indexCode" style="width: 100%" placeholder="全部 4 个指数">
            <el-option label="全部（沪深300/中证500/中证1000/上证指数）" value="" />
            <el-option label="沪深300 (000300)" value="000300" />
            <el-option label="中证500 (000905)" value="000905" />
            <el-option label="中证1000 (000852)" value="000852" />
            <el-option label="上证指数 (000001)" value="000001" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="indexForm.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="indexForm.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <p class="q-note">
        从东方财富拉取指数日K写入 index_quote；已存在记录自动跳过（幂等）。
      </p>
      <template #footer>
        <el-button @click="indexDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="indexLoading" @click="runFetchIndex">执行拉取</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, onMounted } from 'vue'
import dayjs from 'dayjs'
import { ElMessageBox } from 'element-plus'
import { dataApi } from '@/api/data'
import { useToast } from '@/composables/useToast'

interface QualityReport {
  earliest_date: string
  latest_date: string
  trading_days: { expected: number; actual: number; missing_count: number; missing_dates: string[]; note: string }
  update_staleness: { today: string; latest_date?: string; days_behind: number | null; stale: boolean; threshold_days: number; warning: string | null }
  list_date: { missing_count: number; backfillable_count: number; note: string }
  adjust_factor: { current_basis: string; scope: string; warning: string }
  raw_and_adjust?: {
    market_price_raw: {
      total_rows: number; distinct_stocks: number; distinct_trade_dates: number
      earliest_date: string; latest_date: string; source: string; status: string
    }
    adjust_factor: {
      total_rows: number; distinct_stocks: number
      by_source?: { derived_local: number; official: number }
      by_reliability?: { limited: number }
      status: string; note: string
    }
  }
  security_master?: {
    total_rows: number; distinct_stocks: number
    by_source?: { current_snapshot: number; eastmoney: number; tushare: number }
    by_reliability?: { limited: number; official: number }
    status: string; note: string
  }
  trading_calendar?: {
    total_rows: number
    earliest_date: string; latest_date: string
    by_source?: { eastmoney_derived: number; tushare: number }
    by_reliability?: { limited: number; official: number }
    status: string; note: string
    diff_vs_market_data?: {
      calendar_days: number; market_data_days: number; missing_in_market_data: number
      missing_dates: string[]; note: string
    }
  }
  index_data?: {
    total_rows: number; distinct_indices: number
    indices?: Array<{ index_code: string; index_name: string; rows: number; earliest_date: string; latest_date: string }>
    by_source?: { eastmoney: number; tushare: number }
    by_reliability?: { limited: number; official: number }
    status: string; note: string
  }
  data_snapshot?: {
    total_snapshots: number
    status: string; note: string
    latest?: Array<{ snapshot_id: string; cutoff_time: string; hash: string; created_by: string; created_at: string; row_counts: string }>
  }
  health: { status: string; verdict: string }
}

const toast = useToast()
const loading = ref(false)
const report = ref<QualityReport | null>(null)
const showMissingDates = ref(false)
const showCalendarDiffMissing = ref(false)

const rawDialogVisible = ref(false)
const rawLoading = ref(false)
const rawForm = reactive({
  stockCode: '',
  exchange: 'SZSE',
  startDate: dayjs().subtract(6, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD')
})

const smInitLoading = ref(false)

const calendarDialogVisible = ref(false)
const calendarLoading = ref(false)
const calendarForm = reactive({
  startDate: dayjs().subtract(2, 'year').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD')
})

const indexDialogVisible = ref(false)
const indexLoading = ref(false)
const indexForm = reactive({
  indexCode: '',
  startDate: dayjs().subtract(2, 'year').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD')
})

const snapshotCreating = ref(false)

const replayLoading = ref(false)
const replayResult = ref<any>(null)
const replayForm = reactive({
  startDate: dayjs().subtract(1, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD')
})

const labelBuilding = ref(false)
const labelResult = ref<any>(null)
const labelForm = reactive({
  startDate: dayjs().subtract(1, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD'),
  labelVersion: 'label_v1'
})

const healthType = computed<'success' | 'warning' | 'info'>(() => {
  if (!report.value) return 'info'
  return report.value.health.status === 'ok' ? 'success' : 'warning'
})

async function load() {
  loading.value = true
  try {
    report.value = await dataApi.getQuality()
  } catch (e: any) {
    toast.error(e?.message || '数据质量报告加载失败')
  } finally {
    loading.value = false
  }
}

async function runBackfillRaw() {
  if (!rawForm.stockCode || !rawForm.startDate || !rawForm.endDate) {
    toast.warning('请填写股票代码与起止日期')
    return
  }
  rawLoading.value = true
  try {
    const res = await dataApi.backfillRaw({
      stockCode: rawForm.stockCode,
      exchange: rawForm.exchange,
      startDate: rawForm.startDate,
      endDate: rawForm.endDate
    })
    toast.success(res.message || `不复号新增 ${res.raw_inserted} 条，复权因子生成 ${res.factor_generated} 条`)
    rawDialogVisible.value = false
    await load()
  } catch (e: any) {
    toast.error(e?.message || '不复权数据回填失败')
  } finally {
    rawLoading.value = false
  }
}

async function confirmInitSecurityMaster() {
  try {
    await ElMessageBox.confirm(
      '将用 stock_info 当前快照为每只标的生成一条 valid_from=上市日 / valid_to=null 的历史主数据记录（source=current_snapshot, reliability=limited）。无历史 ST/改名/行业变更数据，仅消除「上市日至今状态不变」标的的前视风险。同键已存在自动跳过（幂等）。是否继续？',
      '初始化历史证券主数据',
      { confirmButtonText: '执行初始化', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  smInitLoading.value = true
  try {
    const res = await dataApi.initSecurityMaster()
    toast.success(`初始化完成：新增 ${res.initialized} 条，跳过已存在 ${res.skipped_existing} 条，跳过无上市日 ${res.skipped_no_listdate} 条`)
    await load()
  } catch (e: any) {
    toast.error(e?.message || '历史证券主数据初始化失败')
  } finally {
    smInitLoading.value = false
  }
}

async function runInitCalendar() {
  if (!calendarForm.startDate || !calendarForm.endDate) {
    toast.warning('请填写起止日期')
    return
  }
  calendarLoading.value = true
  try {
    const res = await dataApi.initTradingCalendar({
      startDate: calendarForm.startDate,
      endDate: calendarForm.endDate
    })
    toast.success(`反推完成：拉取 ${res.total_fetched} 个交易日，新增 ${res.inserted} 条，跳过已存在 ${res.skipped_existing} 条`)
    calendarDialogVisible.value = false
    await load()
  } catch (e: any) {
    toast.error(e?.message || '交易日历反推失败')
  } finally {
    calendarLoading.value = false
  }
}

async function runFetchIndex() {
  if (!indexForm.startDate || !indexForm.endDate) {
    toast.warning('请填写起止日期')
    return
  }
  indexLoading.value = true
  try {
    const res = await dataApi.fetchIndexData({
      indexCode: indexForm.indexCode || undefined,
      startDate: indexForm.startDate,
      endDate: indexForm.endDate
    })
    if (Array.isArray(res?.results)) {
      const total = res.results.reduce((s: number, r: any) => s + (r.inserted ?? 0), 0)
      toast.success(`拉取完成：共 ${res.results.length} 个指数，新增 ${total} 条`)
    } else {
      toast.success(`拉取完成：新增 ${res?.inserted ?? 0} 条，跳过 ${res?.skipped ?? 0} 条`)
    }
    indexDialogVisible.value = false
    await load()
  } catch (e: any) {
    toast.error(e?.message || '指数行情拉取失败')
  } finally {
    indexLoading.value = false
  }
}

async function runCreateSnapshot() {
  snapshotCreating.value = true
  try {
    const res = await dataApi.createSnapshot()
    toast.success(res.message || `快照已创建：${res.snapshot_id}`)
    await load()
  } catch (e: any) {
    toast.error(e?.message || '数据快照创建失败')
  } finally {
    snapshotCreating.value = false
  }
}

async function runReplay() {
  if (!replayForm.startDate || !replayForm.endDate) {
    toast.warning('请填写起止日期')
    return
  }
  replayLoading.value = true
  try {
    replayResult.value = await dataApi.runReplay({
      startDate: replayForm.startDate,
      endDate: replayForm.endDate
    })
    if (replayResult.value?.status === 'no_calendar') {
      toast.warning('交易日历未初始化，请先反推交易日历')
    } else {
      toast.success(`重放完成：${replayResult.value?.trading_days_count ?? 0} 个交易日`)
    }
  } catch (e: any) {
    toast.error(e?.message || '历史重放失败')
  } finally {
    replayLoading.value = false
  }
}

async function runBuildLabels() {
  if (!labelForm.startDate || !labelForm.endDate) {
    toast.warning('请填写起止日期')
    return
  }
  labelBuilding.value = true
  try {
    labelResult.value = await dataApi.buildLabels({
      startDate: labelForm.startDate,
      endDate: labelForm.endDate,
      labelVersion: labelForm.labelVersion
    })
    if (labelResult.value?.status === 'no_calendar') {
      toast.warning('交易日历未初始化，请先反推交易日历')
    } else {
      toast.success(`标签生成完成：${labelResult.value?.generated ?? 0} 条`)
    }
  } catch (e: any) {
    toast.error(e?.message || '标签生成失败')
  } finally {
    labelBuilding.value = false
  }
}

onMounted(() => load())
</script>

<style scoped>
.quality-page {
  max-width: 1200px;
  margin: 0 auto;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.health-alert {
  margin-bottom: 16px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.q-card {
  background: rgba(9, 18, 34, 0.72);
  border: 1px solid rgba(145, 196, 255, 0.18);
}
.q-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.q-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
.date-list {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.date-tag {
  font-family: monospace;
}
.text-danger { color: #f56c6c; }
.text-success { color: #67c23a; }
.text-warning { color: #e6a23c; }
.raw-card {
  grid-column: 1 / -1;
}
.card-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.raw-section {
  margin-bottom: 12px;
}
.raw-section:last-child {
  margin-bottom: 0;
}
.raw-sub-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--accent-1);
  margin-bottom: 6px;
}
.hash-cell {
  font-family: monospace;
  font-size: 12px;
  color: var(--text-muted);
}
.replay-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 8px;
}
</style>
