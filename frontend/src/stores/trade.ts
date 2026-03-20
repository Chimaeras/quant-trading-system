import { defineStore } from 'pinia'
import { ref } from 'vue'
import { tradeApi } from '@/api/trade'
import type { TradeRecord, PositionInfo } from '@/types'

export const useTradeStore = defineStore('trade', () => {
  const tradeList = ref<TradeRecord[]>([])
  const positions = ref<PositionInfo[]>([])
  const loading = ref(false)

  async function fetchTradeList(params?: { page?: number; size?: number; startDate?: string; endDate?: string }) {
    loading.value = true
    try {
      const res = await tradeApi.getTradeList(params || {})
      tradeList.value = res.list
      return res
    } finally {
      loading.value = false
    }
  }

  async function fetchPositions() {
    loading.value = true
    try {
      positions.value = await tradeApi.getPositions()
      return positions.value
    } finally {
      loading.value = false
    }
  }

  return { tradeList, positions, loading, fetchTradeList, fetchPositions }
})
