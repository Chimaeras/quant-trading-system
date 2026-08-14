<template>
  <el-card shadow="hover" class="terminal-page">
    <template #header>
      <div class="header-row">
        <span>候选前瞻收益（非组合回测）</span>
      </div>
    </template>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="disclaimer-alert"
      title="候选前瞻收益说明（非组合回测）"
      description="本页统计各候选信号的前瞻收益样本，不含现金/仓位/重叠持仓的组合状态机。组合级回测请使用 POST /api/backtest/portfolio/run。基于历史选股结果与行情，未按历史时点股票池完整复现，存在幸存者偏差风险；不代表未来表现或投资建议。"
    />

    <!-- 参数配置 -->
    <div class="section-title">回测参数配置</div>
    <el-form inline>
      <el-form-item label="策略类型">
        <el-select v-model="form.strategyType" placeholder="全部策略" style="width: 200px" :loading="strategiesLoading" clearable>
          <el-option label="全部策略" value="" />
          <el-option v-for="s in strategies" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
      <el-form-item label="起始日期">
        <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="结束日期">
        <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="每日选取TopN">
        <el-input-number v-model="form.topN" :min="1" :max="100" :step="1" />
      </el-form-item>
      <el-form-item label="因子分组数">
        <el-input-number v-model="form.factorGroupCount" :min="2" :max="10" :step="1" />
      </el-form-item>
    </el-form>
    <el-form inline class="holding-form">
      <el-form-item label="持仓周期(交易日)">
        <el-checkbox-group v-model="form.holdingDays">
          <el-checkbox :value="1">T+1</el-checkbox>
          <el-checkbox :value="3">T+3</el-checkbox>
          <el-checkbox :value="5">T+5</el-checkbox>
          <el-checkbox :value="10">T+10</el-checkbox>
          <el-checkbox :value="20">T+20</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runBacktest">执行回测</el-button>
      </el-form-item>
      <el-form-item label="历史回测">
        <el-select v-model="historyResultId" placeholder="按 result_id 回溯历史回测" clearable filterable style="width: 300px" @change="loadRun">
          <el-option v-for="r in runs" :key="r.result_id" :label="`${r.strategy_type} ${r.start_date}~${r.end_date} (${r.result_id})`" :value="r.result_id" />
        </el-select>
        <el-button link type="primary" :loading="runsLoading" @click="loadRuns">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="!response"
      type="info"
      :closable="false"
      show-icon
      class="run-alert"
      title="收益按信号后下一交易日开盘买入、持有期末收盘卖出计算，并扣除双边佣金、卖出税费和双边滑点。"
    />

    <el-alert v-if="errorMsg" type="error" show-icon :closable="true" class="run-alert" @close="errorMsg = ''">
      {{ errorMsg }}
    </el-alert>

    <!-- 回测结果 -->
    <template v-if="response">
      <div class="result-meta">
        <span>策略：{{ response.strategyType || '全部' }}</span>
        <span>日期：{{ response.startDate }} ~ {{ response.endDate }}</span>
        <span>覆盖交易日：{{ response.totalTradingDays }} 天</span>
        <span>每日前 {{ response.topN }} 只</span>
        <span v-if="response.resultId" class="result-id-chip">结果ID：{{ response.resultId }}</span>
      </div>
      <el-alert
        v-if="response.resultId"
        type="warning"
        :closable="false"
        show-icon
        class="run-alert"
        title="该结果已按当时的数据口径与假设持久化；若之后行情更新，重跑可能得到不同结果，以本结果ID记录为准。"
      />

      <el-tabs v-model="activeTab" class="result-tabs" @tab-change="handleTabChange">
        <!-- ============ Tab 1：回测概览 ============ -->
        <el-tab-pane label="回测概览" name="overview">
          <div class="section-hint">每个持仓周期下，选出的 Top 股票整体表现如何。</div>

          <div class="verdict-grid">
            <div v-for="v in periodVerdicts" :key="v.day" class="verdict-card glass">
              <div class="verdict-head">
                <span class="period-tag">持有 {{ v.day }} 个交易日</span>
                <el-tag :type="v.verdictType" size="small" effect="dark">{{ v.verdictLabel }}</el-tag>
              </div>
              <p class="verdict-text">{{ v.conclusion }}</p>
              <div class="verdict-metrics">
                <div class="vm">
                  <span class="vm-label">历史样本胜率</span>
                  <b :class="v.winRate >= 0.5 ? 'text-success' : 'text-danger'">{{ pct1(v.winRate) }}</b>
                </div>
                <div class="vm">
                  <span class="vm-label">平均收益</span>
                  <b :class="v.avgReturn >= 0 ? 'text-success' : 'text-danger'">{{ pct(v.avgReturn) }}</b>
                </div>
                <div class="vm">
                  <span class="vm-label">盈亏比</span>
                  <b :class="v.profitLossRatio >= 1 ? 'text-success' : 'text-danger'">{{ v.profitLossRatio.toFixed(2) }}</b>
                </div>
                <div class="vm">
                  <span class="vm-label">样本数</span>
                  <b>{{ v.totalObservations }}</b>
                </div>
              </div>
            </div>
          </div>

          <el-collapse class="assumptions-collapse">
            <el-collapse-item title="成交与费用假设" name="assumptions">
              <div class="assumptions-clean">
                <div v-for="row in businessAssumptions" :key="row.key" class="assumption-row">
                  <span class="assumption-label">{{ row.label }}</span>
                  <span class="assumption-value">{{ row.value }}</span>
                </div>
              </div>
              <el-collapse class="tech-collapse">
                <el-collapse-item title="技术细节" name="tech">
                  <div class="assumptions-clean">
                    <div v-for="row in technicalAssumptions" :key="row.key" class="assumption-row">
                      <span class="assumption-label">{{ row.label }}</span>
                      <span class="assumption-value">{{ row.value }}</span>
                    </div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </el-collapse-item>
          </el-collapse>
        </el-tab-pane>

        <!-- ============ Tab 2：持仓周期表现 ============ -->
        <el-tab-pane label="持仓周期表现" name="performance">
          <div class="section-hint">各持仓周期的详细收益分布与盈亏统计。</div>
          <div class="stats-grid">
            <div v-for="(stat, day) in response.holdingPeriodStats" :key="day" class="period-card glass">
              <div class="stat-header">持有 {{ day }} 个交易日</div>
              <div class="stat-body">
                <div class="stat-item">
                  <span class="stat-label">历史样本胜率</span>
                  <span class="stat-value" :class="stat.winRate >= 0.5 ? 'text-success' : 'text-danger'">
                    {{ (stat.winRate * 100).toFixed(1) }}%
                  </span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">平均收益</span>
                  <span class="stat-value" :class="stat.avgReturn >= 0 ? 'text-success' : 'text-danger'">
                    {{ (stat.avgReturn * 100).toFixed(2) }}%
                  </span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">中位数收益</span>
                  <span class="stat-value" :class="stat.medianReturn >= 0 ? 'text-success' : 'text-danger'">
                    {{ (stat.medianReturn * 100).toFixed(2) }}%
                  </span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">平均赚</span>
                  <span class="stat-value text-success">{{ (stat.avgWin * 100).toFixed(2) }}%</span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">平均亏</span>
                  <span class="stat-value text-danger">{{ (stat.avgLoss * 100).toFixed(2) }}%</span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">盈亏比（赚/亏）</span>
                  <span class="stat-value" :class="stat.profitLossRatio >= 1 ? 'text-success' : 'text-danger'">
                    {{ stat.profitLossRatio.toFixed(2) }}
                  </span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">最大收益 / 最大亏损</span>
                  <span class="stat-value">
                    <span class="text-success">{{ (stat.maxReturn * 100).toFixed(2) }}%</span>
                    <span class="text-muted"> / </span>
                    <span class="text-danger">{{ (stat.minReturn * 100).toFixed(2) }}%</span>
                  </span>
                </div>
                <div class="stat-item">
                  <span class="stat-label">样本数</span>
                  <span class="stat-value">{{ stat.totalObservations }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="section-title">各周期历史样本胜率与平均收益对比</div>
          <div ref="chartRef" class="chart-box"></div>
        </el-tab-pane>

        <!-- ============ Tab 3：因子有效性 ============ -->
        <el-tab-pane label="因子有效性" name="factor">
          <template v-if="response.factorAnalysis || response.shortHorizonValidation || response.formulaAudit">
            <div class="section-hint">
              “因子有效性”回答：得分高的股票，未来是否真的涨得更好？以下指标衡量选股排序的预测能力。
            </div>

            <!-- 因子有效性解读卡 -->
            <template v-if="response.factorAnalysis">
              <div class="section-title">选股排序预测力（持有 {{ response.factorAnalysis.holdingDays }} 个交易日）</div>
              <div class="stats-grid factor-ic-grid">
                <div class="period-card glass">
                  <div class="stat-header">选股排序准确度</div>
                  <div class="stat-body">
                    <div class="stat-item">
                      <span class="stat-label">准确度（Rank IC）</span>
                      <span class="stat-value" :class="response.factorAnalysis.rankIcMean >= 0 ? 'text-success' : 'text-danger'">
                        {{ response.factorAnalysis.rankIcMean.toFixed(4) }}
                      </span>
                    </div>
                    <div class="stat-item">
                      <span class="stat-label">业务判定</span>
                      <el-tag :type="icVerdict(response.factorAnalysis.rankIcMean).type" size="small">
                        {{ icVerdict(response.factorAnalysis.rankIcMean).text }}
                      </el-tag>
                    </div>
                    <div class="stat-item">
                      <span class="stat-label">预测有效天数占比</span>
                      <span class="stat-value">{{ (response.factorAnalysis.rankIcPositiveRate * 100).toFixed(1) }}%</span>
                    </div>
                  </div>
                </div>
                <div class="period-card glass">
                  <div class="stat-header">预测稳定性</div>
                  <div class="stat-body">
                    <div class="stat-item">
                      <span class="stat-label">稳定性（ICIR）</span>
                      <span class="stat-value">{{ response.factorAnalysis.rankIcIr.toFixed(3) }}</span>
                    </div>
                    <div class="stat-item">
                      <span class="stat-label">业务判定</span>
                      <el-tag :type="icirVerdict(response.factorAnalysis.rankIcIr).type" size="small">
                        {{ icirVerdict(response.factorAnalysis.rankIcIr).text }}
                      </el-tag>
                    </div>
                    <div class="stat-item">
                      <span class="stat-label">波动幅度</span>
                      <span class="stat-value">{{ response.factorAnalysis.rankIcStd.toFixed(4) }}</span>
                    </div>
                  </div>
                </div>
                <div class="period-card glass">
                  <div class="stat-header">高分 vs 低分收益差</div>
                  <div class="stat-body">
                    <div class="stat-item">
                      <span class="stat-label">多空收益差</span>
                      <span class="stat-value" :class="response.factorAnalysis.longShortSpread >= 0 ? 'text-success' : 'text-danger'">
                        {{ (response.factorAnalysis.longShortSpread * 100).toFixed(2) }}%
                      </span>
                    </div>
                    <div class="stat-item">
                      <span class="stat-label">业务含义</span>
                      <span class="stat-value small-text">
                        {{ response.factorAnalysis.longShortSpread >= 0 ? '高分组合收益高于低分组合，选股有效' : '高分组合反而跑输低分组合，选股方向需警惕' }}
                      </span>
                    </div>
                    <div class="stat-item">
                      <span class="stat-label">样本覆盖</span>
                      <span class="stat-value">{{ response.factorAnalysis.crossSectionDays }} 个截面日 / {{ response.factorAnalysis.totalObservations }} 个观测</span>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <!-- 短周期验证（业务化） -->
            <el-alert
              v-if="response.shortHorizonValidation"
              :type="shortHorizonValidationType"
              :closable="false"
              show-icon
              class="run-alert"
              title="短周期预测力验证结论"
            >
              <div class="formula-audit">
                <p><strong>结论：</strong>{{ response.shortHorizonValidation.verdict }}</p>
                <p>置信度：{{ response.shortHorizonValidation.confidence }}</p>
                <p>
                  T+3 选股准确度：{{ formatIcMean(response.shortHorizonValidation.t3_rank_ic_mean) }}
                  <el-tag v-if="response.shortHorizonValidation.t3_rank_ic_mean != null" :type="icVerdict(Number(response.shortHorizonValidation.t3_rank_ic_mean)).type" size="small" style="margin-left:6px">
                    {{ icVerdict(Number(response.shortHorizonValidation.t3_rank_ic_mean)).text }}
                  </el-tag>
                </p>
                <p>
                  T+5 选股准确度：{{ formatIcMean(response.shortHorizonValidation.t5_rank_ic_mean) }}
                  <el-tag v-if="response.shortHorizonValidation.t5_rank_ic_mean != null" :type="icVerdict(Number(response.shortHorizonValidation.t5_rank_ic_mean)).type" size="small" style="margin-left:6px">
                    {{ icVerdict(Number(response.shortHorizonValidation.t5_rank_ic_mean)).text }}
                  </el-tag>
                </p>
                <p v-if="response.shortHorizonValidation.codex_t_dominates === true" class="text-success">
                  趋势因子（T）是短周期预测力的主要来源
                </p>
                <p v-else-if="response.shortHorizonValidation.codex_t_dominates === false && response.shortHorizonValidation.codex_sub_factors_available">
                  趋势因子（T）预测力不足，建议结合 F（财务）/ R（风险）因子综合判断
                </p>
                <p class="text-muted disclaimer-inline">{{ response.shortHorizonValidation.disclaimer }}</p>
              </div>
            </el-alert>

            <!-- 公式审计 -->
            <el-alert
              v-if="response.formulaAudit"
              :type="formulaAuditAlertType"
              :closable="false"
              show-icon
              class="run-alert"
              title="策略公式适配性"
            >
              <div class="formula-audit">
                <p><strong>公式：</strong>{{ response.formulaAudit.final_score_formula }}</p>
                <p><strong>结论：</strong>{{ response.formulaAudit.short_horizon_verdict }}</p>
                <p>短周期适配分：{{ response.formulaAudit.short_horizon_fit_score }}/100</p>
                <ul v-if="formulaAuditTips.length" class="formula-tips">
                  <li v-for="(tip, idx) in formulaAuditTips" :key="idx">{{ tip }}</li>
                </ul>
              </div>
            </el-alert>

            <!-- Codex 子因子表 -->
            <div v-if="codexFactorTableRows.length" class="section-title">各子因子预测力（T=趋势 / F=财务 / R=风险）</div>
            <el-table v-if="codexFactorTableRows.length" :data="codexFactorTableRows" stripe size="small" class="data-table">
              <el-table-column prop="factor" label="因子" width="80">
                <template #default="{ row }">{{ codexFactorLabel(row.factor) }}</template>
              </el-table-column>
              <el-table-column label="T+3 准确度" min-width="120">
                <template #default="{ row }">
                  {{ row.h3 }} <el-tag v-if="row.h3 !== '-'" :type="icVerdict(Number(row.h3)).type" size="small" style="margin-left:4px">{{ icVerdict(Number(row.h3)).text }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="T+5 准确度" min-width="120">
                <template #default="{ row }">
                  {{ row.h5 }} <el-tag v-if="row.h5 !== '-'" :type="icVerdict(Number(row.h5)).type" size="small" style="margin-left:4px">{{ icVerdict(Number(row.h5)).text }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="T+10 准确度" min-width="120">
                <template #default="{ row }">
                  {{ row.h10 }} <el-tag v-if="row.h10 !== '-'" :type="icVerdict(Number(row.h10)).type" size="small" style="margin-left:4px">{{ icVerdict(Number(row.h10)).text }}</el-tag>
                </template>
              </el-table-column>
            </el-table>

            <!-- 分组收益 -->
            <template v-if="response.factorAnalysis">
              <div class="section-title">按得分分组的表现（验证单调性：分数越高是否收益越高）</div>
              <el-alert
                type="warning"
                :closable="false"
                show-icon
                class="run-alert"
                :title="response.factorAnalysis.universeNote"
              />
              <div ref="factorChartRef" class="chart-box"></div>
              <el-table :data="response.factorAnalysis.groupedReturns" stripe size="small" class="data-table" max-height="260">
                <el-table-column prop="label" label="分组" width="120" />
                <el-table-column label="平均收益" width="120">
                  <template #default="{ row }">
                    <span :class="row.avgReturn >= 0 ? 'text-success' : 'text-danger'">
                      {{ (row.avgReturn * 100).toFixed(2) }}%
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="observations" label="截面日观测" />
              </el-table>

              <template v-if="icByHoldingRows.length">
                <div class="section-title">不同持有期的预测力对比</div>
                <div ref="icByHoldingChartRef" class="chart-box"></div>
                <el-table :data="icByHoldingRows" stripe size="small" class="data-table" max-height="220">
                  <el-table-column prop="label" label="持有期" width="90" />
                  <el-table-column label="选股准确度" width="110">
                    <template #default="{ row }">
                      <span :class="row.rankIcMean >= 0 ? 'text-success' : 'text-danger'">{{ row.rankIcMean.toFixed(4) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="稳定性" width="90">
                    <template #default="{ row }">{{ row.rankIcIr.toFixed(3) }}</template>
                  </el-table-column>
                  <el-table-column label="高分减低分收益差" width="130">
                    <template #default="{ row }">
                      <span :class="row.longShortSpread >= 0 ? 'text-success' : 'text-danger'">
                        {{ (row.longShortSpread * 100).toFixed(2) }}%
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="crossSectionDays" label="截面日" width="80" />
                </el-table>
              </template>

              <template v-if="response.factorAnalysis.dailyRankIc?.length">
                <div class="section-title">每日选股准确度变化趋势</div>
                <div ref="icSeriesChartRef" class="chart-box"></div>
              </template>
            </template>
          </template>
          <el-empty v-else description="本次回测无因子有效性数据" />
        </el-tab-pane>

        <!-- ============ Tab 4：每日明细 ============ -->
        <el-tab-pane label="每日明细" name="daily">
          <div class="section-hint">逐日查看选出的股票及其实际收益，点击「详情」查看个股明细。</div>
          <div class="section-title">
            每日回测明细
            <el-button size="small" style="margin-left: 12px" @click="exportCsv">导出 CSV</el-button>
          </div>
          <el-table :data="dailyPage" stripe size="small" class="data-table" max-height="500">
            <el-table-column prop="tradeDate" label="交易日期" width="120" sortable />
            <el-table-column v-for="day in form.holdingDays" :key="day" :label="'持有' + day + '日历史样本胜率'" width="130">
              <template #default="{ row }">
                <span v-if="row.dailyWinRate[day] != null" :class="row.dailyWinRate[day] >= 0.5 ? 'text-success' : 'text-danger'">
                  {{ (row.dailyWinRate[day] * 100).toFixed(1) }}%
                </span>
                <span v-else class="text-muted">-</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button link type="primary" @click="showDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pagination-box" v-if="dailyPageTotal > pageSize">
            <el-pagination
              v-model:current-page="dailyPageNum"
              :page-size="pageSize"
              :total="dailyPageTotal"
              layout="prev, pager, next"
              background
              small
            />
          </div>
        </el-tab-pane>
      </el-tabs>

      <!-- 详情弹窗 -->
      <el-dialog v-model="detailVisible" :title="'回测明细 - ' + detailDate" width="900px" destroy-on-close>
        <el-table :data="detailStocks" stripe size="small" max-height="400">
          <el-table-column prop="rankNo" label="排名" width="60" />
          <el-table-column prop="stockCode" label="代码" width="100" />
          <el-table-column prop="stockName" label="名称" width="100" />
          <el-table-column prop="finalScore" label="得分" width="80" sortable />
          <el-table-column prop="skipReason" label="跳过原因" min-width="140">
            <template #default="{ row }">
              <span v-if="row.skipReason">{{ formatSkipReason(row.skipReason) }}</span>
              <span v-else class="text-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column v-for="day in form.holdingDays" :key="day" :label="'持有' + day + '日收益'" width="120">
            <template #default="{ row: stock }">
              <span v-if="stock.forwardReturns[day] != null" :class="stock.forwardReturns[day] >= 0 ? 'text-success' : 'text-danger'">
                {{ (stock.forwardReturns[day] * 100).toFixed(2) }}%
              </span>
              <span v-else-if="stock.periodSkipReasons?.[day]" class="text-muted skip-hint">
                {{ formatSkipReason(stock.periodSkipReasons[day]) }}
              </span>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
        </el-table>
      </el-dialog>

      <el-collapse class="turnover-collapse">
        <el-collapse-item title="候选换手率分析" name="turnover">
          <el-button type="primary" size="small" :loading="turnoverLoading" @click="runTurnover">分析换手率</el-button>
          <template v-if="turnover">
            <div class="turnover-meta">
              <span>信号日：{{ turnover.signal_days }}</span>
              <span>平均换手：{{ (turnover.avg_turnover * 100).toFixed(2) }}%</span>
              <span>最高：{{ (turnover.max_turnover * 100).toFixed(2) }}%</span>
              <span>最低：{{ (turnover.min_turnover * 100).toFixed(2) }}%</span>
            </div>
            <el-table v-if="turnover.daily_turnover.length" :data="turnover.daily_turnover" size="small" stripe border max-height="320">
              <el-table-column prop="trade_date" label="信号日" min-width="110" />
              <el-table-column label="换手率" min-width="100">
                <template #default="{ row }">{{ (row.turnover * 100).toFixed(2) }}%</template>
              </el-table-column>
              <el-table-column prop="new_count" label="新进数" min-width="80" />
              <el-table-column prop="exit_count" label="退出数" min-width="80" />
            </el-table>
            <p class="turnover-note">{{ turnover.note }}</p>
          </template>
        </el-collapse-item>
        <el-collapse-item title="分段稳定性报告" name="stability">
          <el-button type="primary" size="small" :loading="stabilityLoading" @click="runStability">分析稳定性</el-button>
          <template v-if="stability">
            <el-alert type="info" :closable="false" show-icon class="stability-verdict" :title="stability.verdict" />
            <p class="turnover-meta">切分日：{{ stability.split_date }} · 持有期 T+{{ stability.holding_day }}</p>
            <el-table :data="[stability.first_half, stability.second_half, stability.full_period]" size="small" stripe border>
              <el-table-column prop="label" label="分段" min-width="80" />
              <el-table-column prop="signal_days" label="信号日" width="80" />
              <el-table-column prop="observations" label="观测数" width="80" />
              <el-table-column label="平均收益" min-width="100">
                <template #default="{ row }">{{ (row.avg_return * 100).toFixed(2) }}%</template>
              </el-table-column>
              <el-table-column label="历史样本胜率" min-width="90">
                <template #default="{ row }">{{ (row.win_rate * 100).toFixed(2) }}%</template>
              </el-table-column>
              <el-table-column label="盈亏比" min-width="90">
                <template #default="{ row }">{{ row.profit_loss_ratio.toFixed(2) }}</template>
              </el-table-column>
            </el-table>
            <!-- 基准对照 + 牛/熊市场分段 -->
            <template v-if="stability.benchmark?.available">
              <el-divider content-position="left">vs {{ stability.benchmark.index_name }}（{{ stability.benchmark.index_code }}）</el-divider>
              <div class="benchmark-row">
                <span>策略平均收益：<b :class="(stability.benchmark.strategy_avg_return ?? 0) >= 0 ? 'text-success' : 'text-danger'">{{ pct(stability.benchmark.strategy_avg_return ?? 0) }}</b></span>
                <span>指数收益：<b :class="(stability.benchmark.index_return ?? 0) >= 0 ? 'text-success' : 'text-danger'">{{ pct(stability.benchmark.index_return ?? 0) }}</b></span>
                <span v-if="stability.benchmark.outperform != null">
                  <el-tag :type="stability.benchmark.outperform ? 'success' : 'warning'" size="small">{{ stability.benchmark.outperform ? '跑赢基准' : '未跑赢基准' }}</el-tag>
                </span>
              </div>
              <el-table :data="stability.market_segments ? [stability.market_segments.bull, stability.market_segments.bear] : []" size="small" stripe border style="margin-top: 8px">
                <el-table-column label="市场状态" min-width="90">
                  <template #default="{ row }">{{ row === stability.market_segments?.bull ? '牛市（指数上涨日）' : '熊市（指数下跌日）' }}</template>
                </el-table-column>
                <el-table-column label="信号日数" width="90">
                  <template #default="{ row }">{{ row.days }}</template>
                </el-table-column>
                <el-table-column label="平均胜率" min-width="100">
                  <template #default="{ row }">{{ (row.avg_win_rate * 100).toFixed(2) }}%</template>
                </el-table-column>
                <el-table-column label="指数累计收益" min-width="120">
                  <template #default="{ row }">{{ (row.index_return * 100).toFixed(2) }}%</template>
                </el-table-column>
              </el-table>
            </template>
            <el-alert v-else-if="stability.benchmark && !stability.benchmark.available" type="info" :closable="false" show-icon :title="stability.benchmark.note || ''" />
            <p class="turnover-note">{{ stability.note }}</p>
          </template>
        </el-collapse-item>
      </el-collapse>
    </template>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { echarts, CHART_THEME, CHART_UP, CHART_DOWN, CHART_C1, CHART_C2 } from '@/utils/echartsTheme'
import dayjs from 'dayjs'
import { backtestApi, analyzeTurnover, analyzeStability, type ScoringBacktestResponse, type DailyDetail, type StockReturn, type TurnoverResponse, type StabilityResponse } from '@/api/backtest'
import { errorText } from '@/api/request'
import { useToast } from '@/composables/useToast'

const toast = useToast()
const running = ref(false)
const errorMsg = ref('')
const strategies = ref<string[]>([])
const strategiesLoading = ref(false)
const runs = ref<Array<{ result_id: string; strategy_type: string; start_date: string; end_date: string; created_at?: string | null }>>([])
const runsLoading = ref(false)
const historyResultId = ref('')
const response = ref<ScoringBacktestResponse | null>(null)
const turnover = ref<TurnoverResponse | null>(null)
const turnoverLoading = ref(false)
const stability = ref<StabilityResponse | null>(null)
const stabilityLoading = ref(false)
const activeTab = ref('overview')

const form = reactive({
  strategyType: '',
  startDate: dayjs().subtract(6, 'month').format('YYYY-MM-DD'),
  endDate: dayjs().format('YYYY-MM-DD'),
  topN: 10,
  holdingDays: [3, 5, 10, 20],
  factorGroupCount: 5
})

// ===== 业务化格式化 =====
function pct(v: number) { return Number.isFinite(v) ? (v * 100).toFixed(2) + '%' : '—' }
function pct1(v: number) { return Number.isFinite(v) ? (v * 100).toFixed(1) + '%' : '—' }

// IC 业务判定：<0.02 几乎无预测力 / <0.05 较弱 / <0.1 有效 / >=0.1 强
function icVerdict(ic: number): { text: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  if (!Number.isFinite(ic)) return { text: '数据不足', type: 'info' }
  const a = Math.abs(ic)
  if (a < 0.02) return { text: '几乎无预测力', type: 'danger' }
  if (a < 0.05) return { text: '预测力较弱', type: 'warning' }
  if (a < 0.1) return { text: '预测有效', type: 'success' }
  return { text: '预测力强', type: 'success' }
}

// ICIR 业务判定：<0.3 不稳定 / <0.5 一般 / >=0.5 较稳定
function icirVerdict(icir: number): { text: string; type: 'success' | 'warning' | 'danger' | 'info' } {
  if (!Number.isFinite(icir)) return { text: '数据不足', type: 'info' }
  if (icir < 0.3) return { text: '不稳定', type: 'danger' }
  if (icir < 0.5) return { text: '一般', type: 'warning' }
  return { text: '较稳定', type: 'success' }
}

function codexFactorLabel(f: string): string {
  return f === 'T' ? 'T 趋势' : f === 'F' ? 'F 财务' : f === 'R' ? 'R 风险' : f
}

// ===== 各持仓周期业务结论 =====
const periodVerdicts = computed(() => {
  const stats = response.value?.holdingPeriodStats
  if (!stats) return []
  return Object.keys(stats).map(Number).sort((a, b) => a - b).map((d) => {
    const s = stats[d]
    const wr = s.winRate
    const ar = s.avgReturn
    const plr = s.profitLossRatio
    let verdictLabel = '表现一般'
    let verdictType: 'success' | 'warning' | 'danger' | 'info' = 'info'
    let conclusion = ''
    if (ar > 0 && wr >= 0.5 && plr >= 1) {
      verdictLabel = '表现较好'
      verdictType = 'success'
      conclusion = `持有 ${d} 个交易日后，选出的股票整体盈利：平均赚 ${pct(ar)}，${(wr * 100).toFixed(0)}% 的股票上涨，盈亏比 ${plr.toFixed(2)}（赚的比亏的多）。`
    } else if (ar <= 0) {
      verdictLabel = '表现不佳'
      verdictType = 'danger'
      conclusion = `持有 ${d} 个交易日后，选出的股票整体亏损：平均 ${pct(ar)}，仅 ${(wr * 100).toFixed(0)}% 上涨，盈亏比 ${plr.toFixed(2)}。`
    } else {
      verdictLabel = '表现一般'
      verdictType = 'warning'
      conclusion = `持有 ${d} 个交易日后平均收益 ${pct(ar)}，历史样本胜率 ${(wr * 100).toFixed(0)}%，盈亏比 ${plr.toFixed(2)}，表现中规中矩。`
    }
    return { day: d, ...s, verdictLabel, verdictType, conclusion }
  })
})

const icByHoldingRows = computed(() => {
  const map = response.value?.factorIcByHolding
  if (!map) return []
  return Object.keys(map)
    .map(Number)
    .sort((a, b) => a - b)
    .map((d) => ({
      label: 'T+' + d,
      ...map[d],
      holdingDays: d
    }))
})

const formulaAuditTips = computed(() => {
  const tips = response.value?.formulaAudit?.usage_tips
  return Array.isArray(tips) ? (tips as string[]) : []
})

const formulaAuditAlertType = computed(() => {
  const score = Number(response.value?.formulaAudit?.short_horizon_fit_score ?? 0)
  if (score >= 75) return 'success'
  if (score >= 50) return 'warning'
  return 'error'
})

const shortHorizonValidationType = computed(() => {
  const v = response.value?.shortHorizonValidation
  if (!v) return 'info'
  if (v.t3_effective || v.t5_effective) return 'success'
  if (v.t3_rank_ic_mean != null || v.t5_rank_ic_mean != null) return 'warning'
  return 'info'
})

const codexFactorTableRows = computed(() => {
  const map = response.value?.codexFactorIcByHolding
  if (!map) return []
  return ['T', 'F', 'R'].map((factor) => ({
    factor,
    h3: formatIcMean(map[3]?.[factor]?.rankIcMean),
    h5: formatIcMean(map[5]?.[factor]?.rankIcMean),
    h10: formatIcMean(map[10]?.[factor]?.rankIcMean)
  }))
})

function formatIcMean(v: unknown) {
  if (v == null || v === '') return '-'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(4) : '-'
}

const skipReasonLabels: Record<string, string> = {
  NO_QUOTE_ON_ENTRY_DAY: '入场日无行情（可能停牌）',
  INVALID_ENTRY_OPEN: '入场开盘价无效',
  ZERO_VOLUME_ON_ENTRY: '入场日无成交量（疑似停牌）',
  LIMIT_UP_AT_OPEN: '涨停开盘无法买入',
  NO_NEXT_TRADING_DAY: '无下一交易日',
  BEYOND_CALENDAR: '超出交易日历',
  NO_QUOTE_ON_EXIT_DAY: '出场日无行情（可能停牌）',
  INVALID_EXIT_CLOSE: '出场收盘价无效',
  ZERO_VOLUME_ON_EXIT: '出场日无成交量（疑似停牌）',
  LIMIT_DOWN_AT_CLOSE: '跌停收盘无法卖出'
}

function formatSkipReason(code: string) {
  return skipReasonLabels[code] ?? code
}

const assumptionLabels: Record<string, string> = {
  entry: '买入成交价',
  exit: '卖出成交价',
  t_plus_one_sell: 'T+1 卖出限制',
  holding_period_semantics: '持有期语义',
  commission_rate: '买入佣金率',
  sell_tax_rate: '卖出印花税',
  slippage_rate_each_side: '单边滑点',
  return_type: '收益口径',
  limit_up_entry_blocked: '涨停开盘不买入',
  limit_down_exit_blocked: '跌停收盘不卖出',
  limit_pct_source: '涨跌停幅度来源',
  skip_reason_codes: '跳过原因码',
  selector_tradability: '选股可交易性假设',
  short_horizon_research_focus: '短周期研究重点',
  codex_neutralization: '中性化处理'
}

const tradabilityAssumptionLabels: Record<string, string> = {
  exclude_st: '排除 ST',
  exclude_zero_volume: '排除零成交量',
  exclude_limit_up_at_close: '排除涨停收盘',
  exclude_limit_down_at_close: '排除跌停收盘',
  min_listing_days: '最短上市天数',
  listing_tenure_basis: '上市时长口径',
  listing_tenure_bar_scope: '上市时长K线范围',
  listing_tenure_warning: '上市时长提示',
  bulk_fetch: '批量拉取K线',
  limit_pct_source: '涨跌停幅度来源',
  limit_price_basis: '涨跌停价基准',
  avg_amount20_basis: '20日均成交额口径',
  min_avg_amount_gate: '最低成交额闸',
  source: '口径来源'
}

const tradabilitySourceLabels: Record<string, string> = {
  result_factor_snapshot: '取自选股结果快照',
  default_no_snapshot_in_result: '结果无快照，使用默认'
}

function formatAssumptionKey(key: string) {
  return assumptionLabels[key] ?? key
}

function formatAssumptionValue(value: unknown, key?: string): string {
  if (key === 'selector_tradability' && value && typeof value === 'object' && !Array.isArray(value)) {
    return Object.entries(value as Record<string, unknown>)
      .map(([k, v]) => {
        if (k === 'source' && typeof v === 'string') {
          return `${tradabilityAssumptionLabels[k] || k}=${tradabilitySourceLabels[v] || v}`
        }
        return `${tradabilityAssumptionLabels[k] || k}=${formatAssumptionValue(v)}`
      })
      .join('；')
  }
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number' && value > 0 && value < 1) return `${(value * 100).toFixed(2)}%`
  return String(value)
}

// 成交假设拆为「业务」与「技术细节」两组，避免一次性 dump 晦涩字段
const businessAssumptions = computed(() => {
  const a = response.value?.executionAssumptions as Record<string, unknown> | undefined
  if (!a) return []
  const keys = ['entry', 'exit', 't_plus_one_sell', 'commission_rate', 'sell_tax_rate',
    'slippage_rate_each_side', 'return_type', 'limit_up_entry_blocked',
    'limit_down_exit_blocked', 'selector_tradability']
  return keys.filter((k) => a[k] !== undefined).map((k) => ({
    key: k,
    label: formatAssumptionKey(k),
    value: formatAssumptionValue(a[k], k)
  }))
})

const technicalAssumptions = computed(() => {
  const a = response.value?.executionAssumptions as Record<string, unknown> | undefined
  if (!a) return []
  const keys = ['holding_period_semantics', 'limit_pct_source',
    'short_horizon_research_focus', 'codex_neutralization', 'skip_reason_codes']
  return keys.filter((k) => a[k] !== undefined).map((k) => ({
    key: k,
    label: formatAssumptionKey(k),
    value: formatAssumptionValue(a[k], k)
  }))
})

// ===== 图表 =====
const chartRef = ref<HTMLElement>()
const factorChartRef = ref<HTMLElement>()
const icByHoldingChartRef = ref<HTMLElement>()
const icSeriesChartRef = ref<HTMLElement>()
let chart: echarts.ECharts | null = null
let factorChart: echarts.ECharts | null = null
let icByHoldingChart: echarts.ECharts | null = null
let icSeriesChart: echarts.ECharts | null = null

function renderFactorChart() {
  if (!factorChartRef.value || !response.value?.factorAnalysis) return
  const groups = response.value.factorAnalysis.groupedReturns
  if (!groups.length) return
  if (!factorChart) factorChart = echarts.init(factorChartRef.value, CHART_THEME)
  factorChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const p = params[0]
        const g = groups[p.dataIndex]
        return [`<b>${g.label}</b>`, `平均收益：${(g.avgReturn * 100).toFixed(2)}%`, `截面日：${g.observations}`].join('<br/>')
      }
    },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '10%' },
    xAxis: {
      type: 'category',
      data: groups.map((g) => g.label),
      axisLabel: { interval: 0, rotate: groups.length > 5 ? 20 : 0 }
    },
    yAxis: {
      type: 'value',
      name: '收益 (%)',
      axisLabel: { formatter: '{value}%' }
    },
    series: [
      {
        name: '平均收益',
        type: 'bar',
        data: groups.map((g) => +(g.avgReturn * 100).toFixed(2)),
        itemStyle: {
          color: (params: { dataIndex: number }) => {
            const v = groups[params.dataIndex].avgReturn
            return v >= 0 ? CHART_UP : CHART_DOWN
          }
        },
        barWidth: '50%',
        label: {
          show: true,
          position: 'top',
          formatter: '{c}%'
        }
      }
    ]
  })
}

