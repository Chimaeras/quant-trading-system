<template>
  <PageShell>
    <PageHeader title="统一打分选股工作台" subtitle="先执行打分任务拿到评分池，再按 TopN 与最低分从结果中筛选关注清单。">
      <template #actions>
        <el-radio-group v-model="mode" size="small" @change="onModeChange">
          <el-radio-button value="classic">经典多因子</el-radio-button>
          <el-radio-button value="oversold">超跌反弹</el-radio-button>
          <el-radio-button value="trend">趋势打分</el-radio-button>
          <el-radio-button value="comprehensive">综合三维度</el-radio-button>
          <el-radio-button value="multi_dim">多维度v1</el-radio-button>
        </el-radio-group>
      </template>
    </PageHeader>

    <SectionCard title="步骤1 · 执行打分任务">
    <el-form v-if="mode === 'classic'" inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="form.tradeDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="全库历史">
        <el-switch v-model="form.fullHistory" active-text="开" inactive-text="关" />
      </el-form-item>
      <el-form-item v-if="!form.fullHistory" label="回看天数">
        <el-input-number v-model="form.lookbackDays" :min="20" :max="3650" :step="5" />
      </el-form-item>
      <el-form-item label="打分池数量">
        <el-input-number v-model="form.scoreTopN" :min="10" :max="500" :step="10" />
      </el-form-item>
      <el-form-item label="最小日均成交额(元)">
        <el-input-number v-model="form.minAvgAmount" :min="0" :step="1000000" />
      </el-form-item>
      <el-form-item label="最小趋势分">
        <el-input-number v-model="form.minTrendScore" :min="0" :max="100" />
      </el-form-item>
      <el-form-item label="最短上市(天)">
        <el-input-number v-model="form.minListingDays" :min="0" :max="3650" :step="30" />
      </el-form-item>
      <el-form-item label="预测周期">
        <el-select v-model="form.horizonProfile" style="width: 200px">
          <el-option label="中长期趋势（默认）" value="MEDIUM_TREND" />
          <el-option label="短周期 3～5 日 (Codex)" value="SHORT_3_5D" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.horizonProfile === 'SHORT_3_5D'" label="Codex最低分">
        <el-input-number v-model="form.minCodexScore" :min="0" :max="100" :step="5" />
      </el-form-item>
      <el-form-item label="模板">
        <el-select v-model="form.templateId" clearable placeholder="可选模板" style="width: 180px">
          <el-option v-for="t in templates" :key="String(t.template_id)" :label="String(t.name)" :value="String(t.template_id)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runSelection">执行打分</el-button>
        <el-button :disabled="running" @click="loadLatest">加载最新</el-button>
        <el-button :disabled="running" @click="saveTemplate">保存模板</el-button>
      </el-form-item>
    </el-form>

    <el-form v-else-if="mode === 'oversold'" inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="oversoldForm.tradeDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="打分池数量">
        <el-input-number v-model="oversoldForm.scoreTopN" :min="10" :max="500" :step="10" />
      </el-form-item>
      <el-form-item label="最低综合分">
        <el-input-number v-model="oversoldForm.minTotalScore" :min="0" :max="100" />
      </el-form-item>
      <el-form-item label="最低日均成交额(元)">
        <el-input-number v-model="oversoldForm.minAvgAmount" :min="0" :step="1000000" />
      </el-form-item>
      <el-form-item label="排除 ST">
        <el-switch v-model="oversoldForm.excludeST" />
      </el-form-item>
      <el-form-item label="落库">
        <el-switch v-model="oversoldForm.persist" />
      </el-form-item>
      <el-form-item label="模板">
        <el-select v-model="oversoldForm.templateId" clearable placeholder="可选（参数合并）" style="width: 180px">
          <el-option v-for="t in templates" :key="String(t.template_id)" :label="String(t.name)" :value="String(t.template_id)" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runOversold">执行打分</el-button>
        <el-button :disabled="running" @click="loadLatest">加载最新</el-button>
        <el-button :disabled="running" @click="saveOversoldTemplate">保存模板</el-button>
      </el-form-item>
    </el-form>

    <el-form v-else inline>
      <el-form-item label="交易日">
        <el-date-picker v-model="commonForm.tradeDate" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="打分池数量">
        <el-input-number v-model="commonForm.scoreTopN" :min="10" :max="500" :step="10" />
      </el-form-item>
      <el-form-item v-if="mode === 'comprehensive'" label="回看天数">
        <el-input-number v-model="commonForm.lookbackDays" :min="20" :max="3650" :step="5" />
      </el-form-item>
      <el-form-item v-if="mode === 'trend'" label="乖离阈值">
        <el-input-number v-model="commonForm.biasThreshold" :min="1" :max="20" :step="0.5" :precision="1" />
      </el-form-item>
      <el-form-item label="最低综合分">
        <el-input-number v-model="commonForm.minTotalScore" :min="0" :max="100" :step="1" />
      </el-form-item>
      <el-form-item label="最低日均成交额(元)">
        <el-input-number v-model="commonForm.minAvgAmount" :min="0" :step="1000000" />
      </el-form-item>
      <el-form-item label="排除 ST">
        <el-switch v-model="commonForm.excludeST" />
      </el-form-item>
      <el-form-item label="最短上市(天)">
        <el-input-number v-model="commonForm.minListingDays" :min="0" :max="3650" :step="30" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="running" @click="runGeneric">执行打分</el-button>
        <el-button :disabled="running" @click="loadLatest">加载最新</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="taskStatus" :type="taskStatus === 'FAILED' ? 'error' : taskStatus === 'SUCCESS' ? 'success' : taskStatus === 'CANCELLED' ? 'warning' : 'info'" show-icon :closable="false" class="run-alert">
      <template #title>
        执行任务状态：{{ taskStatus }} <span v-if="taskId">（{{ taskId }}）</span>
      </template>
      <template #default>
        <div class="run-meta">
          <span>{{ taskMessage || '任务执行中' }}</span>
          <span v-if="taskStatus === 'PENDING'">排队中：前方 {{ pendingAhead }} 个等待任务，当前运行 {{ runningCount }} 个</span>
          <span v-if="taskError">错误：{{ taskError }}</span>
          <span v-if="pollStopReason">监控停止原因：{{ pollStopReason }}</span>
          <span v-if="pollStopAt">停止时间：{{ pollStopAt }}</span>
        </div>
        <div class="run-actions">
          <el-button v-if="taskId && (taskStatus === 'PENDING' || taskStatus === 'RUNNING')" link type="danger" @click="cancelTaskServer">取消当前任务</el-button>
          <el-button v-if="taskId && taskStatus === 'PENDING'" link type="warning" @click="prioritizeCurrentTask">取消前序并优先执行当前任务</el-button>
          <el-button v-if="pendingAhead > 0" link type="danger" @click="clearPendingQueue">一键清空待执行队列</el-button>
          <el-button v-if="taskId && (taskStatus === 'PENDING' || taskStatus === 'RUNNING')" link type="danger" @click="forceRestartCurrentTask">清队列+取消并立即重跑</el-button>
          <el-button v-if="isPollingActive" link type="warning" @click="cancelTaskWatch">停止监控</el-button>
          <el-button v-if="taskId" link type="primary" @click="refreshTaskStatus">刷新任务状态</el-button>
          <el-button v-if="resultMeta.result_id" link type="primary" @click="refreshResultData">刷新已完成股票</el-button>
          <el-button v-else-if="taskId && taskStatus !== 'SUCCESS' && taskStatus !== 'FAILED' && taskStatus !== 'CANCELLED'" link type="primary" @click="resumeTaskWatch">恢复监控</el-button>
        </div>
        <el-progress :percentage="Math.max(0, Math.min(100, taskProgress))" :status="taskStatus === 'FAILED' ? 'exception' : taskStatus === 'SUCCESS' ? 'success' : taskStatus === 'CANCELLED' ? 'warning' : undefined" />
      </template>
    </el-alert>
    <el-form inline class="task-query-form">
      <el-form-item label="任务ID查询">
        <el-input v-model="queryTaskId" placeholder="USK..." style="width: 220px" clearable />
      </el-form-item>
      <el-form-item>
        <el-button @click="queryByTaskId">查询任务</el-button>
      </el-form-item>
      <el-form-item label="结果ID查询">
        <el-input v-model="queryResultId" placeholder="US..." style="width: 220px" clearable />
      </el-form-item>
      <el-form-item>
        <el-button @click="queryByResultId">查询结果</el-button>
      </el-form-item>
      <el-form-item label="历史任务">
        <el-select v-model="historyTaskId" placeholder="选择近期任务载入" clearable filterable style="width: 300px" @change="loadHistoryTask">
          <el-option v-for="t in recentTasks" :key="t.task_id" :label="`${t.strategy} ${t.status} ${t.created_at || ''}`" :value="t.task_id" />
        </el-select>
        <el-button link type="primary" :loading="recentTasksLoading" @click="loadRecentTasks">刷新</el-button>
      </el-form-item>
    </el-form>

    <div v-if="lastTaskSummary" class="meta">
      <span>最近任务：{{ lastTaskSummary.taskId }}</span>
      <span>策略：{{ lastTaskSummary.strategy }}</span>
      <span>耗时：{{ lastTaskSummary.elapsedMs }} ms</span>
      <span>返回：{{ lastTaskSummary.returnedItems }} / {{ lastTaskSummary.totalItems }}</span>
      <span v-if="lastTaskSummary.qualifiedItems != null">合格：{{ lastTaskSummary.qualifiedItems }}</span>
      <span v-if="lastTaskSummary.minTotalScore != null">质量闸 ≥ {{ lastTaskSummary.minTotalScore }}</span>
      <span>完成时间：{{ lastTaskSummary.finishedAt }}</span>
    </div>

    </SectionCard>

    <SectionCard title="步骤2 · 从评分结果中选择关注 TopN">
    <el-form inline class="focus-form">
      <el-form-item label="TopN">
        <el-input-number v-model="focusForm.topN" :min="1" :max="200" />
      </el-form-item>
      <el-form-item label="最低分">
        <el-input-number v-model="focusForm.minScore" :min="0" :max="100" :step="0.5" :precision="1" />
      </el-form-item>
      <el-form-item label="结果视图">
        <el-radio-group v-model="focusForm.view" size="small">
          <el-radio-button value="focus">关注清单</el-radio-button>
          <el-radio-button value="all">全部评分</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item>
        <el-input
          v-model="tableSearch"
          placeholder="按代码/名称过滤"
          clearable
          style="width: 180px"
        />
        <el-button type="primary" :disabled="!results.length" @click="applyFocusSelection">生成关注清单</el-button>
        <el-button :disabled="!tableRows.length" @click="exportCsv">导出当前视图</el-button>
        <el-button link type="primary" :disabled="!resultMeta.result_id" @click="saveFocusSnapshot">保存关注快照</el-button>
        <el-button link type="primary" @click="loadFocusSnapshot">载入快照</el-button>
      </el-form-item>
    </el-form>

    <div class="meta" v-if="resultMeta.result_id">
      <span>结果ID: {{ resultMeta.result_id }}</span>
      <span>交易日: {{ resultMeta.trade_date }}</span>
      <span>已评分: {{ results.length }} 只</span>
      <span v-if="resultMeta.total_candidates > 0">进度: {{ resultMeta.processed_candidates }}/{{ resultMeta.total_candidates }}</span>
      <span>关注: {{ focusResults.length }} 只</span>
      <span>因子版本: {{ resultMeta.factor_version || 'v1' }}</span>
      <span v-if="resultMeta.run_status">状态: {{ resultMeta.run_status }}</span>
      <span v-if="resultMeta.min_total_score != null">质量闸 ≥ {{ resultMeta.min_total_score }}</span>
      <span v-if="resultMeta.qualified_items != null">合格 {{ resultMeta.qualified_items }} 只</span>
      <span v-if="resultMeta.total_scored_pool != null">评分池 {{ resultMeta.total_scored_pool }} 只</span>
      <span v-if="resultMeta.cross_section_count != null">全截面 {{ resultMeta.cross_section_count }} 只</span>
      <span v-if="resultMeta.display_top_n != null">展示 Top {{ resultMeta.display_top_n }}</span>
      <span v-if="tradabilitySummary">{{ tradabilitySummary }}</span>
      <el-button v-if="hasFactorSnapshot" link type="primary" @click="snapshotVisible = true">策略参数快照</el-button>
    </div>

    <el-alert
      v-if="resultMeta.score_type === 'rule_score' && results.length"
      type="warning"
      :closable="false"
      show-icon
      class="score-type-alert"
      :title="resultMeta.score_type_note || '当前分数为规则分数/截面百分位，非上涨概率'"
    />

    <EmptyState
      v-if="!loading && resultMeta.result_id && results.length === 0"
      :message="resultMeta.min_total_score != null
        ? `今日无符合质量闸的候选：最低综合分 ${resultMeta.min_total_score}，评分池 ${resultMeta.total_scored_pool ?? 0} 只，合格 ${resultMeta.qualified_items ?? 0} 只。宁可空结果，也不凑满 TopN。`
        : (form.horizonProfile === 'SHORT_3_5D' && form.minCodexScore > 0
            ? `Codex 短周期：最低百分位分 ${form.minCodexScore}，且须 F&gt;−0.5、R&gt;−1.0。可调低阈值或扩大股票池。`
            : '今日无符合质量闸的候选')"
    />

    <el-table v-if="mode === 'classic' && results.length" v-loading="loading" :data="tableRows" stripe>
      <el-table-column label="排名" width="70">
        <template #default="{ row, $index }">{{ resolveRank(row, $index) }}</template>
      </el-table-column>
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="120" />
      <el-table-column prop="final_score" label="综合分（规则分）" min-width="100" sortable />
      <el-table-column v-if="form.horizonProfile === 'SHORT_3_5D'" label="T" min-width="72">
        <template #default="{ row }">{{ formatCodexFactor(row, 'T') }}</template>
      </el-table-column>
      <el-table-column v-if="form.horizonProfile === 'SHORT_3_5D'" label="F" min-width="72">
        <template #default="{ row }">{{ formatCodexFactor(row, 'F') }}</template>
      </el-table-column>
      <el-table-column v-if="form.horizonProfile === 'SHORT_3_5D'" label="R" min-width="72">
        <template #default="{ row }">{{ formatCodexFactor(row, 'R') }}</template>
      </el-table-column>
      <el-table-column v-if="form.horizonProfile === 'SHORT_3_5D'" label="中性化" min-width="100">
        <template #default="{ row }">{{ formatNeutralizationMode(row) }}</template>
      </el-table-column>
      <el-table-column v-if="form.horizonProfile !== 'SHORT_3_5D'" prop="trend_score" label="趋势分" min-width="90" sortable />
      <el-table-column v-if="form.horizonProfile !== 'SHORT_3_5D'" prop="momentum_score" label="动量分" min-width="90" sortable />
      <el-table-column v-if="form.horizonProfile !== 'SHORT_3_5D'" prop="ret20" label="20日收益(%)" min-width="110" sortable />
      <el-table-column v-if="form.horizonProfile !== 'SHORT_3_5D'" prop="ret60" label="60日收益(%)" min-width="110" sortable />
      <el-table-column prop="max_drawdown60" label="60日回撤(%)" min-width="110" sortable />
      <el-table-column label="操作" min-width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-table v-else-if="mode === 'oversold' && results.length" v-loading="loading" :data="tableRows" stripe>
      <el-table-column label="排名" width="70">
        <template #default="{ row, $index }">{{ resolveRank(row, $index) }}</template>
      </el-table-column>
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="120" />
      <el-table-column prop="final_score" label="综合分（规则分）" min-width="100" sortable />
      <el-table-column prop="trend_score" label="趋势(40)" min-width="88" sortable />
      <el-table-column prop="volume_score" label="量能(30)" min-width="88" sortable />
      <el-table-column prop="momentum_score" label="超跌(30)" min-width="88" sortable />
      <el-table-column prop="volatility20" label="加权分" min-width="88" sortable />
      <el-table-column prop="ret20" label="20日收益(%)" min-width="110" sortable />
      <el-table-column prop="ret60" label="60日收益(%)" min-width="110" sortable />
      <el-table-column label="操作" min-width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-table v-else-if="results.length" v-loading="loading" :data="tableRows" stripe>
      <el-table-column label="排名" width="70">
        <template #default="{ row, $index }">{{ resolveRank(row, $index) }}</template>
      </el-table-column>
      <el-table-column prop="stock_code" label="代码" min-width="95" />
      <el-table-column prop="stock_name" label="名称" min-width="120" />
      <el-table-column prop="final_score" label="综合分（规则分）" min-width="100" sortable />
      <el-table-column prop="trend_score" :label="dimensionLabels.dim1" min-width="90" sortable />
      <el-table-column prop="momentum_score" :label="dimensionLabels.dim2" min-width="90" sortable />
      <el-table-column prop="volume_score" :label="dimensionLabels.dim3" min-width="90" sortable />
      <el-table-column v-if="mode === 'comprehensive'" prop="investment_advice" label="投资建议" min-width="100" />
      <el-table-column v-if="mode === 'trend'" prop="buy_signal" label="信号" min-width="90">
        <template #default="{ row }">{{ row.buy_signal || row.extra_metrics?.buy_signal || '--' }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p v-if="mode === 'classic'" class="tip">流程：先执行打分拿到评分池，再按 TopN 和最低分生成关注清单。</p>
    <p v-else-if="mode === 'oversold'" class="tip">逻辑：趋势确认(40) + 成交量验证(30) + 超跌程度(30)；关注清单支持二次阈值筛选。</p>
    <p v-else-if="mode === 'trend'" class="tip">逻辑：趋势/乖离/量能等六维评分；服务端按最低综合分（默认45）过滤，不足 TopN 时不凑数。</p>
    <p v-else-if="mode === 'comprehensive'" class="tip">逻辑：技术面30 + 基本面40 + 情绪面30；默认质量闸55分。情绪面若无外部舆情，将标注为「技术面推导」并已打折。</p>
    <p v-else class="tip">逻辑：纯库内 K 线 + PE + 行业聚合的多维度打分 v1；默认质量闸55分。</p>
    </SectionCard>
  </PageShell>

  <el-dialog v-model="detailVisible" title="选股详情" width="720px">
    <el-descriptions :column="2" border v-if="detailRow">
      <el-descriptions-item label="股票">{{ detailRow.stock_code }} {{ detailRow.stock_name }}</el-descriptions-item>
      <el-descriptions-item label="综合分">{{ detailRow.final_score ?? detailRow.total_score }}</el-descriptions-item>
      <el-descriptions-item label="数据日期">{{ resultMeta.trade_date || '--' }}</el-descriptions-item>
      <el-descriptions-item label="因子版本">{{ resultMeta.factor_version || 'v1' }}</el-descriptions-item>
      <template v-if="mode === 'oversold'">
        <el-descriptions-item label="趋势(40)">{{ detailRow.trend_score }}</el-descriptions-item>
        <el-descriptions-item label="量能(30)">{{ detailRow.volume_score }}</el-descriptions-item>
        <el-descriptions-item label="超跌(30)">{{ detailRow.momentum_score }}</el-descriptions-item>
        <el-descriptions-item label="加权分">{{ detailRow.volatility20 }}</el-descriptions-item>
      </template>
      <template v-else-if="isGenericMode">
        <el-descriptions-item :label="dimensionLabels.dim1">{{ detailRow.trend_score }}</el-descriptions-item>
        <el-descriptions-item :label="dimensionLabels.dim2">{{ detailRow.momentum_score }}</el-descriptions-item>
        <el-descriptions-item :label="dimensionLabels.dim3">{{ detailRow.volume_score }}</el-descriptions-item>
        <el-descriptions-item v-if="mode === 'comprehensive'" label="投资建议">{{ detailRow.investment_advice || detailRow.extra_metrics?.suggestion || '--' }}</el-descriptions-item>
        <el-descriptions-item v-if="mode === 'comprehensive' && sentimentMeta(detailRow).source" label="情绪数据来源" :span="2">
          {{ sentimentMeta(detailRow).label }}
        </el-descriptions-item>
      </template>
      <template v-else-if="form.horizonProfile === 'SHORT_3_5D'">
        <el-descriptions-item label="T 因子">{{ formatCodexFactor(detailRow, 'T') }}</el-descriptions-item>
        <el-descriptions-item label="F 因子">{{ formatCodexFactor(detailRow, 'F') }}</el-descriptions-item>
        <el-descriptions-item label="R 因子">{{ formatCodexFactor(detailRow, 'R') }}</el-descriptions-item>
        <el-descriptions-item label="G 市场">{{ formatCodexFactor(detailRow, 'G_market') }}</el-descriptions-item>
        <el-descriptions-item label="S_raw">{{ formatCodexFactor(detailRow, 'S_raw') }}</el-descriptions-item>
        <el-descriptions-item label="百分位分">{{ formatCodexFactor(detailRow, 'score_percentile') }}</el-descriptions-item>
        <el-descriptions-item label="中性化">{{ formatNeutralizationMode(detailRow) }}</el-descriptions-item>
        <el-descriptions-item v-if="formatCodexFactor(detailRow, 'G_weighting') !== '—'" label="G 加权">
          {{ formatGWeighting(detailRow) }}
        </el-descriptions-item>
        <el-descriptions-item label="ln(MV)来源">{{ formatLogMvSource(detailRow) }}</el-descriptions-item>
        <el-descriptions-item v-if="formatCodexFactor(detailRow, 'log_mv_reliable') !== '—'" label="ln(MV)可靠">
          {{ formatCodexFactor(detailRow, 'log_mv_reliable') }}
        </el-descriptions-item>
        <el-descriptions-item v-if="formatCodexFactor(detailRow, 'financial_imputed_count') !== '—'" label="财报填充项">
          {{ formatCodexFactor(detailRow, 'financial_imputed_count') }}
        </el-descriptions-item>
      </template>
      <template v-else>
        <el-descriptions-item label="趋势分">{{ detailRow.trend_score }}</el-descriptions-item>
        <el-descriptions-item label="动量分">{{ detailRow.momentum_score }}</el-descriptions-item>
        <el-descriptions-item label="量价分">{{ detailRow.volume_score }}</el-descriptions-item>
        <el-descriptions-item label="风险惩罚">{{ detailRow.risk_penalty }}</el-descriptions-item>
      </template>
      <el-descriptions-item label="20日收益">{{ detailRow.ret20 != null ? `${detailRow.ret20}%` : '—' }}</el-descriptions-item>
      <el-descriptions-item label="60日收益">{{ detailRow.ret60 != null ? `${detailRow.ret60}%` : '—' }}</el-descriptions-item>
      <el-descriptions-item label="日均成交额" :span="2">{{ detailRow.avg_amount20 }}</el-descriptions-item>
      <el-descriptions-item label="触发原因" :span="2">
        <el-table v-if="detailReasonEntries.length" :data="detailReasonEntries" size="small" stripe class="reason-table">
          <el-table-column prop="label" label="指标" min-width="140" />
          <el-table-column prop="value" label="取值" min-width="180" />
        </el-table>
        <span v-else class="empty-reason">暂无结构化原因，见下方原始数据。</span>
      </el-descriptions-item>
      <el-descriptions-item label="原始明细" :span="2">
        <pre class="json-box">{{ JSON.stringify(parseItemReasons(detailRow), null, 2) }}</pre>
      </el-descriptions-item>
    </el-descriptions>
  </el-dialog>

  <el-dialog v-model="snapshotVisible" title="策略参数快照（factor_snapshot）" width="680px">
    <el-table v-if="factorSnapshotEntries.length" :data="factorSnapshotEntries" size="small" stripe>
      <el-table-column prop="label" label="参数" min-width="180" />
      <el-table-column prop="value" label="取值" min-width="320" show-overflow-tooltip />
    </el-table>
    <EmptyState v-else message="当前结果无 factor_snapshot" />
    <pre v-if="hasFactorSnapshot" class="json-box snapshot-json">{{ JSON.stringify(resultMeta.factor_snapshot, null, 2) }}</pre>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { selectorApi } from '@/api/selector'
