import request from './request'

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

export interface FactorResearchResponse {
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
  note: string
}

export function getFactorResearch(params: {
  strategy?: string
  startDate: string
  endDate: string
  holdingDays?: number
}): Promise<FactorResearchResponse> {
  return request.get('/api/factor/research', { params })
}