function renderIcByHoldingChart() {
  if (!icByHoldingChartRef.value || !icByHoldingRows.value.length) return
  const rows = icByHoldingRows.value
  if (!icByHoldingChart) icByHoldingChart = echarts.init(icByHoldingChartRef.value, CHART_THEME)
  icByHoldingChart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '10%' },
    xAxis: {
      type: 'category',
      data: rows.map((r) => r.label)
    },
    yAxis: [
      {
        type: 'value',
        name: '选股准确度'
      },
      {
        type: 'value',
        name: '收益差 %',
        axisLabel: { formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '选股准确度',
        type: 'bar',
        data: rows.map((r) => +r.rankIcMean.toFixed(4)),
        barWidth: '35%'
      },
      {
        name: '高分减低分收益差',
        type: 'line',
        yAxisIndex: 1,
        data: rows.map((r) => +(r.longShortSpread * 100).toFixed(2)),
        lineStyle: { width: 2 }
      }
    ]
  })
}

function renderIcSeriesChart() {
  const series = response.value?.factorAnalysis?.dailyRankIc
  if (!icSeriesChartRef.value || !series?.length) return
  if (!icSeriesChart) icSeriesChart = echarts.init(icSeriesChartRef.value, CHART_THEME)
  icSeriesChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const p = params[0]
        const row = series[p.dataIndex]
        return [`<b>${row.tradeDate}</b>`, `选股准确度：${row.rankIc.toFixed(4)}`, `样本：${row.sampleSize}`].join('<br/>')
      }
    },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '14%' },
    xAxis: {
      type: 'category',
      data: series.map((d) => d.tradeDate),
      axisLabel: { rotate: 35, fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      name: '选股准确度'
    },
    series: [
      {
        name: '选股准确度',
        type: 'line',
        data: series.map((d) => +d.rankIc.toFixed(4)),
        lineStyle: { width: 2 },
        areaStyle: { opacity: 0.12 },
        markLine: {
          silent: true,
          data: [{ yAxis: 0 }]
        }
      }
    ]
  })
}