import { scoringApi, type UnifiedScoringStrategy, type UnifiedScoringTaskStatus } from '@/api/scoring'
import { errorText } from '@/api/request'
import { FOCUS_SNAPSHOT_KEY } from '@/utils/userScope'
import { useToast } from '@/composables/useToast'
import PageShell from '@/components/ui/PageShell.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import SectionCard from '@/components/ui/SectionCard.vue'
import EmptyState from '@/components/ui/EmptyState.vue'

type ModeType = 'classic' | 'oversold' | 'trend' | 'comprehensive' | 'multi_dim'
type TaskStatusType = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED' | ''

/** 上市天数缺省闸（新股需≥指定天数才纳入可交易池），与后端默认对齐，避免字面量漂移 */
const MIN_LISTING_DAYS_DEFAULT = 60
/** 各模式缺省最低总分：trend=45、comprehensive/multi_dim=55、classic/oversold=0（不设下限按分排序） */
const DEFAULT_MIN_TOTAL_SCORE = { trend: 45, comprehensive: 55, multi_dim: 55, classic: 0, oversold: 0 } as const
const LISTING_DAYS_FALLBACK_LABEL = `上市≥${MIN_LISTING_DAYS_DEFAULT}天（缺 snapshot 时默认）`
/** 结果表关键字过滤（代码/名称），空串=不过滤 */
const tableSearch = ref('')

