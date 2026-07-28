import request from './request'

export interface FactorDistribution {
  min: number
  p25: number
  median: number
  p75: number
  max: number
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
  note: string
}

export function getFactorResearch(params: {
  strategy?: string
  startDate: string
  endDate: string
}): Promise<FactorResearchResponse> {
  return request.get('/api/factor/research', { params })
}