function renderChart() {
  if (!chartRef.value || !response.value) return
  const stats = response.value.holdingPeriodStats
  const days = Object.keys(stats).map(Number).sort((a, b) => a - b)
  const winRates = days.map((d) => stats[d].winRate * 100)
  const avgReturns = days.map((d) => stats[d].avgReturn * 100)

  if (!chart) chart = echarts.init(chartRef.value, CHART_THEME)
  chart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const day = params[0].name
        const stat = stats[parseInt(day.replace('持有', '').replace('日', ''))] ?? stats[parseInt(day.replace('T+', ''))]
        return [
          `<b>${day}</b>`,
          `历史样本胜率：${(stat.winRate * 100).toFixed(1)}%`,
          `平均收益：${(stat.avgReturn * 100).toFixed(2)}%`,
          `中位数：${(stat.medianReturn * 100).toFixed(2)}%`,
          `最大：${(stat.maxReturn * 100).toFixed(2)}%  /  最小：${(stat.minReturn * 100).toFixed(2)}%`,
          `样本数：${stat.totalObservations}`
        ].join('<br/>')
      }
    },
    grid: { left: '8%', right: '8%', top: '10%', bottom: '10%' },
    xAxis: {
      type: 'category',
      data: days.map((d) => '持有' + d + '日')
    },
    yAxis: [
      {
        type: 'value',
        name: '历史样本胜率 (%)',
        min: 0,
        max: 100,
        axisLabel: { formatter: '{value}%' }
      },
      {
        type: 'value',
        name: '平均收益 (%)',
        axisLabel: { formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '历史样本胜率',
        type: 'bar',
        data: winRates,
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: CHART_C1 },
            { offset: 1, color: CHART_C2 }
          ])
        },
        barWidth: '40%',
        label: {
          show: true,
          position: 'top',
          formatter: '{c:.1f}%'
        }
      },
      {
        name: '平均收益',
        type: 'line',
        yAxisIndex: 1,
        data: avgReturns,
        lineStyle: { width: 2 },
        symbol: 'circle',
        symbolSize: 8,
        label: {
          show: true,
          position: 'top',
          formatter: '{c:.2f}%'
        }
      }
    ]
  })
}