const loading = ref(false)
const running = ref(false)
const toast = useToast()
const mode = ref<ModeType>('classic')
const results = ref<Record<string, any>[]>([])
const focusResults = ref<Record<string, any>[]>([])
const detailVisible = ref(false)
const snapshotVisible = ref(false)
const detailRow = ref<Record<string, any> | null>(null)
const taskId = ref('')
const taskStatus = ref<TaskStatusType>('')
const taskProgress = ref(0)
const taskMessage = ref('')
const taskError = ref('')
const pollSerial = ref(0)
const isPollingActive = ref(false)
const pollStopReason = ref('')
const pollStopAt = ref('')
const pendingAhead = ref(0)
const runningCount = ref(0)
const queryTaskId = ref('')
const queryResultId = ref('')
const recentTasks = ref<Array<{ task_id: string; strategy: string; status: string; result_id?: string; created_at?: string }>>([])
const recentTasksLoading = ref(false)
const historyTaskId = ref('')
const lastTaskSummary = ref<{
  taskId: string
  strategy: string
  elapsedMs: number
  totalItems: number
  returnedItems: number
  qualifiedItems?: number
  minTotalScore?: number
  finishedAt: string
} | null>(null)

const resultMeta = reactive({
  result_id: '',
  trade_date: '',
  factor_version: 'v1',
  run_status: '',
  processed_candidates: 0,
  total_candidates: 0,
  min_total_score: null as number | null,
  qualified_items: null as number | null,
  total_scored_pool: null as number | null,
  factor_snapshot: null as Record<string, unknown> | null,
  cross_section_count: null as number | null,
  display_top_n: null as number | null,
  score_type: '' as string,
  score_type_note: '' as string
})
const templates = ref<Array<Record<string, unknown>>>([])

