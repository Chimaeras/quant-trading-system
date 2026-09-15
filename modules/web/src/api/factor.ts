import request, { unwrap } from './request'

/**
 * 分组单调性标签。
 * `NO_DISPERSION` = 各组收益**全相等**（零区分度）：既不是上升也不是下降，
 * 不得当作任一方向（后端 Q-R006-07 / Q-R013-01）。`N/A` = 可用组不足 2 或组均值非有限。
 */
export type MonotonicityLabel =
  | 'MONOTONIC_INCREASING'
  | 'MONOTONIC_DECREASING'
  | 'NON_MONOTONIC'
  | 'NO_DISPERSION'
  | 'N/A'

/**
 * HAC/bootstrap 区间状态：
 * `INSUFFICIENT_N` = 截面日不足 2（HAC 标准误与区间端点均为 null，不可读作「精确估计」）；
 * `UNAVAILABLE` = 区间端点缺失/无定义（不得谎报零宽区间）；
 * `ZERO_WIDTH` = 下界不小于上界；`OK` = 正常。
 */
export type CiStatus = 'OK' | 'INSUFFICIENT_N' | 'ZERO_WIDTH' | 'UNAVAILABLE'

/** 三口径族共用的**纯增量**披露字段（命中率/丢弃计数/构造规则；后端未改任何既有键语义）。 */
export interface FactorIcDisclosure {
  /** 本口径的价格基准：legacy/snapshot 毛收益面为 qfq 前复权 / raw 不复权，跨面比较前必看 */
  price_basis?: string
  /** 口径状态：OK / NO_OBSERVATIONS / UNAVAILABLE */
  status?: string
  /** 净收益口径是否真的有数据（false 时其 0.0 是占位，不是「成本后恰为 0」） */
  data_available?: boolean
  min_cross_section_size?: number
  /** 规模闸的比较基准：INPUT_ROWS = 按输入行数（剔除非有限观测前） */
  min_cross_section_size_basis?: string
  input_cross_section_days?: number
  days_dropped_below_min_cross_section?: number
  days_dropped_non_finite_ic?: number
  excluded_non_finite_observations?: number
  groups_with_observations?: number
  pearson_support_days?: number
  rank_ic_std_basis?: string
  rank_ic_std_basis_note?: string
  /** 主口径 `rank_ic_std` 分母为**总体标准差 ÷T**；并列键 `rank_ic_std_sample` 用 ÷(T−1)（比值恒为 sqrt(T/(T−1))） */
  rank_ic_std_sample_basis?: string
  rank_ic_std_sample_note?: string
  group_boundary_rule?: string
  group_boundary_rule_note?: string
  rounding_rule?: string
  rounding_rule_note?: string
  ci_status?: CiStatus
  long_short_spread_basis?: string
  long_short_spread_basis_note?: string
  /** 本块两腿价差的成本基准：GROSS_BEFORE_COSTS / NET_AFTER_MODELED_COSTS / NOT_APPLICABLE_NO_OBSERVATIONS */
  long_short_spread_cost_basis?: string
  long_short_spread_cost_basis_note?: string
  /** 单腿往返成本（乘性口径：佣金+印花税+双边滑点复合）；两条腿共 2× 本值 */
  cost_per_leg_round_trip?: number | null
  cost_per_leg_round_trip_exact?: number | null
  cost_per_leg_round_trip_basis?: string
  cost_per_leg_round_trip_note?: string
  /** 美元中性净价差 = long_short_spread − 2×单腿往返成本；本块本身已是净价差时**不适用**，为 null */
  long_short_spread_neutral?: number | null
  long_short_spread_neutral_exact?: number | null
  long_short_spread_neutral_basis?: string
  long_short_spread_neutral_note?: string
  pearson_ic_mean_note?: string
  /** 取整策略：载荷为 4 位小数四舍五入，同名的 *_exact 键给出未取整原值 */
  precision_policy?: string
  precision_policy_note?: string
  /** 并列口径：样本标准差（÷(T−1)）的 IC 标准差；无离散度/无观测时为 null */
  rank_ic_std_sample?: number | null
  /** 并列口径：由 rank_ic_std_sample 算出的 ICIR；分母为 0 或无定义时为 null */
  rank_ic_ir_sample?: number | null
  rank_ic_std_sample_exact?: number | null
  /** 聚合面的未取整原值（主键为 4 位小数四舍五入结果） */
  rank_ic_mean_exact?: number | null
  rank_ic_std_exact?: number | null
  rank_ic_ir_exact?: number | null
  rank_ic_positive_rate_exact?: number | null
  pearson_ic_mean_exact?: number | null
  hac_std_exact?: number | null
  ci_lower_exact?: number | null
  ci_upper_exact?: number | null
  long_short_spread_exact?: number | null
  /** 聚合面 grouped_returns[].observations 的单位（截面日数，不是成分股数） */
  grouped_returns_observations_basis?: string
  grouped_returns_observations_basis_note?: string
}

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
  /** 该日被剔除的非有限观测数（不参与计算，披露用） */
  excluded_observations?: number
  /** 未取整 Rank IC（`rank_ic` 为 4 位小数四舍五入结果） */
  rank_ic_exact?: number
}