// tab 切换时按需渲染对应图表（避免隐藏 tab 内 echarts 0 尺寸问题）
function handleTabChange(name: string | number) {
  nextTick(() => {
    if (name === 'performance') {
      renderChart()
      chart?.resize()
    } else if (name === 'factor') {
      renderFactorChart()
      renderIcByHoldingChart()
      renderIcSeriesChart()
      factorChart?.resize()
      icByHoldingChart?.resize()
      icSeriesChart?.resize()
    }
  })
}

watch(response, () => {
  dailyPageNum.value = 1
  activeTab.value = 'overview'
  nextTick(() => {
    // 概览页无图表；其余图表在切到对应 tab 时渲染
    if (activeTab.value === 'performance') renderChart()
  })
})

// 分页
const dailyPageNum = ref(1)
const pageSize = 20
const dailyPageTotal = computed(() => response.value?.dailyDetails?.length ?? 0)
const dailyPage = computed(() => {
  const details = response.value?.dailyDetails ?? []
  const start = (dailyPageNum.value - 1) * pageSize
  return details.slice(start, start + pageSize)
})

// 详情弹窗
const detailVisible = ref(false)
const detailDate = ref('')
const detailStocks = ref<StockReturn[]>([])

function showDetail(row: DailyDetail) {
  detailDate.value = row.tradeDate
  detailStocks.value = row.topStocks
  detailVisible.value = true
}