const form = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  fullHistory: true,
  lookbackDays: 120,
  scoreTopN: 500,
  minAvgAmount: 30_000_000,
  minTrendScore: 40,
  minListingDays: MIN_LISTING_DAYS_DEFAULT,
  horizonProfile: 'MEDIUM_TREND',
  minCodexScore: 90,
  templateId: ''
})

const oversoldForm = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  scoreTopN: 500,
  minTotalScore: 60,
  minAvgAmount: 30_000_000,
  excludeST: true,
  persist: true,
  templateId: ''
})

const commonForm = reactive({
  tradeDate: dayjs().format('YYYY-MM-DD'),
  scoreTopN: 500,
  lookbackDays: 120,
  biasThreshold: 5.0,
  minTotalScore: 45,
  minAvgAmount: 30_000_000,
  excludeST: true,
  minListingDays: MIN_LISTING_DAYS_DEFAULT
})

const isGenericMode = computed(() => ['trend', 'comprehensive', 'multi_dim'].includes(mode.value))

const dimensionLabels = computed(() => {
  switch (mode.value) {
    case 'trend':
      return { dim1: '趋势', dim2: '乖离', dim3: '量能' }
    case 'comprehensive':
      return { dim1: '技术(30)', dim2: '基本(40)', dim3: '情绪(30)' }
    case 'multi_dim':
      return { dim1: '技术', dim2: '估值', dim3: '行业' }
    default:
      return { dim1: '维度1', dim2: '维度2', dim3: '维度3' }
  }
})

const focusForm = reactive({
  topN: 20,
  minScore: 0,
  view: 'focus' as 'focus' | 'all'
})

const displayedResults = computed(() => (focusForm.view === 'focus' ? focusResults.value : results.value))

/** 结果表展示行：在 displayedResults 之上做关键字过滤（代码/名称），供表格与导出共用 */
const tableRows = computed(() => {
  const rows = displayedResults.value
  const kw = tableSearch.value.trim().toLowerCase()
  if (!kw) return rows
  return rows.filter(
    (r) =>
      String(r.stock_code ?? '').toLowerCase().includes(kw) ||
      String(r.stock_name ?? '').toLowerCase().includes(kw)
  )
})

const REASON_LABELS: Record<string, string> = {
  trend: '趋势判断',
  ret20: '20日收益',
  ret60: '60日收益',
  rsi14: 'RSI14',
  volume_ratio: '量比',
  pass_min_avg_amount: '最低成交额',
  pass_min_trend_score: '最低趋势分',
  pass_ma20_gt_ma60: 'MA20>MA60',
  pass_ret20_floor: '20日收益下限',
  buy_signal: '买入信号',
  sentiment_data_source: '情绪数据来源',
  sentiment_discount_factor: '情绪折扣系数',
  oversold_level: '超跌程度',
  volume_price_type: '量价形态',
  quality_gate: '质量闸',
  G_weighting: 'G 市场加权',
  G_market: 'G 市场状态',
  neutralization_mode: '中性化模式',
  financial_imputed_count: '财报填充项',
  log_mv_source: 'ln(MV)来源',
  log_mv_reliable: 'ln(MV)可靠',
  log_mv_reliability: 'ln(MV)可靠度',
  mv_reliable_count: '可靠市值样本数',
  mv_total_count: '市值样本总数',
  min_codex_score_warning: 'Codex阈值提示',
  pass_ma_trend: '均线趋势闸',
  pass_ret20: '20日收益闸',
  regression_log_mv_imputed_count: '回归ln(MV)插补数',
  score_percentile: '百分位分',
  preprocessing: '预处理管道'
}