export interface FactorGroupReturn {
  group_no: number
  label: string
  /** 组均值；**该组无观测（observations = 0）时为 null**，不得渲染成 0 */
  avg_return: number | null
  /** 单位取决于聚合层级：载荷内为**参与聚合的截面日数**，不是成分股数（见 grouped_returns_observations_basis） */
  observations: number
  /** 空组标记：avg_return = null 表示无观测、不参与方向断言与极差 */
  empty?: boolean
  /** 未取整组均值 */
  avg_return_exact?: number | null
}

export interface FactorRankIc extends FactorIcDisclosure {
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
  /** Rank IC 均值；无可用截面日时不可计算，为 null（不得渲染成 0） */
  rank_ic_mean: number | null
  /** Rank IC 总体标准差（÷T）；无可用截面日时为 null；真零离散度仍为 0 */
  rank_ic_std: number | null
  /** ICIR；IC 序列无离散度（std = 0）时**无定义**，为 null（≠「IR 恰为 0」） */
  rank_ic_ir: number | null
  /** IC 为正的截面日占比；无可用截面日时为 null；真为 0 时仍为 0 */
  rank_ic_positive_rate: number | null
  /**
   * 线性（Pearson）IC 均值。**不可定义时为 null**（样本不足、任一侧无区分度等），
   * 不再以占位 0.0 冒充「线性 IC 恰为 0」；null 时同时看 `pearson_support_days`。
   */
  pearson_ic_mean: number | null
  monotonicity: MonotonicityLabel
  /** HAC 标准误；截面日不足 2 时无定义，为 null（配 `ci_status = INSUFFICIENT_N`） */
  hac_std: number | null
  /** 名义 95% 区间下界；与 `hac_std` 同为 null 时不得渲染成 0/零宽区间 */
  ci_lower: number | null
  ci_upper: number | null
  /** 高分组均值 − 低分组均值；无非空分组时为 null（不得渲染成「恰好中性」） */
  long_short_spread: number | null
  daily_rank_ic: FactorDailyRankIc[]
  grouped_returns: FactorGroupReturn[]
}

