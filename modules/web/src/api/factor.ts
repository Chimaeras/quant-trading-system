import request, { unwrap } from './request'

export interface FactorDistribution {
  min: number
  p25: number
  median: number
  p75: number
  max: number
}

export interface FactorGroupBand {
  group_no: number
  min_score: number
  max_score: number
  avg_score: number
  count: number
}

export interface FactorDailyRankIc {
  trade_date: string
  rank_ic: number
  sample_size: number
}

export interface FactorGroupReturn {
  group_no: number
  label: string
  avg_return: number
  observations: number
}

export interface FactorRankIc {
  holding_days: number
  group_count: number
  basis_note: string
  universe_note: string
  metric_scope?: string
  metric_scopes?: string[]
  executable_metric_scope?: string
  feature_snapshot_note?: string
  cross_section_days: number
  total_observations: number
  rank_ic_mean: number
  rank_ic_std: number
  rank_ic_ir: number
  rank_ic_positive_rate: number
  long_short_spread: number
  daily_rank_ic: FactorDailyRankIc[]
  grouped_returns: FactorGroupReturn[]
}

export interface FactorResearchFunnel {
  universe_count?: number
  feature_valid_count?: number
  selector_item_count?: number
  tradable_count?: number | null
  tradable_status?: string
  metric_scope?: string
  note?: string
}

export interface FactorResearchResponse {
  /** FULL_FEATURE_SNAPSHOT（权威）或 LEGACY_SELECTION_BIASED（sparse selector，V2-MP-022）。 */
  source?: string
  strategy: string
  start_date: string
  end_date: string
  cross_section_days: number
  total_observations: number
  valid_observations: number
  coverage: number
  distribution: FactorDistribution
  group_bands: FactorGroupBand[]
  rank_ic: FactorRankIc
  funnel?: FactorResearchFunnel
  metric_scopes?: string[]
  metric_scope?: string
  executable_metric_scope?: string
  feature_snapshot_note?: string
  universe_note?: string
  note: string
}

export async function getFactorResearch(params: {
  strategy?: string
  startDate: string
  endDate: string
  holdingDays?: number
}): Promise<FactorResearchResponse> {
  const res = await request.get<FactorResearchResponse>('/factor/research', { params })
  return unwrap(res)
}

// ── V2-MP-022：完整 PIT 快照截面 IC（权威路径）──────────────────────────────
// 输入 MP-016 feature snapshot，输出必含 snapshot/schema/label/metricScope/sample funnel；
// EXECUTABLE 口径仅对 label_v2 execStatus=FILLED 样本给出；不读 selector_result / qfq。

export interface SnapshotScopeIc {
  metric_scope?: string
  status?: string
  basis_note?: string
  embargo_note?: string
  universe_note?: string
  cross_section_days: number
  total_observations: number
  rank_ic_mean: number
  rank_ic_std: number
  rank_ic_ir: number
  rank_ic_positive_rate: number
  pearson_ic_mean: number
  monotonicity: string
  hac_std: number
  ci_lower: number
  ci_upper: number
  long_short_spread: number
  daily_rank_ic: FactorDailyRankIc[]
  grouped_returns: FactorGroupReturn[]
  note?: string
}

export interface SnapshotDailyFunnelRow {
  signal_date: string
  rows_total: number
  scored_valid: number
  raw_observations: number
  cost_approx_observations: number
  executable_observations: number
  labels_matched: number
  labels_missing: number
}

export interface SnapshotResearchFunnel {
  universe_source?: string
  partitions_reusable?: number
  partitions_skipped?: string[]
  cross_section_days?: number
  rows_total?: number
  scored_valid?: number
  coverage?: number
  label_version?: string | null
  exec_status_breakdown?: Record<string, number>
  tradable_status?: string
  tradable_note?: string
  metric_scope?: string
  note?: string
}

export interface SnapshotResearchResponse {
  source: string
  snapshot_id: string
  strategy: string
  feature_version: string
  schema_hash: string
  label_version?: string | null
  start_date: string
  end_date: string
  holding_days: number
  universe_source: string
  cross_section_days: number
  partitions_reusable: number
  partitions_skipped: string[]
  rows_total: number
  scored_valid: number
  coverage: number
  metric_scopes: string[]
  executable_metric_scope: string
  funnel?: SnapshotResearchFunnel
  daily_funnel: SnapshotDailyFunnelRow[]
  scopes: Record<string, SnapshotScopeIc>
  note?: string
}

export async function getSnapshotFactorResearch(params: {
  strategy: string
  snapshotId: string
  startDate: string
  endDate: string
  holdingDays?: number
  labelVersion?: string
  featureVersion?: string
}): Promise<SnapshotResearchResponse> {
  const res = await request.get<SnapshotResearchResponse>('/factor/snapshot-research', {
    params,
    timeout: 60000
  })
  return unwrap(res)
}

/** 统一因子报告：T+1/2/3/5 多周期并列（Pearson/Rank IC/ICIR/单调性/多空 spread + 成本后近似 IC）。 */
export interface UnifiedReportHorizonRow {
  holding_days: number
  basis_note?: string
  net_basis_note?: string
  embargo_note?: string
  cross_section_days: number
  total_observations: number
  rank_ic_mean: number
  rank_ic_std: number
  rank_ic_ir: number
  rank_ic_positive_rate: number
  pearson_ic_mean: number
  monotonicity: string
  hac_std: number
  ci_lower: number
  ci_upper: number
  long_short_spread: number
  /** 成本后近似 IC（尚未接入涨跌停/停牌拒单，勿称为完整可执行） */
  executable_net_ic?: {
    cross_section_days: number
    total_observations: number
    rank_ic_mean: number
    rank_ic_std: number
    rank_ic_ir: number
    rank_ic_positive_rate: number
    pearson_ic_mean: number
    long_short_spread: number
  }
}

export interface UnifiedFactorReport {
  /** LEGACY_SELECTION_BIASED（V2-MP-022）：sparse selector_result，仅供历史对照。 */
  source?: string
  strategy: string
  start_date: string
  end_date: string
  horizons: number[]
  by_horizon: UnifiedReportHorizonRow[]
  note?: string
  metric_scopes?: string[]
  metric_scope?: string
  executable_metric_scope?: string
  feature_snapshot_note?: string
  universe_note?: string
  funnel?: FactorResearchFunnel
}

export async function getUnifiedFactorReport(params: {
  strategy?: string
  startDate: string
  endDate: string
}): Promise<UnifiedFactorReport> {
  const res = await request.get<UnifiedFactorReport>('/factor/unified-report', { params })
  return unwrap(res)
}