const TRADABILITY_LABELS: Record<string, string> = {
  exclude_st: '排除 ST',
  exclude_zero_volume: '排除零成交量(停牌代理)',
  exclude_limit_up_at_close: '排除信号日涨停收盘',
  exclude_limit_down_at_close: '排除信号日跌停收盘',
  min_listing_days: '最短上市自然日',
  listing_tenure_basis: '上市时长口径',
  listing_tenure_bar_scope: '上市时长K线范围',
  listing_tenure_warning: '上市时长提示',
  bulk_fetch: '批量拉取K线',
  limit_pct_source: '涨跌停幅度来源',
  limit_price_basis: '涨跌停价基准',
  avg_amount20_basis: '20日均成交额口径',
  min_avg_amount_gate: '最低成交额闸'
}

const hasFactorSnapshot = computed(() => {
  const snap = resultMeta.factor_snapshot
  return !!snap && typeof snap === 'object' && Object.keys(snap).length > 0
})

const factorSnapshotEntries = computed(() => flattenSnapshotEntries(resultMeta.factor_snapshot))

const tradabilitySummary = computed(() => {
  const snap = resultMeta.factor_snapshot
  const t = snap?.tradability
  if (t && typeof t === 'object') {
    const trad = t as Record<string, unknown>
    const parts: string[] = []
    if (trad.exclude_limit_up_at_close === true) parts.push('过滤涨停')
    if (trad.exclude_limit_down_at_close === true) parts.push('过滤跌停')
    if (trad.exclude_st === true) parts.push('排除ST')
    const minDays = trad.min_listing_days
    if (typeof minDays === 'number' && minDays > 0) parts.push(`上市≥${minDays}天`)
    if (trad.bulk_fetch === false) parts.push('非bulk预过滤')
    if (parts.length) return `可交易性: ${parts.join(' / ')}`
  }
  if (mode.value === 'oversold') return `可交易性: 过滤涨停 / 排除ST / ${LISTING_DAYS_FALLBACK_LABEL}`
  if (isGenericMode.value || mode.value === 'classic') return `可交易性: 过滤涨跌停 / 排除ST / ${LISTING_DAYS_FALLBACK_LABEL}`
  return ''
})

const detailReasonEntries = computed(() => {
  if (!detailRow.value) return []
  return reasonEntries(detailRow.value)
})

function formatReasonValue(value: unknown): string {
  if (typeof value === 'boolean') return value ? '通过' : '未通过'
  if (value === null || value === undefined) return '--'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function reasonEntries(row: Record<string, any>) {
  const parsed = parseItemReasons(row)
  return Object.entries(parsed)
    .filter(([key]) => !['reasons_json', 'raw_item'].includes(key))
    .map(([key, value]) => ({
      key,
      label: REASON_LABELS[key] || key,
      value: formatReasonValue(value)
    }))
}

function flattenSnapshotEntries(
  snap: Record<string, unknown> | null,
  prefix = ''
): Array<{ label: string; value: string }> {
  if (!snap || typeof snap !== 'object') return []
  const rows: Array<{ label: string; value: string }> = []
  for (const [key, value] of Object.entries(snap)) {
    const label = prefix ? `${prefix}.${key}` : key
    if (key === 'tradability' && value && typeof value === 'object' && !Array.isArray(value)) {
      for (const [tk, tv] of Object.entries(value as Record<string, unknown>)) {
        rows.push({
          label: `可交易性.${TRADABILITY_LABELS[tk] || tk}`,
          value: formatReasonValue(tv)
        })
      }
      continue
    }
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      rows.push(...flattenSnapshotEntries(value as Record<string, unknown>, label))
    } else {
      rows.push({ label, value: formatReasonValue(value) })
    }
  }
  return rows
}