async function runBacktest() {
  if (!form.startDate || !form.endDate) {
    errorMsg.value = '请选择起始和结束日期'
    return
  }
  if (form.holdingDays.length === 0) {
    errorMsg.value = '请至少选择一个持仓周期'
    return
  }
  running.value = true
  errorMsg.value = ''
  response.value = null
  try {
    const data = await backtestApi.runBacktest({
      strategyType: form.strategyType || undefined,
      startDate: form.startDate,
      endDate: form.endDate,
      topN: form.topN,
      holdingDays: form.holdingDays,
      factorGroupCount: form.factorGroupCount,
      commissionRate: 0.0003,
      sellTaxRate: 0.0005,
      slippageRate: 0.0002
    })
    if (!data.totalTradingDays) {
      toast.info('该条件下无回测数据')
    }
    response.value = data
  } catch (e: unknown) {
    errorMsg.value = errorText(e)
    toast.error(errorMsg.value)
  } finally {
    running.value = false
  }
}

async function loadStrategies() {
  strategiesLoading.value = true
  try {
    strategies.value = await backtestApi.getStrategies()
  } catch {
    // silent
  } finally {
    strategiesLoading.value = false
  }
}

async function loadRuns() {
  runsLoading.value = true
  try {
    runs.value = await backtestApi.getRuns(20)
  } catch {
    // silent
  } finally {
    runsLoading.value = false
  }
}

