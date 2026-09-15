<template>
  <PageShell>
    <SectionCard title="因子研究面板">
      <template #extra>
        <el-tooltip content="V1 研究页面（截面 IC / 分桶），维持只读展示，新因子流水线见研究任务工作台" placement="top">
          <el-tag size="small" effect="plain" type="info" class="capability-tag">Legacy</el-tag>
        </el-tooltip>
      </template>

      <el-form inline class="form">
        <el-form-item label="策略">
          <el-select v-model="strategy" placeholder="全部策略" clearable style="width: 180px">
            <el-option label="全部策略" value="" />
            <el-option v-for="(label, key) in STRATEGY_LABELS" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="startDate" type="date" value-format="YYYY-MM-DD" placeholder="起始日" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="endDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日" />
        </el-form-item>
        <el-form-item label="IC持仓日">
          <el-input-number v-model="holdingDays" :min="1" :max="60" :step="1" controls-position="right" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="run">研究</el-button>
          <el-button :loading="unifiedLoading" @click="runUnified">多周期 IC 对比</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        v-if="result?.source === 'LEGACY_SELECTION_BIASED'"
        type="warning"
        :closable="false"
        show-icon
        class="note-alert"
        title="下方 selector 路径为 LEGACY_SELECTION_BIASED（sparse selector_result 取样，存在选择偏差），仅供历史对照；权威 IC 请使用上方快照全截面路径"
      />

      <!-- V2-MP-022：完整 PIT 快照截面 IC（权威路径，不读 selector/qfq） -->
      <el-divider content-position="left">
        <span>完整 PIT 快照截面 IC（V2-MP-022 权威路径）</span>
      </el-divider>
      <el-form inline class="form">
        <el-form-item label="策略">
          <el-select v-model="snapStrategy" style="width: 170px">
            <el-option v-for="(label, key) in SNAPSHOT_STRATEGIES" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="snapshotId">
          <el-input v-model="snapSnapshotId" placeholder="MP-016 数据快照 ID" style="width: 190px" />
        </el-form-item>
        <el-form-item label="labelVersion">
          <el-input v-model="snapLabelVersion" placeholder="可空；EXECUTABLE 需 label_v2" style="width: 190px" />
        </el-form-item>
        <el-form-item label="起始日">
          <el-date-picker v-model="snapStartDate" type="date" value-format="YYYY-MM-DD" placeholder="起始日" />
        </el-form-item>
        <el-form-item label="截止日">
          <el-date-picker v-model="snapEndDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日" />
        </el-form-item>
        <el-form-item label="IC持仓日">
          <el-input-number v-model="snapHoldingDays" :min="1" :max="60" :step="1" controls-position="right" style="width: 120px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="snapshotLoading" @click="runSnapshot">研究</el-button>
        </el-form-item>
      </el-form>

      <div v-loading="snapshotLoading">
        <template v-if="snapshotResult">
          <el-alert type="info" :closable="false" show-icon class="note-alert" :title="snapshotResult.note || ''" />
          <div class="q-meta funnel-meta">
            <span>来源：{{ snapshotResult.source }}（{{ snapshotResult.universe_source }}）</span>
            <span>snapshot：{{ snapshotResult.snapshot_id }}</span>
            <span>策略：{{ strategyLabel(snapshotResult.strategy) }}</span>
            <span>schema_hash：{{ snapshotResult.schema_hash }}</span>
            <span>label_version：{{ snapshotResult.label_version || '未提供' }}</span>
            <span>截面日：{{ snapshotResult.cross_section_days }}</span>
            <span>可复用分区：{{ snapshotResult.partitions_reusable }}</span>
            <span>跳过分区：{{ snapshotResult.partitions_skipped?.length ? snapshotResult.partitions_skipped.join('、') : '无' }}</span>
            <span>metric_scopes：{{ (snapshotResult.metric_scopes || []).join(' / ') }}</span>
            <span>EXECUTABLE：{{ snapshotResult.executable_metric_scope === 'UNAVAILABLE' ? '不可用（需 labelVersion）' : snapshotResult.executable_metric_scope }}</span>
          </div>

          <div class="grid">
            <SectionCard class="q-card" pad="sm" title="样本漏斗">
              <div class="q-meta">
                <span>rows_total（全截面行）：{{ snapshotResult.funnel?.rows_total ?? '—' }}</span>
                <span>scored_valid（得分>0）：{{ snapshotResult.funnel?.scored_valid ?? '—' }}</span>
                <span>覆盖率：{{ snapshotResult.funnel?.coverage ?? '—' }}%</span>
                <span v-if="snapshotResult.funnel?.exec_status_breakdown">
                  执行状态：
                  <template v-for="(cnt, st) in snapshotResult.funnel.exec_status_breakdown" :key="st">{{ st }}={{ cnt }} </template>
                </span>
                <span>tradable：{{ snapshotResult.funnel?.tradable_status ?? '—' }}</span>
              </div>
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                class="ic-note"
                :title="snapshotResult.funnel?.tradable_note || 'EXECUTABLE 样本 = label_v2 FILLED；其余状态计入拒绝漏斗'"
              />
            </SectionCard>

            <SectionCard class="q-card" pad="sm" title="每日漏斗（覆盖/缺失）">
              <el-table :data="snapshotResult.daily_funnel" size="small" stripe border max-height="320">
                <el-table-column prop="signal_date" label="信号日" min-width="110" />
                <el-table-column prop="rows_total" label="全截面行" width="88" />
                <el-table-column prop="scored_valid" label="有效得分" width="80" />
                <el-table-column prop="raw_observations" label="RAW观测" width="82" />
                <el-table-column prop="cost_approx_observations" label="成本近似观测" width="104" />
                <el-table-column prop="executable_observations" label="可执行观测" width="96" />
                <el-table-column prop="labels_matched" label="标签命中" width="80" />
                <el-table-column prop="labels_missing" label="标签缺失" width="80" />
              </el-table>
            </SectionCard>
          </div>

          <div class="grid">
            <SectionCard
              v-for="(blk, scopeKey) in snapshotResult.scopes"
              :key="scopeKey"
              class="q-card rank-ic-card"
              pad="sm"
              :title="`IC（${scopeKey}）· 持仓 ${snapshotResult.holding_days} 日`"
            >
              <div class="q-meta">
                <span>截面日：{{ blk.cross_section_days }}</span>
                <span title="参与 IC 计算的有限观测总数（已剔除非有限值）">截面日观测：{{ blk.total_observations }}</span>
                <span title="信号日得分与 forward return 的秩相关均值；raw 口径">Rank IC 均值：{{ fmt4(blk.rank_ic_mean) }}</span>
                <span>Rank IC 标准差：{{ fmt4(blk.rank_ic_std) }}</span>
                <span title="IC 序列无离散度（std = 0，含截面日不足 2）时无定义，显示「—」而不是 0">ICIR：{{ fmt4(blk.rank_ic_ir) }}</span>
                <span>Rank IC 正率：{{ pct(blk.rank_ic_positive_rate) }}</span>
                <span title="线性 IC 均值；不可定义时（样本不足/任一侧无区分度）为 null，显示「—」而不是占位 0.0，须看 Pearson 支撑日数">Pearson IC：{{ fmt4(blk.pearson_ic_mean) }}</span>
                <span>单调性：{{ monoLabel(blk.monotonicity) }}</span>
                <span title="高分组均值 − 低分组均值（两个多头腿之差，非美元中性多空）">多空 spread（高−低）：{{ pct4(blk.long_short_spread) }}</span>
                <span v-if="blk.long_short_spread_neutral !== undefined" title="美元中性净价差 = 毛价差 − 2×单腿往返成本（真多空各付一次往返成本）；本块收益若本身已是净口径则不适用，显示「—」">
                  美元中性净 spread：{{ pct4(blk.long_short_spread_neutral) }}
                </span>
                <span v-if="blk.cost_per_leg_round_trip !== undefined" title="单腿往返成本（佣金+印花税+双边滑点乘性复合）；两条腿共 2× 本值">
                  单腿往返成本：{{ bps(blk.cost_per_leg_round_trip) }}
                </span>
                <span v-if="blk.long_short_spread_cost_basis" title="本块两腿价差的成本基准：毛价差 / 净价差 / 不适用">
                  价差基准：{{ blk.long_short_spread_cost_basis }}
                </span>
                <span v-if="blk.rank_ic_ir_sample !== undefined" title="并列口径：分母用样本标准差 ÷(T−1)（主口径为总体标准差 ÷T）；无定义时为「—」">
                  ICIR（样本口径 ÷(T−1)）：{{ fmt4(blk.rank_ic_ir_sample) }}
                </span>
                <span title="名义 95% 水平，未做多重检验校正；ci_status = INSUFFICIENT_N 时为零宽区间，不可读作精确估计">HAC 95%CI：{{ fmt4(blk.ci_lower) }} ~ {{ fmt4(blk.ci_upper) }}</span>
                <span v-if="blk.pearson_support_days !== undefined" title="Pearson IC 可定义的截面日数（Spearman 与 Pearson 的有限性不等价）">
                  Pearson 支撑日：{{ blk.pearson_support_days }}
                </span>
                <span v-if="blk.input_cross_section_days !== undefined" title="入参截面日数 = 计入日 + 规模不足丢弃日 + IC 不可算丢弃日">
                  入参日数：{{ blk.input_cross_section_days }}（规模不足丢弃 {{ blk.days_dropped_below_min_cross_section ?? 0 }} / IC 不可算 {{ blk.days_dropped_non_finite_ic ?? 0 }} / 剔除观测 {{ blk.excluded_non_finite_observations ?? 0 }}）
                </span>
                <span v-if="blk.ci_status" title="区间状态：INSUFFICIENT_N = 截面日不足 2；ZERO_WIDTH = 零宽">CI 状态：{{ blk.ci_status }}</span>
                <span v-if="blk.price_basis" title="价格基准：legacy/snapshot 毛收益面的复权方式不同，跨面比较前必看">价格基准：{{ blk.price_basis }}</span>
              </div>
              <el-alert type="info" :closable="false" show-icon class="ic-note" :title="blk.basis_note" />
              <el-alert v-if="blk.status === 'UNAVAILABLE'" type="warning" :closable="false" show-icon class="ic-note" :title="blk.note || '口径不可用'" />
              <div v-if="blk.grouped_returns?.length" class="ic-section">
                <div class="ic-section-title">分组收益（按得分等数量分组）</div>
                <el-table :data="blk.grouped_returns" size="small" stripe border>
                  <el-table-column prop="group_no" label="组" width="60" />
                  <el-table-column prop="label" label="分组" min-width="120" />
                  <el-table-column label="平均收益" min-width="100">
                    <template #default="{ row }">{{ row.empty ? '空组（无观测）' : pct4(row.avg_return) }}</template>
                  </el-table-column>
                  <el-table-column label="截面日数" width="90">
                    <template #default="{ row }">
                      <span title="参与该组均值聚合的截面日数，不是成分股数">{{ row.observations }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
              <div v-if="blk.daily_rank_ic?.length" class="ic-section">
                <div class="ic-section-title">每日 Rank IC</div>
                <el-table :data="blk.daily_rank_ic" size="small" stripe border max-height="280">
                  <el-table-column prop="trade_date" label="信号日" min-width="110" />
                  <el-table-column label="Rank IC" min-width="100">
                    <template #default="{ row }">{{ fmt4(row.rank_ic) }}</template>
                  </el-table-column>
                  <el-table-column label="样本数" width="90">
                    <template #default="{ row }">
                      <span title="参与该日计算的有限观测数（非有限值已剔除）">{{ row.sample_size }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="剔除" width="70">
                    <template #default="{ row }">
                      <span title="该日因 score/forward return 非有限而被剔除的观测数">{{ row.excluded_observations ?? '—' }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
              <EmptyState
                v-if="!blk.grouped_returns?.length && !blk.daily_rank_ic?.length && blk.status !== 'UNAVAILABLE'"
                message="该口径无可用观测（forward 区间未形成 / raw 缺失 / 无可执行标签）"
              />
            </SectionCard>
          </div>
        </template>
        <EmptyState v-else-if="!snapshotLoading" message="输入 snapshotId 后研究完整 PIT 截面 IC（不读 selector_result / qfq）" />
      </div>

      <div v-loading="loading">
        <template v-if="result">
          <el-alert type="warning" :closable="false" show-icon class="note-alert" :title="result.note" />
          <el-alert
            v-if="result.universe_note || result.feature_snapshot_note"
            type="info"
            :closable="false"
            show-icon
            class="note-alert"
            :title="result.universe_note || result.feature_snapshot_note || ''"
          />
          <div v-if="result.funnel" class="q-meta funnel-meta">
            <span>漏斗 universe：{{ result.funnel.universe_count ?? '—' }}</span>
            <span>feature_valid：{{ result.funnel.feature_valid_count ?? '—' }}</span>
            <span>tradable：{{ result.funnel.tradable_status === 'UNAVAILABLE' ? '不可用' : (result.funnel.tradable_count ?? '—') }}</span>
            <span>metric_scopes：{{ (result.metric_scopes || []).join(' / ') || 'RAW / COST_APPROX' }}</span>
            <span>EXECUTABLE：{{ result.executable_metric_scope === 'UNAVAILABLE' ? '不可用' : (result.executable_metric_scope || '—') }}</span>
          </div>

          <div class="grid">
            <SectionCard class="q-card" pad="sm" title="覆盖率">
              <div class="q-meta">
                <span>截面天数：{{ result.cross_section_days }}</span>
                <span>总观测：{{ result.total_observations }}</span>
                <span>有效观测（得分>0）：{{ result.valid_observations }}</span>
                <span :class="coverageClass">{{ coverageText }}</span>
              </div>
            </SectionCard>

            <SectionCard class="q-card" pad="sm" title="得分截面分布">
              <div class="q-meta">
                <span>最小值：{{ fmt(result.distribution.min) }}</span>
                <span>P25：{{ fmt(result.distribution.p25) }}</span>
                <span>中位数：{{ fmt(result.distribution.median) }}</span>
                <span>P75：{{ fmt(result.distribution.p75) }}</span>
                <span>最大值：{{ fmt(result.distribution.max) }}</span>
              </div>
            </SectionCard>

            <SectionCard class="q-card" pad="sm" title="得分分组（等数量 5 组）">
              <el-table v-if="result.group_bands.length" :data="result.group_bands" size="small" stripe border>
                <el-table-column prop="group_no" label="组" width="60" />
                <el-table-column label="得分范围" min-width="140">
                  <template #default="{ row }">{{ fmt(row.min_score) }} ~ {{ fmt(row.max_score) }}</template>
                </el-table-column>
                <el-table-column label="均值" min-width="80">
                  <template #default="{ row }">{{ fmt(row.avg_score) }}</template>
                </el-table-column>
                <el-table-column prop="count" label="数量" width="70" />
              </el-table>
              <EmptyState v-else message="无有效得分" />
            </SectionCard>

            <SectionCard class="q-card" pad="sm" title="研究范围">
              <div class="q-meta">
                <span>策略：{{ strategyLabel(result.strategy) }}</span>
                <span>区间：{{ result.start_date }} ~ {{ result.end_date }}</span>
              </div>
            </SectionCard>

            <SectionCard class="q-card rank-ic-card" pad="sm" :title="`Rank IC 与分组收益（持仓 ${result.rank_ic.holding_days} 日）`">
              <div class="q-meta">
                <span>截面日数：{{ result.rank_ic.cross_section_days }}</span>
                <span title="参与 IC 计算的有限观测总数（已剔除非有限值）">截面日观测：{{ result.rank_ic.total_observations }}</span>
                <span title="信号日得分与 forward return 的秩相关均值">Rank IC 均值：{{ fmt4(result.rank_ic.rank_ic_mean) }}</span>
                <span>Rank IC 标准差：{{ fmt4(result.rank_ic.rank_ic_std) }}</span>
                <span title="IC 序列无离散度（含截面日不足 2）时无定义，显示「—」而不是 0">ICIR：{{ fmt4(result.rank_ic.rank_ic_ir) }}</span>
                <span>Rank IC 正率：{{ pct(result.rank_ic.rank_ic_positive_rate) }}</span>
                <span v-if="result.rank_ic.pearson_ic_mean !== undefined" title="线性 IC 均值；不可定义时（样本不足/任一侧无区分度）为 null，显示「—」而不是占位 0.0，须看 Pearson 支撑日数">
                  Pearson IC：{{ fmt4(result.rank_ic.pearson_ic_mean) }}
                </span>
                <span v-if="result.rank_ic.monotonicity">单调性：{{ monoLabel(result.rank_ic.monotonicity) }}</span>
                <span title="高分组均值 − 低分组均值（两个多头腿之差，非美元中性多空）">多空 spread（高−低）：{{ pct4(result.rank_ic.long_short_spread) }}</span>
                <span v-if="result.rank_ic.long_short_spread_neutral !== undefined" title="美元中性净价差 = 毛价差 − 2×单腿往返成本；不适用时显示「—」">
                  美元中性净 spread：{{ pct4(result.rank_ic.long_short_spread_neutral) }}
                </span>
                <span v-if="result.rank_ic.cost_per_leg_round_trip !== undefined" title="单腿往返成本（乘性复合口径）；两条腿共 2× 本值">
                  单腿往返成本：{{ bps(result.rank_ic.cost_per_leg_round_trip) }}
                </span>
                <span v-if="result.rank_ic.rank_ic_ir_sample !== undefined" title="并列口径：分母为样本标准差 ÷(T−1)（主口径为总体标准差 ÷T）">
                  ICIR（样本口径 ÷(T−1)）：{{ fmt4(result.rank_ic.rank_ic_ir_sample) }}
                </span>
                <span v-if="result.rank_ic.input_cross_section_days !== undefined" title="入参截面日数 = 计入日 + 规模不足丢弃日 + IC 不可算丢弃日">
                  入参日数：{{ result.rank_ic.input_cross_section_days }}（规模不足丢弃 {{ result.rank_ic.days_dropped_below_min_cross_section ?? 0 }} / IC 不可算 {{ result.rank_ic.days_dropped_non_finite_ic ?? 0 }} / 剔除观测 {{ result.rank_ic.excluded_non_finite_observations ?? 0 }}）
                </span>
                <span v-if="result.rank_ic.price_basis" title="价格基准：本面为 qfq 前复权 close；同名 metric_scope=RAW 在 snapshot 面是 rawClose 不复权，跨面比较前必看">
                  价格基准：{{ result.rank_ic.price_basis }}
                </span>
              </div>
              <el-alert type="info" :closable="false" show-icon class="ic-note" :title="result.rank_ic.basis_note" />
              <el-alert type="warning" :closable="false" show-icon class="ic-note" :title="result.rank_ic.universe_note" />
              <div v-if="result.rank_ic.grouped_returns.length" class="ic-section">
                <div class="ic-section-title">分组收益（按得分等数量分组，仅研究评价）</div>
                <el-table :data="result.rank_ic.grouped_returns" size="small" stripe border>
                  <el-table-column prop="group_no" label="组" width="60" />
                  <el-table-column prop="label" label="分组" min-width="120" />
                  <el-table-column label="平均收益" min-width="100">
                    <template #default="{ row }">{{ row.empty ? '空组（无观测）' : pct4(row.avg_return) }}</template>
                  </el-table-column>
                  <el-table-column label="截面日数" width="90">
                    <template #default="{ row }">
                      <span title="参与该组均值聚合的截面日数，不是成分股数">{{ row.observations }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
              <div v-if="result.rank_ic.daily_rank_ic.length" class="ic-section">
                <div class="ic-section-title">每日 Rank IC</div>
                <el-table :data="result.rank_ic.daily_rank_ic" size="small" stripe border max-height="320">
                  <el-table-column prop="trade_date" label="信号日" min-width="110" />
                  <el-table-column label="Rank IC" min-width="100">
                    <template #default="{ row }">{{ fmt4(row.rank_ic) }}</template>
                  </el-table-column>
                  <el-table-column label="样本数" width="90">
                    <template #default="{ row }">
                      <span title="参与该日计算的有限观测数（非有限值已剔除）">{{ row.sample_size }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="剔除" width="70">
                    <template #default="{ row }">
                      <span title="该日因 score/forward return 非有限而被剔除的观测数">{{ row.excluded_observations ?? '—' }}</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
              <EmptyState
                v-if="!result.rank_ic.grouped_returns.length && !result.rank_ic.daily_rank_ic.length"
                message="截面样本不足或无可用 forward 收益（信号日接近数据末端时跳过）"
              />
            </SectionCard>
          </div>
        </template>
        <EmptyState v-else-if="!loading" message="选择区间与策略后点击研究" />
      </div>

      <!-- 多周期 IC 对比（统一因子报告） -->
      <div v-loading="unifiedLoading" class="unified-block">
        <template v-if="unifiedResult">
          <div class="unified-toolbar">
            <el-button size="small" @click="exportUnifiedCsv">导出多周期 IC CSV</el-button>
          </div>
          <el-alert type="info" :closable="false" show-icon class="note-alert" :title="unifiedResult.note || ''" />
          <el-alert
            v-if="unifiedResult.universe_note || unifiedResult.feature_snapshot_note"
            type="warning"
            :closable="false"
            show-icon
            class="note-alert"
            :title="unifiedResult.universe_note || unifiedResult.feature_snapshot_note || ''"
          />
          <div v-if="unifiedResult.metric_scopes?.length" class="q-meta funnel-meta">
            <span>metric_scopes：{{ unifiedResult.metric_scopes.join(' / ') }}</span>
            <span>主口径：{{ unifiedResult.metric_scope || 'COST_APPROX' }}（成本后近似）</span>
            <span>EXECUTABLE：{{ unifiedResult.executable_metric_scope === 'UNAVAILABLE' ? '不可用' : (unifiedResult.executable_metric_scope || '—') }}</span>
          </div>
          <el-table :data="unifiedResult.by_horizon" size="small" stripe border max-height="420">
            <el-table-column prop="holding_days" label="持有期" width="70">
              <template #default="{ row }">T+{{ row.holding_days }}</template>
            </el-table-column>
            <el-table-column label="Rank IC" width="90">
              <template #default="{ row }">{{ fmt4(row.rank_ic_mean) }}</template>
            </el-table-column>
            <el-table-column label="Pearson IC" width="100">
              <template #default="{ row }">{{ fmt4(row.pearson_ic_mean) }}</template>
            </el-table-column>
            <el-table-column label="ICIR" width="90">
              <template #default="{ row }">
                <span title="IC 序列无离散度（含截面日不足 2）时无定义，显示「—」而不是 0">{{ fmt4(row.rank_ic_ir) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="正IC率" width="80">
              <template #default="{ row }">{{ pct(row.rank_ic_positive_rate) }}</template>
            </el-table-column>
            <el-table-column label="单调性" width="170">
              <template #default="{ row }">{{ monoLabel(row.monotonicity) }}</template>
            </el-table-column>
            <el-table-column label="多空 spread（高−低）" width="140">
              <template #default="{ row }">
                <span title="高分组均值 − 低分组均值（两个多头腿之差，非美元中性多空）">{{ pct(row.long_short_spread) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="美元中性净 spread" width="150">
              <template #default="{ row }">
                <span v-if="row.long_short_spread_neutral === undefined || row.long_short_spread_neutral === null"
                      title="本块收益口径不适用（如本身已是净价差）或成本不可算">—</span>
                <span v-else title="毛价差 − 2×单腿往返成本">{{ pct(row.long_short_spread_neutral) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="ICIR（÷(T−1)）" width="120">
              <template #default="{ row }">
                <span title="并列口径：样本标准差 ÷(T−1)；主口径 ICIR 用总体标准差 ÷T，两者比值恒为 sqrt(T/(T−1))">
                  {{ fmt4(row.rank_ic_ir_sample) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="净收益 IC (成本后近似)" width="160">
              <template #default="{ row }">
                <span v-if="!row.cost_net_ic || row.cost_net_ic.data_available === false" title="本口径无数据（缺 raw 行情/无可执行标签），0.0 是占位而不是「成本后恰为 0」">
                  不可用
                </span>
                <span v-else>{{ fmt4(row.cost_net_ic.rank_ic_mean) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="HAC 95%CI" min-width="150">
              <template #default="{ row }">
                <span title="名义 95% 水平，未做多重检验校正；INSUFFICIENT_N 时为零宽区间">{{ fmt4(row.ci_lower) }} ~ {{ fmt4(row.ci_upper) }}</span>
                <el-tag v-if="row.ci_status === 'INSUFFICIENT_N'" size="small" type="info" class="capability-tag">N&lt;2</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div class="unified-notes">
            <p v-if="unifiedResult.by_horizon[0]?.basis_note">毛收益口径：{{ unifiedResult.by_horizon[0].basis_note }}</p>
            <p v-if="unifiedResult.by_horizon[0]?.net_basis_note">净收益口径：{{ unifiedResult.by_horizon[0].net_basis_note }}</p>
            <p v-if="unifiedResult.by_horizon[0]?.embargo_note">Embargo：{{ unifiedResult.by_horizon[0].embargo_note }}</p>
          </div>
        </template>
      </div>
    </SectionCard>
  </PageShell>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  getFactorResearch,
  getUnifiedFactorReport,
  getSnapshotFactorResearch,
  type FactorResearchResponse,
  type UnifiedFactorReport,
  type SnapshotResearchResponse
} from '@/api/factor'
import { errorText } from '@/api/request'
import { useToast } from '@/composables/useToast'
import dayjs from 'dayjs'
import PageShell from '@/components/ui/PageShell.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'

const STRATEGY_LABELS: Record<string, string> = {
  CLASSIC_SELECTOR: '经典多因子',
  OVERSOLD_REBOUND: '超跌反弹',
  TREND: '趋势打分',
  COMPREHENSIVE: '综合三维度',
  MULTI_DIM: '多维度 v1'
}

const SNAPSHOT_STRATEGIES: Record<string, string> = {
  CLASSIC_SELECTOR: '经典多因子',
  OVERSOLD_REBOUND: '超跌反弹'
}

const toast = useToast()
const loading = ref(false)
const strategy = ref('')
const startDate = ref(dayjs().subtract(30, 'day').format('YYYY-MM-DD'))
const endDate = ref(dayjs().format('YYYY-MM-DD'))
const holdingDays = ref(5)
const result = ref<FactorResearchResponse | null>(null)
const unifiedLoading = ref(false)
const unifiedResult = ref<UnifiedFactorReport | null>(null)

// V2-MP-022：快照全截面 IC（权威路径）
const snapshotLoading = ref(false)
const snapStrategy = ref('CLASSIC_SELECTOR')
const snapSnapshotId = ref('')
const snapLabelVersion = ref('')
const snapStartDate = ref(dayjs().subtract(30, 'day').format('YYYY-MM-DD'))
const snapEndDate = ref(dayjs().format('YYYY-MM-DD'))
const snapHoldingDays = ref(5)
const snapshotResult = ref<SnapshotResearchResponse | null>(null)

function strategyLabel(s: string): string {
  if (s === 'ALL') return '全部策略'
  return STRATEGY_LABELS[s] || s
}

function fmt(v: number): string {
  return Number.isFinite(v) ? v.toFixed(2) : '—'
}

function fmt4(v: number | null | undefined): string {
  // null = 该量无定义（如 IC 序列无离散度时的 ICIR），显示「—」而不是 0
  return v !== null && v !== undefined && Number.isFinite(v) ? v.toFixed(4) : '—'
}

/**
 * 单调性标签的中文展示（含 NO_DISPERSION：各组收益全相等 = 零区分度，不是任何方向）
 */
function monoLabel(v: string | undefined): string {
  switch (v) {
    case 'MONOTONIC_INCREASING':
      return '单调递增'
    case 'MONOTONIC_DECREASING':
      return '单调递减'
    case 'NON_MONOTONIC':
      return '非单调'
    case 'NO_DISPERSION':
      return '零区分度（各组收益全相等）'
    case 'N/A':
      return '不可判定（可用组不足 2）'
    default:
      return v || '—'
  }
}

function pct(v: number | null | undefined): string {
  return v !== null && v !== undefined && Number.isFinite(v) ? `${(v * 100).toFixed(1)}%` : '—'
}

function pct4(v: number | null | undefined): string {
  return v !== null && v !== undefined && Number.isFinite(v) ? `${(v * 100).toFixed(2)}%` : '—'
}

/** 成本类比例按基点显示（1bp = 0.01%），null 显示「—」 */
function bps(v: number | null | undefined): string {
  return v !== null && v !== undefined && Number.isFinite(v) ? `${(v * 10000).toFixed(2)}bp` : '—'
}

const coverageText = computed(() => {
  if (!result.value) return ''
  return `覆盖率：${result.value.coverage}%`
})

const coverageClass = computed(() => {
  if (!result.value) return ''
  const c = result.value.coverage
  if (c >= 80) return 'text-success'
  if (c >= 50) return 'text-warning'
  return 'text-danger'
})

async function run() {
  if (!startDate.value || !endDate.value) {
    toast.warning('请选择起止日期')
    return
  }
  if (startDate.value > endDate.value) {
    toast.warning('起始日不能晚于截止日')
    return
  }
  loading.value = true
  try {
    result.value = await getFactorResearch({
      strategy: strategy.value || undefined,
      startDate: startDate.value,
      endDate: endDate.value,
      holdingDays: holdingDays.value
    })
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function runUnified() {
  if (!startDate.value || !endDate.value) {
    toast.warning('请选择起止日期')
    return
  }
  if (startDate.value > endDate.value) {
    toast.warning('起始日不能晚于截止日')
    return
  }
  unifiedLoading.value = true
  try {
    unifiedResult.value = await getUnifiedFactorReport({
      strategy: strategy.value || undefined,
      startDate: startDate.value,
      endDate: endDate.value
    })
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    unifiedLoading.value = false
  }
}

async function runSnapshot() {
  const sid = snapSnapshotId.value.trim()
  if (!sid) {
    toast.warning('请填写 snapshotId（MP-016 数据快照）')
    return
  }
  if (!snapStartDate.value || !snapEndDate.value) {
    toast.warning('请选择起止日期')
    return
  }
  if (snapStartDate.value > snapEndDate.value) {
    toast.warning('起始日不能晚于截止日')
    return
  }
  snapshotLoading.value = true
  try {
    snapshotResult.value = await getSnapshotFactorResearch({
      strategy: snapStrategy.value,
      snapshotId: sid,
      startDate: snapStartDate.value,
      endDate: snapEndDate.value,
      holdingDays: snapHoldingDays.value,
      labelVersion: snapLabelVersion.value.trim() || undefined
    })
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    snapshotLoading.value = false
  }
}

function exportUnifiedCsv() {
  if (!unifiedResult.value || !unifiedResult.value.by_horizon.length) return
  const r = unifiedResult.value
  // 表头须自证口径：前三列是毛收益（gross_*）、column `net_ic` 实为成本后近似净收益 Rank IC
  // 且与毛收益窗口差一日，故带 full 名与 gross_/cost_net_ 前缀；ICIR 无定义时留空而不是 0
  const lines: string[] = [
    `strategy,${r.strategy}`,
    `range,${r.start_date} ~ ${r.end_date}`,
    'metric_scope,RAW(gross) unless prefixed cost_net_,COST_APPROX',
    'icir_undefined_when,,IC 序列无离散度（std=0，含截面日 < 2）',
    ['holding_days', 'gross_rank_ic_mean', 'gross_pearson_ic_mean', 'gross_rank_ic_ir',
      'gross_rank_ic_positive_rate', 'monotonicity', 'gross_long_short_spread',
      'gross_long_short_spread_neutral', 'gross_rank_ic_ir_sample',
      'cost_net_rank_ic_mean', 'gross_hac_std', 'gross_ci_lower', 'gross_ci_upper',
      'ci_status', 'cross_section_days', 'input_cross_section_days', 'total_observations',
      'pearson_support_days'].join(','),
    ...r.by_horizon.map((row) => [
      row.holding_days, row.rank_ic_mean, row.pearson_ic_mean ?? '',
      row.rank_ic_ir ?? '', row.rank_ic_positive_rate, row.monotonicity, row.long_short_spread,
      row.long_short_spread_neutral ?? '', row.rank_ic_ir_sample ?? '',
      row.cost_net_ic?.data_available === false ? '' : (row.cost_net_ic?.rank_ic_mean ?? ''),
      row.hac_std, row.ci_lower, row.ci_upper,
      row.ci_status ?? '', row.cross_section_days, row.input_cross_section_days ?? '',
      row.total_observations, row.pearson_support_days ?? ''
    ].join(','))
  ]
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `unified-factor-ic-${r.start_date}_${r.end_date}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(() => run())
</script>

<style scoped>
.capability-tag { margin-left: 8px; vertical-align: middle; }
.form {
  margin-bottom: 8px;
}
.note-alert {
  margin-bottom: 16px;
}
.funnel-meta {
  margin-bottom: 12px;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 12px;
}
.unified-block {
  margin-top: 16px;
}
.unified-toolbar {
  margin-bottom: 8px;
}
.unified-notes {
  margin-top: 10px;
  font-size: 12px;
  color: var(--text-dim);
  line-height: 1.6;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 16px;
}
.q-card {
  background: var(--surface-2);
  border: 1px solid var(--hairline-strong);
}
.q-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--text-secondary);
}
.text-danger { color: var(--up); }
.text-success { color: var(--down); }
.text-warning { color: var(--warning); }
.rank-ic-card {
  grid-column: 1 / -1;
}
.ic-note {
  margin-top: 8px;
}
.ic-section {
  margin-top: 12px;
}
.ic-section-title {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
}
</style>