function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function scoreOf(row: Record<string, any>) {
  const v = row.final_score ?? row.total_score ?? 0
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

function parseItemReasons(row: Record<string, any>): Record<string, any> {
  const reasons = row.reasons
  if (reasons && typeof reasons === 'object') {
    const nested = (reasons as Record<string, any>).reasons_json ?? (reasons as Record<string, any>).raw_item?.reasons_json
    if (nested && typeof nested === 'object') return nested as Record<string, any>
    if (typeof nested === 'string') {
      try {
        return JSON.parse(nested) as Record<string, any>
      } catch {
        return reasons as Record<string, any>
      }
    }
    return reasons as Record<string, any>
  }
  if (typeof row.reasons_json === 'string') {
    try {
      return JSON.parse(row.reasons_json) as Record<string, any>
    } catch {
      return {}
    }
  }
  return {}
}

function formatCodexFactor(row: Record<string, any>, key: string) {
  const r = parseItemReasons(row)
  const v = r[key]
  if (v == null || v === '') return '—'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : String(v)
}

function formatLogMvSource(row: Record<string, any>) {
  const src = formatCodexFactor(row, 'log_mv_source')
  if (src === '—') return src
  const labels: Record<string, string> = {
    turnover_amount: '换手率×成交额',
    close_x_volume: '收盘价×成交量',
    avg_amount20_fallback: '20日均额(回退)'
  }
  return labels[src] ?? src
}

function formatNeutralizationMode(row: Record<string, any>) {
  const mode = formatCodexFactor(row, 'neutralization_mode')
  if (mode === '—') return mode
  const labels: Record<string, string> = {
    FULL_MV_INDUSTRY_REGRESSION: '全回归(lnMV+行业)',
    LOG_MV_ONLY_REGRESSION: '仅ln(MV)回归',
    INDUSTRY_DEMEAN_FALLBACK: '行业市值加权去均值',
    PASSTHROUGH: '直通(样本不足)',
    SKIPPED_MV_RELIABILITY: '跳过市值中性化(可靠市值不足)'
  }
  return labels[mode] ?? mode
}

function formatGWeighting(row: Record<string, any>) {
  const w = formatCodexFactor(row, 'G_weighting')
  if (w === 'value_weighted_by_exp_logMv') return '市值加权(exp lnMV)'
  return w
}

function sentimentMeta(row: Record<string, any> | null) {
  if (!row) return { source: '', label: '' }
  const r = parseItemReasons(row)
  const source = String(r.sentiment_data_source || '')
  if (!source) return { source: '', label: '' }
  if (source === 'derived_from_technical') {
    const discount = r.sentiment_discount_factor ?? 0.6
    return { source, label: `技术面推导（可信度折扣 ${discount}，非真实舆情）` }
  }
  return { source, label: source }
}

function resolveRank(row: Record<string, any>, index: number) {
  if (focusForm.view === 'focus') return row.local_rank ?? index + 1
  return row.rank_no ?? index + 1
}

function resetTaskState() {
  taskId.value = ''
  taskStatus.value = ''
  taskProgress.value = 0
  taskMessage.value = ''
  taskError.value = ''
  pendingAhead.value = 0
  runningCount.value = 0
}

function stopPolling(reason?: string) {
  const hadPolling = isPollingActive.value
  pollSerial.value += 1
  isPollingActive.value = false
  if (reason && hadPolling) {
    pollStopReason.value = reason
    pollStopAt.value = dayjs().format('YYYY-MM-DD HH:mm:ss')
    taskMessage.value = reason
  }
}

function setTaskState(t: UnifiedScoringTaskStatus) {
  taskId.value = t.task_id
  taskStatus.value = t.status
  taskProgress.value = Number(t.progress_pct || 0)
  taskMessage.value = String(t.message || '')
  taskError.value = String(t.error_message || '')
  pendingAhead.value = Number(t.pending_ahead || 0)
  runningCount.value = Number(t.running_count || 0)
}

function buildTaskSummary(t: UnifiedScoringTaskStatus) {
  const startedAt = t.started_at ? new Date(t.started_at).getTime() : 0
  const finishedAt = t.finished_at ? new Date(t.finished_at).getTime() : 0
  const elapsedMs = startedAt > 0 && finishedAt >= startedAt ? finishedAt - startedAt : 0
  lastTaskSummary.value = {
    taskId: t.task_id,
    strategy: t.strategy,
    elapsedMs,
    totalItems: Number(t.total_items || 0),
    returnedItems: Number(t.returned_items || 0),
    qualifiedItems: resultMeta.qualified_items ?? undefined,
    minTotalScore: resultMeta.min_total_score ?? undefined,
    finishedAt: t.finished_at || dayjs().format('YYYY-MM-DD HH:mm:ss')
  }
}

function defaultMinTotalScoreForMode(m: ModeType) {
  return DEFAULT_MIN_TOTAL_SCORE[m]
}

function applyQualityMeta(data: Record<string, any>) {
  const minScore = data.min_total_score
  const qualified = data.qualified_items
  const pool = data.total_scored_pool ?? data.total_items
  resultMeta.min_total_score = minScore != null && minScore !== '' ? Number(minScore) : null
  resultMeta.qualified_items = qualified != null && qualified !== '' ? Number(qualified) : null
  resultMeta.total_scored_pool = pool != null && pool !== '' ? Number(pool) : null
}

function applyResult(data: Record<string, any>) {
  resultMeta.result_id = String(data.result_id ?? '')
  resultMeta.trade_date = String(data.trade_date ?? '')
  resultMeta.factor_version = String(data.factor_version ?? 'v1')
  resultMeta.run_status = String(data.run_status ?? '')
  resultMeta.processed_candidates = Number(data.processed_candidates || 0)
  resultMeta.total_candidates = Number(data.total_candidates || 0)
  resultMeta.cross_section_count =
    data.cross_section_count != null ? Number(data.cross_section_count) : null
  resultMeta.display_top_n = data.top_n != null ? Number(data.top_n) : null
  resultMeta.score_type = String(data.score_type ?? '')
  resultMeta.score_type_note = String(data.score_type_note ?? '')
  const snap = data.factor_snapshot
  resultMeta.factor_snapshot =
    snap && typeof snap === 'object' && !Array.isArray(snap) ? (snap as Record<string, unknown>) : null
  if (data.tradability && typeof data.tradability === 'object') {
    resultMeta.factor_snapshot = {
      ...(resultMeta.factor_snapshot || {}),
      tradability: data.tradability as Record<string, unknown>
    }
  }
  applyQualityMeta(data)
  const incoming = Array.isArray(data.items) ? data.items : Array.isArray(data.list) ? data.list : []
  // 每次结果都是 page=1 的完整快照（result_id 与 meta 已整体替换），必须「替换」而非与
  // 上一轮求并集；否则换策略 / 改 topN / 换日期重跑会残留上一轮的陈旧行，且成功提示条数虚高。
  const byKey = new Map<string, Record<string, any>>()
  for (const row of incoming) {
    const key = `${String(row.stock_code || '')}|${String(row.exchange_type || '')}`
    if (key === '|') continue
    byKey.set(key, row)
  }
  results.value = Array.from(byKey.values()).sort((a, b) => {
    const ar = Number(a.rank_no ?? Number.MAX_SAFE_INTEGER)
    const br = Number(b.rank_no ?? Number.MAX_SAFE_INTEGER)
    if (ar !== br) return ar - br
    return scoreOf(b) - scoreOf(a)
  })
  applyFocusSelection()
}

function applyFocusSelection() {
  const sorted = [...results.value]
    .filter((item) => scoreOf(item) >= Number(focusForm.minScore || 0))
    .sort((a, b) => scoreOf(b) - scoreOf(a))
    .slice(0, focusForm.topN)
    .map((item, index) => ({ ...item, local_rank: index + 1 }))
  focusResults.value = sorted
}

async function runAndWaitTask(payload: Record<string, unknown>) {
  stopPolling('已启动新任务，停止旧任务监控')
  taskError.value = ''
  pollStopReason.value = ''
  pollStopAt.value = ''
  const created = await scoringApi.runUnifiedScoringTask(payload as any)
  taskId.value = created.task_id
  taskStatus.value = created.status
  taskProgress.value = Number(created.progress_pct || 0)
  taskMessage.value = String(created.message || '任务已创建')
  pendingAhead.value = Number(created.pending_ahead || 0)
  runningCount.value = Number(created.running_count || 0)
  const serial = ++pollSerial.value
  isPollingActive.value = true
  let errorCount = 0

  try {
    while (serial === pollSerial.value) {
      try {
        const t = await scoringApi.getUnifiedScoringTask(created.task_id)
        errorCount = 0
        setTaskState(t)
        if (t.result_id && (t.status === 'RUNNING' || t.status === 'PENDING')) {
          await syncPartialResult(t.result_id)
        }
        if (t.status === 'SUCCESS') {
          if (t.result_id) {
            await syncPartialResult(t.result_id)
          }
          buildTaskSummary(t)
          return t
        }
        if (t.status === 'FAILED') {
          throw new Error(t.error_message || t.message || '任务执行失败')
        }
        if (t.status === 'CANCELLED') {
          throw new Error(t.message || '任务已取消')
        }
      } catch (e: unknown) {
        errorCount += 1
        const msg = errorText(e)
        if (msg.includes('任务执行失败')) {
          throw e
        }
        taskMessage.value = `进度查询波动，正在重试（${errorCount}/6）`
        if (errorCount >= 6) {
          throw new Error('进度查询连续失败，任务可能仍在执行。可点击"恢复监控"继续查看。')
        }
      }
      await sleep(Math.min(2000 + errorCount * 1000, 6000))
    }
    if (pollStopReason.value) {
      throw new Error(pollStopReason.value)
    }
    throw new Error('任务监控已停止')
  } finally {
    if (serial === pollSerial.value) {
      isPollingActive.value = false
    }
  }
}

async function syncPartialResult(resultId: string) {
  try {
    const page = await scoringApi.getUnifiedResult(resultId, { page: 1, size: currentScorePoolSize() })
    const items = Array.isArray(page.list) ? page.list : Array.isArray(page.items) ? page.items : []
    if (!items.length) return
    applyResult(page as Record<string, any>)
    taskMessage.value = `任务执行中，已同步 ${items.length} 条阶段结果`
  } catch {
    // 阶段结果尚未落库时忽略
  }
}

async function refreshTaskStatus() {
  if (!taskId.value) return
  try {
    const t = await scoringApi.getUnifiedScoringTask(taskId.value)
    setTaskState(t)
    queryTaskId.value = taskId.value
    if (t.result_id) {
      await syncPartialResult(t.result_id)
    }
  } catch (e: unknown) {
    toast.error(errorText(e))
  }
}

async function refreshResultData() {
  if (!resultMeta.result_id) return
  loading.value = true
  try {
    const data = await scoringApi.getUnifiedResult(resultMeta.result_id, { page: 1, size: currentScorePoolSize() })
    applyResult(data as Record<string, any>)
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function queryByTaskId() {
  const id = queryTaskId.value.trim()
  if (!id) {
    toast.warning('请输入任务ID')
    return
  }
  try {
    const t = await scoringApi.getUnifiedScoringTask(id)
    setTaskState(t)
    taskId.value = id
    if (t.result_id) {
      resultMeta.result_id = t.result_id
      queryResultId.value = t.result_id
      await refreshResultData()
    }
  } catch (e: unknown) {
    toast.error(errorText(e))
  }
}

async function queryByResultId() {
  const id = queryResultId.value.trim()
  if (!id) {
    toast.warning('请输入结果ID')
    return
  }
  loading.value = true
  try {
    const data = await scoringApi.getUnifiedResult(id, { page: 1, size: currentScorePoolSize() })
    applyResult(data as Record<string, any>)
    resultMeta.result_id = id
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function loadRecentTasks() {
  recentTasksLoading.value = true
  try {
    recentTasks.value = await scoringApi.getRecentUnifiedTasks()
  } catch {
    // silent
  } finally {
    recentTasksLoading.value = false
  }
}

async function loadHistoryTask(taskId: string) {
  if (!taskId) return
  try {
    const t = await scoringApi.getUnifiedScoringTask(taskId)
    setTaskState(t)
    if (t.result_id) {
      resultMeta.result_id = t.result_id
      queryResultId.value = t.result_id
      await refreshResultData()
      toast.success('已载入历史结果')
    } else {
      toast.info('该任务尚无完成的结果')
    }
  } catch (e: unknown) {
    toast.error(errorText(e))
  }
}

/** 保存「结果ID + 关注清单配置」到本地，刷新后仍可回看同一结果。 */
function saveFocusSnapshot() {
  if (!resultMeta.result_id) {
    toast.warning('请先运行或查询一个结果')
    return
  }
  const snapshot = {
    result_id: resultMeta.result_id,
    topN: focusForm.topN,
    minScore: focusForm.minScore,
    savedAt: new Date().toISOString()
  }
  localStorage.setItem(FOCUS_SNAPSHOT_KEY, JSON.stringify(snapshot))
  toast.success('关注快照已保存到本地')
}

/** 载入最近一次保存的关注快照并定位到该结果。 */
async function loadFocusSnapshot() {
  const raw = localStorage.getItem(FOCUS_SNAPSHOT_KEY)
  if (!raw) {
    toast.info('暂无已保存的关注快照')
    return
  }
  try {
    const snapshot = JSON.parse(raw)
    focusForm.topN = Number(snapshot.topN) || 10
    focusForm.minScore = Number(snapshot.minScore) || 0
    if (snapshot.result_id) {
      queryResultId.value = snapshot.result_id
      await queryByResultId()
    }
    toast.success('已载入关注快照')
  } catch {
    toast.error('关注快照数据损坏')
  }
}

function currentStrategy(): UnifiedScoringStrategy {
  const map: Record<ModeType, UnifiedScoringStrategy> = {
    classic: 'CLASSIC_SELECTOR',
    oversold: 'OVERSOLD_REBOUND',
    trend: 'TREND',
    comprehensive: 'COMPREHENSIVE',
    multi_dim: 'MULTI_DIM'
  }
  return map[mode.value]
}

function currentTradeDate() {
  if (mode.value === 'classic') return form.tradeDate
  if (mode.value === 'oversold') return oversoldForm.tradeDate
  return commonForm.tradeDate
}

function currentScorePoolSize() {
  if (mode.value === 'classic') return form.scoreTopN
  if (mode.value === 'oversold') return oversoldForm.scoreTopN
  return commonForm.scoreTopN
}

function buildGenericPayload(): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    strategy: currentStrategy(),
    tradeDate: commonForm.tradeDate,
    topN: commonForm.scoreTopN,
    minTotalScore: commonForm.minTotalScore,
    minAvgAmount: commonForm.minAvgAmount,
    excludeST: commonForm.excludeST,
    minListingDays: commonForm.minListingDays,
    persist: true
  }
  if (mode.value === 'comprehensive') {
    payload.lookbackDays = commonForm.lookbackDays
  }
  if (mode.value === 'trend') {
    payload.biasThreshold = commonForm.biasThreshold
  }
  return payload
}

function scoringCompleteToast() {
  if (results.value.length === 0 && resultMeta.min_total_score != null) {
    toast.warning(`无符合质量闸（≥ ${resultMeta.min_total_score} 分）的候选`)
    return
  }
  toast.success(`打分完成，共 ${results.value.length} 只`)
}

async function runSelection() {
  running.value = true
  loading.value = true
  try {
    const done = await runAndWaitTask({
      strategy: 'CLASSIC_SELECTOR',
      tradeDate: form.tradeDate,
      fullHistory: form.fullHistory,
      lookbackDays: form.fullHistory ? undefined : form.lookbackDays,
      topN: form.scoreTopN,
      minAvgAmount: form.minAvgAmount,
      minTrendScore: form.minTrendScore,
      minListingDays: form.minListingDays,
      horizonProfile: form.horizonProfile,
      minCodexScore: form.horizonProfile === 'SHORT_3_5D' ? form.minCodexScore : undefined,
      templateId: form.templateId || undefined
    })
    const data = done.result_id
      ? await scoringApi.getUnifiedResult(done.result_id, { page: 1, size: form.scoreTopN })
      : await scoringApi.getUnifiedLatest({ strategy: 'CLASSIC_SELECTOR', tradeDate: form.tradeDate, page: 1, size: form.scoreTopN })
    applyResult(data)
    scoringCompleteToast()
  } catch (e: unknown) {
    taskError.value = errorText(e)
    toast.error(taskError.value)
  } finally {
    running.value = false
    loading.value = false
  }
}

async function runOversold() {
  running.value = true
  loading.value = true
  try {
    const done = await runAndWaitTask({
      strategy: 'OVERSOLD_REBOUND',
      tradeDate: oversoldForm.tradeDate,
      topN: oversoldForm.scoreTopN,
      minTotalScore: oversoldForm.minTotalScore,
      minAvgAmount: oversoldForm.minAvgAmount,
      excludeST: oversoldForm.excludeST,
      minListingDays: MIN_LISTING_DAYS_DEFAULT,
      persist: oversoldForm.persist,
      templateId: oversoldForm.templateId || undefined
    })
    const data = done.result_id
      ? await scoringApi.getUnifiedResult(done.result_id, { page: 1, size: oversoldForm.scoreTopN })
      : await scoringApi.getUnifiedLatest({ strategy: 'OVERSOLD_REBOUND', tradeDate: oversoldForm.tradeDate, page: 1, size: oversoldForm.scoreTopN })
    applyResult(data)
    scoringCompleteToast()
  } catch (e: unknown) {
    taskError.value = errorText(e)
    toast.error(taskError.value)
  } finally {
    running.value = false
    loading.value = false
  }
}

async function runGeneric() {
  running.value = true
  loading.value = true
  const strategy = currentStrategy()
  try {
    const payload = buildGenericPayload()
    const done = await runAndWaitTask(payload)
    const data = done.result_id
      ? await scoringApi.getUnifiedResult(done.result_id, { page: 1, size: commonForm.scoreTopN })
      : await scoringApi.getUnifiedLatest({ strategy, tradeDate: commonForm.tradeDate, page: 1, size: commonForm.scoreTopN })
    applyResult(data)
    scoringCompleteToast()
  } catch (e: unknown) {
    taskError.value = errorText(e)
    toast.error(taskError.value)
  } finally {
    running.value = false
    loading.value = false
  }
}

async function resumeTaskWatch() {
  if (!taskId.value) {
    toast.warning('没有可恢复监控的任务')
    return
  }
  running.value = true
  try {
    const serial = ++pollSerial.value
    isPollingActive.value = true
    pollStopReason.value = ''
    pollStopAt.value = ''
    taskError.value = ''
    taskMessage.value = '已恢复监控，正在同步进度...'
    let errorCount = 0

    while (serial === pollSerial.value) {
      try {
        const t = await scoringApi.getUnifiedScoringTask(taskId.value)
        errorCount = 0
        setTaskState(t)
        if (t.result_id && (t.status === 'RUNNING' || t.status === 'PENDING')) {
          await syncPartialResult(t.result_id)
        }
        if (t.status === 'SUCCESS') {
          buildTaskSummary(t)
          const data = t.result_id
            ? await scoringApi.getUnifiedResult(t.result_id, { page: 1, size: currentScorePoolSize() })
            : await scoringApi.getUnifiedLatest({
                strategy: currentStrategy(),
                tradeDate: currentTradeDate(),
                page: 1,
                size: currentScorePoolSize()
              })
          applyResult(data)
          toast.success(`任务已完成，共 ${results.value.length} 只`)
          return
        }
        if (t.status === 'FAILED') {
          throw new Error(t.error_message || t.message || '任务执行失败')
        }
        if (t.status === 'CANCELLED') {
          throw new Error(t.message || '任务已取消')
        }
      } catch (e: unknown) {
        errorCount += 1
        const msg = errorText(e)
        if (msg.includes('任务执行失败')) {
          throw e
        }
        taskMessage.value = `恢复监控后进度查询波动，重试中（${errorCount}/6）`
        if (errorCount >= 6) {
          throw new Error('恢复监控失败：进度查询连续失败，请稍后重试。')
        }
      }
      await sleep(Math.min(2000 + errorCount * 1000, 6000))
    }
    throw new Error(pollStopReason.value || '任务监控已停止')
  } catch (e: unknown) {
    taskError.value = errorText(e)
    toast.error(taskError.value)
  } finally {
    isPollingActive.value = false
    running.value = false
  }
}

function cancelTaskWatch() {
  stopPolling('用户手动停止任务监控（后端任务仍在执行）')
  running.value = false
}

async function cancelTaskServer() {
  if (!taskId.value) return
  try {
    const ret = await scoringApi.cancelUnifiedScoringTask(taskId.value)
    const msg = String(ret?.message || '')
    taskMessage.value = msg || '任务已取消'
    await resumeTaskWatch()
  } catch (e: unknown) {
    toast.error(errorText(e))
  }
}

async function prioritizeCurrentTask() {
  if (!taskId.value) return
  try {
    const ret = await scoringApi.prioritizeUnifiedScoringTask(taskId.value)
    const msg = String(ret?.message || '已触发优先执行')
    taskMessage.value = msg
    toast.success(msg)
    await resumeTaskWatch()
  } catch (e: unknown) {
    toast.error(errorText(e))
  }
}

async function clearPendingQueue() {
  try {
    const ret = await scoringApi.clearPendingUnifiedScoringTasks()
    const msg = String(ret?.message || '')
    if (msg) {
      taskMessage.value = msg
      toast.success(msg)
    }
    if (taskId.value) {
      await resumeTaskWatch()
    }
  } catch (e: unknown) {
    toast.error(errorText(e))
  }
}

async function forceRestartCurrentTask() {
  if (!taskId.value) {
    toast.warning('没有可重跑的任务')
    return
  }
  const oldTaskId = taskId.value
  try {
    stopPolling('准备重跑任务，先清理队列与当前执行')
    const clearRet = await scoringApi.clearPendingUnifiedScoringTasks()
    let cancelMsg = ''
    if (taskStatus.value === 'PENDING' || taskStatus.value === 'RUNNING') {
      const cancelRet = await scoringApi.cancelUnifiedScoringTask(oldTaskId)
      cancelMsg = String(cancelRet?.message || '')
    }
    const clearMsg = String(clearRet?.message || '')
    const summary = [clearMsg, cancelMsg].filter(Boolean).join('；')
    if (summary) {
      taskMessage.value = summary
      toast.success(summary)
    }
    if (mode.value === 'classic') {
      await runSelection()
    } else if (mode.value === 'oversold') {
      await runOversold()
    } else {
      await runGeneric()
    }
  } catch (e: unknown) {
    const msg = errorText(e)
    taskError.value = msg
    toast.error(msg)
  }
}

async function loadTemplates() {
  templates.value = await selectorApi.listTemplates()
}

async function saveTemplate() {
  const name = `经典-${dayjs().format('MMDD-HHmm')}`
  await selectorApi.saveTemplate({
    templateId: form.templateId || undefined,
    name,
    params: {
      fullHistory: form.fullHistory,
      lookbackDays: form.lookbackDays,
      topN: form.scoreTopN,
      minAvgAmount: form.minAvgAmount,
      minTrendScore: form.minTrendScore
    }
  })
  toast.success('模板已保存')
  await loadTemplates()
}

async function saveOversoldTemplate() {
  const name = `超跌反弹-${dayjs().format('MMDD-HHmm')}`
  await selectorApi.saveTemplate({
    templateId: oversoldForm.templateId || undefined,
    name,
    params: {
      topN: oversoldForm.scoreTopN,
      minTotalScore: oversoldForm.minTotalScore,
      minAvgAmount: oversoldForm.minAvgAmount,
      excludeST: oversoldForm.excludeST,
      persist: oversoldForm.persist
    }
  })
  toast.success('模板已保存')
  await loadTemplates()
}

function exportCsv() {
  const rows = tableRows.value
  if (!rows.length) return
  const codex = form.horizonProfile === 'SHORT_3_5D'
  const headers = codex
    ? ['rank_no', 'stock_code', 'stock_name', 'final_score', 'T', 'F', 'R', 'neutralization_mode', 'neutralization_industries', 'score_percentile']
    : ['rank_no', 'stock_code', 'stock_name', 'final_score', 'trend_score', 'momentum_score', 'volume_score', 'ret20', 'ret60']
  const lines = [
    `# trade_date=${resultMeta.trade_date || ''}`,
    `# factor_version=${resultMeta.factor_version || ''}`,
    `# tradability=${tradabilitySummary.value || ''}`,
    headers.join(',')
  ]
  for (const r of rows) {
    const row: Record<string, unknown> = {
      ...r,
      rank_no: focusForm.view === 'focus' ? r.local_rank : r.rank_no
    }
    if (codex) {
      row.T = formatCodexFactor(r, 'T')
      row.F = formatCodexFactor(r, 'F')
      row.R = formatCodexFactor(r, 'R')
      row.neutralization_mode = formatNeutralizationMode(r)
      row.neutralization_industries = formatCodexFactor(r, 'neutralization_industries')
      row.score_percentile = formatCodexFactor(r, 'score_percentile')
    }
    lines.push(headers.map((h) => String(row[h] ?? '')).join(','))
  }
  const suffix = focusForm.view === 'focus' ? 'focus' : 'all'
  downloadCsv(lines, `score-picker-${suffix}-${resultMeta.trade_date || dayjs().format('YYYY-MM-DD')}.csv`)
}

function downloadCsv(lines: string[], filename: string) {
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

async function loadLatest() {
  loading.value = true
  try {
    const data = await scoringApi.getUnifiedLatest({
      strategy: currentStrategy(),
      tradeDate: currentTradeDate(),
      page: 1,
      size: currentScorePoolSize()
    })
    applyResult(data)
    if (!results.value.length) toast.info('当日暂无结果，请先执行打分')
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}

function showDetail(row: Record<string, any>) {
  detailRow.value = row
  detailVisible.value = true
}

function onModeChange() {
  stopPolling('切换策略，已停止当前任务监控')
  resetTaskState()
  results.value = []
  focusResults.value = []
  if (isGenericMode.value) {
    commonForm.minTotalScore = defaultMinTotalScoreForMode(mode.value)
  }
  loadLatest().catch(() => {})
}

onMounted(async () => {
  await loadTemplates()
  await loadLatest()
  await loadRecentTasks()
})

onBeforeUnmount(() => {
  stopPolling('页面离开，已停止任务监控')
})
</script>

<style scoped>
.focus-form {
  margin-bottom: 10px;
}
.meta {
  display: flex;
  gap: 20px;
  margin: 8px 0 12px;
  color: var(--text-secondary);
  font-size: 13px;
  flex-wrap: wrap;
}
.run-alert {
  margin: 0 0 12px;
}
.run-meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  font-size: 13px;
  margin-bottom: 6px;
}
.run-actions {
  margin-bottom: 6px;
}
.task-query-form {
  margin: 8px 0 12px;
}
.tip {
  color: var(--text-secondary);
  font-size: 12px;
  margin-top: 12px;
}
.score-type-alert {
  margin: 8px 0;
}
.empty-reason {
  color: var(--text-secondary);
  font-size: 13px;
}
.reason-table {
  width: 100%;
}
.snapshot-json {
  margin-top: 12px;
}
.json-box {
  margin: 0;
  white-space: pre-wrap;
  background: var(--surface-2);
  border: 1px solid var(--glass-border);
  border-radius: 6px;
  padding: 8px;
  max-height: 220px;
  overflow: auto;
  color: var(--text-primary);
}
</style>
