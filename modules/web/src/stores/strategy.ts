import { defineStore } from 'pinia'
import { ref } from 'vue'
import { strategyApi } from '@/api/strategy'
import type { StrategyInfo } from '@/types'

export const useStrategyStore = defineStore('strategy', () => {
  const list = ref<StrategyInfo[]>([])
  const loading = ref(false)

  async function fetchList() {
    loading.value = true
    try {
      list.value = await strategyApi.getStrategyList()
      return list.value
    } finally {
      loading.value = false
    }
  }

  return { list, loading, fetchList }
})