export interface FactorResearchFunnel {
  universe_count?: number
  /** 有 cross_section_count 快照的截面日数（universe_count 是这些日的加总） */
  universe_days_with_snapshot?: number
  /** 该行的漏斗截面日数（= 去重后的信号日数） */
  cross_section_days?: number
  /** universe_count 的来源；为 FALLBACK_TOTAL_OBSERVATIONS 时该值**不可**读作真实候选池规模 */
  universe_count_source?: string
  universe_count_fallback?: boolean
  universe_count_note?: string
  universe_source?: string
  feature_valid_count?: number
  valid_observations?: number
  selector_item_count?: number
  total_observations?: number
  /** 键别名说明（feature_valid_count ≡ valid_observations 等：同一变量两个键名，不是两个量） */
  key_alias_note?: string
  /** 数值取整规则（本载荷为 4 位小数四舍五入，同名的 *_exact 键给出未取整值） */
  rounding_rule?: string
  tradable_count?: number | null
  tradable_status?: string
  tradable_note?: string
  metric_scope?: string
  /** metric_scope 的继承说明：本块的 COST_APPROX 是家族继承值，块内只有样本覆盖口径数值 */
  metric_scope_note?: string
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

export interface SnapshotScopeIc extends FactorIcDisclosure {
  metric_scope?: string
  basis_note?: string
  embargo_note?: string
  universe_note?: string
  cross_section_days: number
  total_observations: number
  /** Rank IC 均值；无可用截面日时不可计算，为 null（不得渲染成 0） */
  rank_ic_mean: number | null
  /** Rank IC 总体标准差（÷T）；无可用截面日时为 null；真零离散度仍为 0 */
  rank_ic_std: number | null
  /** ICIR；IC 序列无离散度时为 null（≠ 0） */
  rank_ic_ir: number | null
  /** IC 为正的截面日占比；无可用截面日时为 null；真为 0 时仍为 0 */
  rank_ic_positive_rate: number | null
  /** 线性（Pearson）IC 均值；**不可定义时为 null**（不再以占位 0.0 冒充） */
  pearson_ic_mean: number | null
  monotonicity: MonotonicityLabel
  /** HAC 标准误；截面日不足 2 时无定义，为 null（配 `ci_status = INSUFFICIENT_N`） */
  hac_std: number | null
  /** 名义 95% 区间下界；与 `hac_std` 同为 null 时不得渲染成 0/零宽区间 */
  ci_lower: number | null
  ci_upper: number | null
  /** 高分组均值 − 低分组均值；无非空分组时为 null（不得渲染成「恰好中性」） */
  long_short_spread: number | null
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
export interface UnifiedReportHorizonRow extends FactorIcDisclosure {
  holding_days: number
  basis_note?: string
  net_basis_note?: string
  embargo_note?: string
  cross_section_days: number
  total_observations: number
  /** Rank IC 均值；无可用截面日时不可计算，为 null（不得渲染成 0） */
  rank_ic_mean: number | null
  /** Rank IC 总体标准差（÷T）；无可用截面日时为 null；真零离散度仍为 0 */
  rank_ic_std: number | null
  /** ICIR；IC 序列无离散度时为 null（≠ 0） */
  rank_ic_ir: number | null
  /** IC 为正的截面日占比；无可用截面日时为 null；真为 0 时仍为 0 */
  rank_ic_positive_rate: number | null
  /** 线性（Pearson）IC 均值；**不可定义时为 null**（不再以占位 0.0 冒充） */
  pearson_ic_mean: number | null
  monotonicity: MonotonicityLabel
  /** HAC 标准误；截面日不足 2 时无定义，为 null（配 `ci_status = INSUFFICIENT_N`） */
  hac_std: number | null
  /** 名义 95% 区间下界；与 `hac_std` 同为 null 时不得渲染成 0/零宽区间 */
  ci_lower: number | null
  ci_upper: number | null
  /** 高分组均值 − 低分组均值；无非空分组时为 null（不得渲染成「恰好中性」） */
  long_short_spread: number | null
  /** 成本后近似 IC（尚未接入涨跌停/停牌拒单，勿称为完整可执行） */
  cost_net_ic?: {
    cross_section_days: number
    total_observations: number
    /** 无可用截面日时为 null（不得渲染成 0） */
    rank_ic_mean: number | null
    rank_ic_std: number | null
    rank_ic_ir: number | null
    rank_ic_positive_rate: number | null
    /** 不可定义时为 null（原实现写占位 0.0） */
    pearson_ic_mean: number | null
    /** 无非空分组时为 null（不得渲染成「恰好中性」） */
    long_short_spread: number | null
    /** false 时上列统计量为 null（本口径无数据），不是「成本后恰为 0」 */
    data_available?: boolean
    status?: string
    group_count?: number
    grouped_returns?: FactorGroupReturn[]
  } & FactorIcDisclosure
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