async function loadRun(resultId: string) {
  if (!resultId) return
  running.value = true
  errorMsg.value = ''
  try {
    const data = await backtestApi.getRun(resultId)
    if (!data) {
      errorMsg.value = '未找到该回测记录'
      return
    }
    response.value = data
    // 同步表单参数，便于在历史结果基础上修改后重跑
    form.strategyType = data.strategyType ?? ''
    form.startDate = data.startDate
    form.endDate = data.endDate
    form.topN = data.topN || 10
  } catch (e: unknown) {
    errorMsg.value = errorText(e)
    toast.error(errorMsg.value)
  } finally {
    running.value = false
  }
}

function exportCsv() {
  if (!response.value) return
  const resp = response.value
  const details = resp.dailyDetails
  const holdingDays = form.holdingDays
  const lines: string[] = []

  // 1. 运行元信息与假设
  lines.push('== 回测运行元信息 ==')
  lines.push(`result_id,${resp.resultId ?? ''}`)
  lines.push(`strategy,${resp.strategyType || ''}`)
  lines.push(`start_date,${resp.startDate}`)
  lines.push(`end_date,${resp.endDate}`)
  lines.push(`top_n,${resp.topN}`)
  lines.push(`total_trading_days,${resp.totalTradingDays}`)
  for (const [k, v] of Object.entries(resp.executionAssumptions || {})) {
    lines.push(`assumption_${k},${typeof v === 'object' ? JSON.stringify(v) : v}`)
  }
  lines.push('')

  // 2. 每日胜率汇总
  lines.push('== 每日胜率 ==')
  lines.push(['tradeDate', ...holdingDays.map((d) => 'T+' + d + '_winRate')].join(','))
  for (const row of details) {
    lines.push([row.tradeDate, ...holdingDays.map((d) => row.dailyWinRate[d] ?? '')].join(','))
  }
  lines.push('')

  // 3. 个股明细（code/name/rank/score/entry/forward returns/skip reason）
  lines.push('== 个股明细 ==')
  lines.push(['tradeDate', 'stockCode', 'stockName', 'rankNo', 'finalScore', 'entryDate', 'entryPrice',
    ...holdingDays.map((d) => 'T+' + d + '_forwardReturn'), 'skipReason'].join(','))
  for (const row of details) {
    for (const s of row.topStocks || []) {
      lines.push([
        row.tradeDate, s.stockCode, `"${s.stockName ?? ''}"`, s.rankNo, s.finalScore, s.entryDate ?? '', s.entryPrice ?? '',
        ...holdingDays.map((d) => (s.forwardReturns?.[d] ?? s.periodSkipReasons?.[d] ?? '')),
        s.skipReason ?? ''
      ].join(','))
    }
  }

  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `backtest-${resp.strategyType || 'all'}-${resp.startDate}_${resp.endDate}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

// resize
function onResize() {
  chart?.resize()
  factorChart?.resize()
  icByHoldingChart?.resize()
  icSeriesChart?.resize()
}

async function runTurnover() {
  turnoverLoading.value = true
  try {
    turnover.value = await analyzeTurnover({
      strategy: form.strategyType || undefined,
      startDate: form.startDate,
      endDate: form.endDate,
      topN: form.topN
    })
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    turnoverLoading.value = false
  }
}

async function runStability() {
  if (!form.strategyType) {
    toast.warning('请先选择策略类型')
    return
  }
  stabilityLoading.value = true
  try {
    stability.value = await analyzeStability({
      strategy: form.strategyType,
      startDate: form.startDate,
      endDate: form.endDate,
      topN: form.topN,
      holdingDay: form.holdingDays[0] ?? 5
    })
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    stabilityLoading.value = false
  }
}

onMounted(() => {
  loadStrategies()
  loadRuns()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  factorChart?.dispose()
  icByHoldingChart?.dispose()
  icSeriesChart?.dispose()
})
</script>

<style scoped>
.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.disclaimer-alert {
  margin-bottom: 16px;
}

.section-title {
  margin: 6px 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.section-hint {
  margin: 4px 0 14px;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.holding-form {
  margin-top: -4px;
}

.run-alert {
  margin: 0 0 12px;
}

.result-meta {
  display: flex;
  gap: 20px;
  margin: 8px 0 4px;
  color: var(--text-secondary);
  font-size: 13px;
  flex-wrap: wrap;
}

.result-tabs {
  margin-top: 12px;
}

/* 概览结论卡 */
.verdict-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
  margin-bottom: 16px;
}

.verdict-card {
  border-radius: var(--r-md);
  padding: 14px 16px;
  border: 1px solid var(--hairline-strong);
  background: var(--surface-2);
}

.verdict-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.period-tag {
  font-size: 15px;
  font-weight: 600;
  color: var(--accent-1);
}

.verdict-text {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-primary);
  margin: 0 0 12px;
}

.verdict-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
}

.vm {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.vm-label {
  font-size: 11px;
  color: var(--text-secondary);
}

.vm b {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}

/* 成交假设 */
.assumptions-collapse {
  margin-top: 8px;
}

.assumptions-clean {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.assumption-row {
  display: flex;
  gap: 12px;
  font-size: 12px;
}

.assumption-label {
  color: var(--text-secondary);
  min-width: 120px;
  flex-shrink: 0;
}

.assumption-value {
  color: var(--text-primary);
  word-break: break-all;
}

.tech-collapse {
  margin-top: 10px;
}

/* 持仓表现 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.factor-ic-grid {
  grid-template-columns: repeat(auto-fill, minmax(250px, 1fr));
}

.period-card {
  border-radius: var(--r-md);
  padding: 12px 14px;
  border: 1px solid var(--hairline-strong);
  background: var(--surface-2);
}

.stat-header {
  font-size: var(--fz-lg);
  font-weight: 600;
  color: var(--accent-1);
  margin-bottom: 8px;
}

.stat-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.stat-label {
  font-size: var(--fz-xs);
  color: var(--text-secondary);
}

.stat-value {
  font-size: var(--fz-sm);
  font-weight: var(--fw-num);
  font-variant-numeric: tabular-nums;
}

.small-text {
  font-size: 11px;
  font-weight: normal;
  line-height: 1.4;
  text-align: right;
}

/* 收益/指标正负色：与全站 A 股红涨绿跌一致（text-up/text-down 同口径） */
.text-success { color: var(--up); }
.text-danger { color: var(--down); }
.text-muted { color: var(--text-dim); }

.chart-box {
  width: 100%;
  height: 300px;
  margin-bottom: 16px;
}

.data-table {
  margin-bottom: 8px;
}

.pagination-box {
  display: flex;
  justify-content: center;
  margin-top: 8px;
}

.formula-audit {
  font-size: 13px;
  line-height: 1.6;
}

.formula-audit p {
  margin: 4px 0;
}

.formula-tips {
  margin: 8px 0 0 18px;
  padding: 0;
}

.disclaimer-inline {
  margin-top: 6px;
  font-size: 11px;
}

.turnover-collapse {
  margin-top: 16px;
}
.stability-verdict {
  margin: 10px 0;
}
.turnover-meta {
  display: flex;
  gap: 18px;
  margin: 10px 0;
  font-size: 12px;
  color: var(--text-secondary);
}
.turnover-note {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-muted);
  line-height: 1.5;
}
</style>
